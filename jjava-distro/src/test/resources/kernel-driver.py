"""
Runs a sequence of notebook cells against the installed "java" kernel and prints a
machine-readable transcript of what the kernel sent back.

Usage: python kernel-driver.py <cell source> [<cell source> ...]

Each cell source is a separate argv element, so a cell may contain any number of lines.
Every captured value is printed as a single line, base64-encoded, so that multi-line
values and unrelated kernel log output can not be mistaken for the protocol:

    @@JJAVA@@|<cell number>|<field>|<base64 of the value>

Fields: "status" (always present, "ok" or "error"), "result", "display", "stdout",
"stderr", "error" (present only when non-empty).
"""

import base64
import re
import sys
from queue import Empty

from jupyter_client.manager import start_new_kernel

PREFIX = "@@JJAVA@@"
STARTUP_TIMEOUT = 120
MESSAGE_TIMEOUT = 180

# terminal control sequences the kernel uses to colorize JShell diagnostics
ANSI = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")


def emit(cell_number, field, value):
    if value:
        encoded = base64.b64encode(value.encode("utf-8")).decode("ascii")
        print("%s|%d|%s|%s" % (PREFIX, cell_number, field, encoded), flush=True)


def receive(receiver, channel, cell_number):
    """
    Reads the next message off a kernel channel. Every read is bounded by MESSAGE_TIMEOUT, so a
    kernel that dies or never goes idle fails the test instead of hanging it.
    """
    try:
        return receiver(timeout=MESSAGE_TIMEOUT)
    except Empty:
        raise SystemExit("Cell %d: no %s message within %d seconds"
                         % (cell_number, channel, MESSAGE_TIMEOUT))


def execute(client, cell_number, source):
    msg_id = client.execute(source, allow_stdin=False)

    outputs = {"result": "", "display": "", "stdout": "", "stderr": "", "error": ""}
    while True:
        message = receive(client.get_iopub_msg, "iopub", cell_number)
        if message["parent_header"].get("msg_id") != msg_id:
            continue

        message_type = message["msg_type"]
        content = message["content"]
        if message_type == "status":
            if content["execution_state"] == "idle":
                break
        elif message_type == "stream":
            key = "stdout" if content["name"] == "stdout" else "stderr"
            outputs[key] += content["text"]
        elif message_type == "execute_result":
            outputs["result"] += content["data"].get("text/plain", "")
        elif message_type in ("display_data", "update_display_data"):
            outputs["display"] += content["data"].get("text/plain", "")
        elif message_type == "error":
            lines = ["%s: %s" % (content["ename"], content["evalue"])]
            lines.extend(content["traceback"])
            outputs["error"] += ANSI.sub("", "\n".join(lines))

    # "wait_for_ready" re-sends "kernel_info_request" until the kernel answers, and consumes
    # only one of the replies, so the shell channel may still hold replies to those extra
    # requests. Skip anything that is not a reply to our own request.
    while True:
        reply = receive(client.get_shell_msg, "shell", cell_number)
        if reply["parent_header"].get("msg_id") == msg_id:
            break

    emit(cell_number, "status", reply["content"]["status"])
    for field, value in outputs.items():
        emit(cell_number, field, value)


def main(cells):
    manager, client = start_new_kernel(kernel_name="java", startup_timeout=STARTUP_TIMEOUT)
    try:
        for i, source in enumerate(cells):
            execute(client, i + 1, source)
    finally:
        client.stop_channels()
        manager.shutdown_kernel()


if __name__ == "__main__":
    main(sys.argv[1:])
