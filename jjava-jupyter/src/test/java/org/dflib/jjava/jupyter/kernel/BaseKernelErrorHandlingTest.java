package org.dflib.jjava.jupyter.kernel;

import org.dflib.jjava.jupyter.channels.ShellReplyEnvironment;
import org.dflib.jjava.jupyter.kernel.comm.CommManager;
import org.dflib.jjava.jupyter.kernel.display.DisplayData;
import org.dflib.jjava.jupyter.kernel.display.Renderer;
import org.dflib.jjava.jupyter.kernel.magic.MagicTranspiler;
import org.dflib.jjava.jupyter.kernel.magic.MagicsRegistry;
import org.dflib.jjava.jupyter.kernel.magic.MagicsResolver;
import org.dflib.jjava.jupyter.kernel.util.StringStyler;
import org.dflib.jjava.jupyter.messages.Message;
import org.dflib.jjava.jupyter.messages.MessageType;
import org.dflib.jjava.jupyter.messages.publish.PublishError;
import org.dflib.jjava.jupyter.messages.reply.ErrorReply;
import org.dflib.jjava.jupyter.messages.request.ExecuteRequest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The execute handler must convert anything thrown by evaluation — Errors
 * included, not just Exceptions — into a published error and an error reply.
 * An escaping Throwable would kill the shell channel loop and permanently
 * hang the kernel (see gh-132).
 */
public class BaseKernelErrorHandlingTest {

    @Test
    public void errorFromEvalProducesErrorReply() {
        assertErrorHandled(new AssertionError("boom"), "AssertionError");
    }

    @Test
    public void linkageErrorFromEvalProducesErrorReply() {
        assertErrorHandled(new NoClassDefFoundError("com/example/Gone"), "NoClassDefFoundError");
    }

    @Test
    public void exceptionFromEvalProducesErrorReply() {
        assertErrorHandled(new RuntimeException("boom"), "RuntimeException");
    }

    private void assertErrorHandled(Throwable thrown, String expectedName) {
        ThrowingKernel kernel = new ThrowingKernel(thrown);
        CapturingEnv env = new CapturingEnv();
        Message<ExecuteRequest> message = new Message<>(null, MessageType.EXECUTE_REQUEST,
                new ExecuteRequest("1 + 1", false, false, Collections.emptyMap(), false, false));

        kernel.handleExecuteRequest(env, message);
        env.resolveDeferrals();

        List<Object> published = contentsOf(env.published);
        List<Object> replied = contentsOf(env.replied);

        PublishError publishedError = (PublishError) published.stream()
                .filter(c -> c instanceof PublishError)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no error published, got: " + published));
        assertEquals(expectedName, publishedError.getErrorName());

        ErrorReply reply = (ErrorReply) replied.stream()
                .filter(c -> c instanceof ErrorReply)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no error reply sent, got: " + replied));
        assertEquals(expectedName, reply.getErrorName());
        assertTrue(reply.getErrorMessage().contains("boom") || reply.getErrorMessage().contains("Gone"));
    }

    private static List<Object> contentsOf(List<Message<?>> messages) {
        List<Object> contents = new ArrayList<>();
        for (Message<?> message : messages) {
            contents.add(message.getContent());
        }
        return contents;
    }

    private static class ThrowingKernel extends BaseKernel {

        private final Throwable toThrow;

        ThrowingKernel(Throwable toThrow) {
            super("test", "0",
                    new LanguageInfo.Builder("test").build(),
                    Collections.emptyList(),
                    null,
                    new JupyterIO(StandardCharsets.UTF_8),
                    new CommManager(),
                    new Renderer(),
                    new MagicsResolver("%", "%%", new MagicTranspiler()),
                    new MagicsRegistry(),
                    false,
                    new StringStyler.Builder().build());
            this.toThrow = toThrow;
        }

        @Override
        protected Object doEval(String source) {
            if (toThrow instanceof RuntimeException) {
                throw (RuntimeException) toThrow;
            }
            if (toThrow instanceof Error) {
                throw (Error) toThrow;
            }
            throw new IllegalStateException(toThrow);
        }

        @Override
        public DisplayData inspect(String code, int at, boolean extraDetail) {
            return null;
        }

        @Override
        public ReplacementOptions complete(String code, int at) {
            return null;
        }

        @Override
        public String isComplete(String code) {
            return IS_COMPLETE_MAYBE;
        }
    }

    private static class CapturingEnv extends ShellReplyEnvironment {

        final List<Message<?>> published = new ArrayList<>();
        final List<Message<?>> replied = new ArrayList<>();

        CapturingEnv() {
            super(null, null, null, null);
        }

        @Override
        public void publish(Message<?> msg) {
            published.add(msg);
        }

        @Override
        public void reply(Message<?> msg) {
            replied.add(msg);
        }

        @Override
        public ShellReplyEnvironment defer() {
            // captures send immediately, so defer-then-send collapses to send
            return this;
        }
    }
}
