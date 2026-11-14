package org.dflib.jjava.jupyter.channels;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class JupyterSocketTest {

    @Test
    public void formatAddress_tcp() {
        assertEquals("tcp://127.0.0.1:54321", JupyterSocket.formatAddress("tcp", "127.0.0.1", 54321));
    }

    @Test
    public void formatAddress_ipc() {
        // Jupyter convention: "ip" is a base path, channel sockets are "{ip}-{port}"
        assertEquals("ipc:///tmp/kernel-abc-1", JupyterSocket.formatAddress("ipc", "/tmp/kernel-abc", 1));
    }
}
