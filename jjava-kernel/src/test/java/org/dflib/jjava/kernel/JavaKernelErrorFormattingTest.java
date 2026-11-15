package org.dflib.jjava.kernel;

import jdk.jshell.DeclarationSnippet;
import jdk.jshell.EvalException;
import jdk.jshell.JShellException;
import jdk.jshell.UnresolvedReferenceException;
import org.dflib.jjava.jupyter.channels.ShellReplyEnvironment;
import org.dflib.jjava.jupyter.kernel.BaseKernel;
import org.dflib.jjava.jupyter.messages.Message;
import org.dflib.jjava.jupyter.messages.MessageType;
import org.dflib.jjava.jupyter.messages.publish.PublishError;
import org.dflib.jjava.jupyter.messages.request.ExecuteRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JavaKernelErrorFormattingTest {

    private static final Pattern ANSI_ESCAPE = Pattern.compile("\u001B\\[[;\\d]*m");
    private static JavaKernel kernel;

    @BeforeAll
    static void startKernel() {
        kernel = JavaKernel.builder()
                .name("TestKernel")
                .extensionsEnabled(false)
                .build();
    }

    @AfterAll
    static void shutdownKernel() {
        if (kernel != null) {
            kernel.onShutdown(false);
        }
    }

    private static List<String> stripAnsi(List<String> lines) {
        return lines.stream()
                .map(line -> ANSI_ESCAPE.matcher(line).replaceAll(""))
                .collect(Collectors.toList());
    }

    private static EvalException newEvalException(
            String message,
            String exceptionClass,
            StackTraceElement[] stackTrace,
            JShellException cause) throws Exception {
        Constructor<EvalException> ctor = EvalException.class.getDeclaredConstructor(
                String.class, String.class, StackTraceElement[].class, JShellException.class);
        ctor.setAccessible(true);
        return ctor.newInstance(message, exceptionClass, stackTrace, cause);
    }

    private static UnresolvedReferenceException newUnresolvedReferenceException(
            DeclarationSnippet snippet,
            StackTraceElement[] stackTrace) throws Exception {
        Constructor<UnresolvedReferenceException> ctor = UnresolvedReferenceException.class.getDeclaredConstructor(
                DeclarationSnippet.class, StackTraceElement[].class);
        ctor.setAccessible(true);
        return ctor.newInstance(snippet, stackTrace);
    }

    @Test
    public void unwrapsRuntimeExceptionWrappingEvalException() {
        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> kernel.getEvaluator().eval(
                        kernel.getJShell(),
                        "void failHere() { throw new IllegalArgumentException(\"bad input\"); }\nfailHere();"
                )
        );
        assertInstanceOf(EvalException.class, thrown.getCause());

        List<String> plain = stripAnsi(kernel.formatError(thrown));

        assertFalse(plain.isEmpty());
        assertEquals("java.lang.IllegalArgumentException: bad input", plain.get(0));
        assertTrue(
                plain.stream().noneMatch(l -> l.contains("java.lang.RuntimeException")),
                "Internal RuntimeException wrapper must not appear in formatted trace: " + plain
        );
        assertTrue(
                plain.stream().noneMatch(l -> l.contains("jdk.jshell.EvalException")),
                "EvalException class name must be replaced with real exception class: " + plain
        );
        assertTrue(
                plain.stream().anyMatch(l -> l.startsWith("\tat ") && l.contains("failHere")),
                "Stack frames must be tab-indented and include user method frame: " + plain
        );
    }

    @Test
    public void omitsNullMessageSuffix() {
        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> kernel.getEvaluator().eval(
                        kernel.getJShell(),
                        "void throwNpe() { throw new NullPointerException(); }\nthrowNpe();"
                )
        );

        List<String> plain = stripAnsi(kernel.formatError(thrown));

        assertEquals("java.lang.NullPointerException", plain.get(0));
        assertTrue(
                plain.stream().noneMatch(l -> l.contains(": null")),
                "Null exception message must not render ': null': " + plain
        );
    }

    @Test
    public void preservesExplicitStringNullMessage() {
        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> kernel.getEvaluator().eval(
                        kernel.getJShell(),
                        "throw new IllegalArgumentException(\"null\");"
                )
        );

        List<String> plain = stripAnsi(kernel.formatError(thrown));

        assertEquals("java.lang.IllegalArgumentException: null", plain.get(0));
    }

    @Test
    public void formatsMultiLevelEvalExceptionCauseChain() {
        String code = String.join("\n",
                "void rootFailure() {",
                "    throw new NullPointerException();",
                "}",
                "void middleFailure() {",
                "    try {",
                "        rootFailure();",
                "    } catch (RuntimeException ex) {",
                "        ArithmeticException ae = new ArithmeticException(\"div by zero\");",
                "        ae.initCause(ex);",
                "        throw ae;",
                "    }",
                "}",
                "void outerFailure() {",
                "    try {",
                "        middleFailure();",
                "    } catch (RuntimeException ex) {",
                "        throw new IllegalStateException(\"outer failed\", ex);",
                "    }",
                "}",
                "outerFailure();"
        );
        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> kernel.getEvaluator().eval(kernel.getJShell(), code)
        );
        EvalException evalEx = assertInstanceOf(EvalException.class, thrown.getCause());

        List<String> plain = stripAnsi(kernel.formatError(evalEx));

        assertEquals("java.lang.IllegalStateException: outer failed", plain.get(0));
        assertTrue(
                plain.stream().anyMatch(l -> l.equals("Caused by: java.lang.ArithmeticException: div by zero")),
                "Middle cause must render with message: " + plain
        );
        assertTrue(
                plain.stream().anyMatch(l -> l.equals("Caused by: java.lang.NullPointerException")),
                "Root cause with null message must omit ': null': " + plain
        );
        assertTrue(
                plain.stream().anyMatch(l -> l.startsWith("\tat ") && l.contains("rootFailure")),
                "Root cause stack frames must be included: " + plain
        );
    }

    @Test
    public void formatsUnresolvedReferenceCauseWithoutClassCastException() {
        // Defining a method with a missing symbol yields a recoverable snippet; invoking it throws
        // SPIResolutionException, which JShell translates to UnresolvedReferenceException.
        kernel.getEvaluator().eval(kernel.getJShell(), "void usesMissingSymbol() { undefinedHelper(); }");
        String code = String.join("\n",
                "try {",
                "    usesMissingSymbol();",
                "} catch (Throwable t) {",
                "    throw new IllegalStateException(\"wrapper\", t);",
                "}"
        );
        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> kernel.getEvaluator().eval(kernel.getJShell(), code)
        );

        List<String> plain = stripAnsi(kernel.formatError(thrown));

        assertEquals("java.lang.IllegalStateException: wrapper", plain.get(0));
        assertTrue(
                plain.stream().anyMatch(l -> l.startsWith("Caused by: jdk.jshell.UnresolvedReferenceException")),
                "Non-EvalException JShell cause must be formatted instead of throwing ClassCastException: " + plain
        );
    }

    @Test
    public void formatsNestedNonEvalExceptionThrowableCausesAndCircularReferences() throws Exception {
        StackTraceElement frame = new StackTraceElement("Example", "run", "Example.java", 10);
        IllegalArgumentException leaf = new IllegalArgumentException();
        IllegalStateException middle = new IllegalStateException("middle", leaf);
        UnresolvedReferenceException unresolved = newUnresolvedReferenceException(null, new StackTraceElement[]{frame});
        unresolved.initCause(middle);
        EvalException evalWithCustomCause = newEvalException(
                "top",
                "com.example.TopException",
                new StackTraceElement[]{frame},
                unresolved);

        List<String> plain = stripAnsi(kernel.formatError(evalWithCustomCause));

        assertEquals("com.example.TopException: top", plain.get(0));
        assertTrue(
                plain.stream().anyMatch(l -> l.startsWith("Caused by: jdk.jshell.UnresolvedReferenceException")),
                "Missing UnresolvedReferenceException cause: " + plain
        );
        assertTrue(plain.contains("Caused by: java.lang.IllegalStateException: middle"), "Missing middle cause: " + plain);
        assertTrue(plain.contains("Caused by: java.lang.IllegalArgumentException"), "Missing leaf cause: " + plain);

        // Verify cycle detection when a custom Throwable overrides getCause() with a loop
        class CyclicThrowable extends Throwable {
            Throwable next;

            CyclicThrowable(String message) {
                super(message);
            }

            @Override
            public synchronized Throwable getCause() {
                return next;
            }
        }
        CyclicThrowable c1 = new CyclicThrowable("first");
        CyclicThrowable c2 = new CyclicThrowable("second");
        c1.next = c2;
        c2.next = c1;

        UnresolvedReferenceException cyclicBridge = newUnresolvedReferenceException(null, new StackTraceElement[]{frame});
        cyclicBridge.initCause(c1);
        EvalException cyclicEval = newEvalException(
                "outer",
                "com.example.OuterException",
                new StackTraceElement[]{frame},
                cyclicBridge);

        List<String> cyclicPlain = stripAnsi(kernel.formatError(cyclicEval));
        assertTrue(
                cyclicPlain.stream().anyMatch(l -> l.contains("[CIRCULAR REFERENCE:")),
                "Circular cause chain must terminate safely: " + cyclicPlain
        );
    }

    @Test
    public void doesNotUnwrapPlainRuntimeExceptionWithoutEvalExceptionCause() {
        RuntimeException internalErr = new RuntimeException(
                "kernel internal error",
                new IllegalStateException("root cause")
        );

        List<String> plain = stripAnsi(kernel.formatError(internalErr));

        assertFalse(plain.isEmpty());
        assertTrue(
                plain.stream().anyMatch(l -> l.contains("kernel internal error")),
                "Fallback formatting should preserve the internal error message: " + plain
        );
        assertTrue(
                plain.stream().anyMatch(l -> l.contains("root cause")),
                "Fallback formatting should preserve the cause message: " + plain
        );
    }

    @Test
    public void formatsJshellFramesWithExecutionCountAndOmitsLeadingDot() throws Exception {
        JavaKernel isolated = JavaKernel.builder()
                .name("IsolatedErrorFormattingKernel")
                .extensionsEnabled(false)
                .build();
        try {
            assertEquals(List.of(), executeCell(isolated,
                    "class HelperClass {\n"
                    + "    static int divide(int x) {\n"
                    + "        return 10 / x;\n"
                    + "    }\n"
                    + "}"));
            assertEquals(List.of(), executeCell(isolated,
                    "\nint compute(int x) {\n"
                    + "    return HelperClass.divide(x);\n"
                    + "}"));

            List<String> traceback = executeCell(isolated, "/* header */\n\ncompute(0);");
            assertFalse(traceback.isEmpty(), "Expected execution error traceback");
            assertEquals("java.lang.ArithmeticException: / by zero", traceback.get(0));
            assertTrue(traceback.contains("\tat HelperClass.divide([1]:3)"), "Missing class frame: " + traceback);
            assertTrue(traceback.contains("\tat compute([2]:3)"), "Missing top-level method frame: " + traceback);
            assertTrue(traceback.contains("\tat ([3]:3)"), "Missing top-level frame: " + traceback);
            assertTrue(
                    traceback.stream().noneMatch(l -> l.contains("at .(") || l.contains("at .compute(")),
                    "Leading dot must be omitted for top-level JShell frames: " + traceback
            );
        } finally {
            isolated.onShutdown(false);
        }
    }

    @Test
    public void tracksLineOffsetsAcrossMultipleStatementsInSameCell() throws Exception {
        JavaKernel isolated = JavaKernel.builder()
                .name("MultiStmtLineKernel")
                .extensionsEnabled(false)
                .build();
        try {
            List<String> traceback = executeCell(isolated,
                    "int first = 1;\n"
                    + "\n"
                    + "void stepTwo() {\n"
                    + "    int local = 2;\n"
                    + "    throw new IllegalStateException(\"multi-stmt\");\n"
                    + "}\n"
                    + "\n"
                    + "stepTwo();");
            assertFalse(traceback.isEmpty());
            assertTrue(traceback.contains("\tat stepTwo([1]:5)"), "Expected line 5 inside method: " + traceback);
            assertTrue(traceback.contains("\tat ([1]:8)"), "Expected line 8 for caller statement: " + traceback);
        } finally {
            isolated.onShutdown(false);
        }
    }

    @Test
    public void updatesLocationWhenMethodRedefinedReusingSnippetId() throws Exception {
        JavaKernel isolated = JavaKernel.builder()
                .name("RedefineKernel")
                .extensionsEnabled(false)
                .build();
        try {
            assertEquals(List.of(), executeCell(isolated,
                    "int redefSum(int x, int y) {\n"
                    + "    return x + y;\n"
                    + "}"));
            assertEquals(List.of(), executeCell(isolated, "redefSum(1, 2);"));
            assertEquals(List.of(), executeCell(isolated,
                    "int redefSum(int x, int y) {\n"
                    + "    return x / 0;\n"
                    + "}"));

            List<String> traceback = executeCell(isolated, "\nredefSum(1, 2);");
            assertTrue(
                    traceback.contains("\tat redefSum([3]:2)"),
                    "Redefined method reusing JShell snippet ID must point to latest cell [3]: " + traceback
            );
            assertTrue(traceback.contains("\tat ([4]:2)"), "Expected caller frame ([4]:2): " + traceback);
        } finally {
            isolated.onShutdown(false);
        }
    }

    @Test
    public void fallsBackToSnippetIdWithoutExecutionCountAndOmitsLeadingDot() throws Exception {
        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> kernel.getEvaluator().eval(
                        kernel.getJShell(),
                        "void directFail() {\n"
                        + "    throw new IllegalArgumentException(\"x\");\n"
                        + "}\n"
                        + "directFail();"
                )
        );

        List<String> plain = stripAnsi(kernel.formatError(thrown));
        assertTrue(
                plain.stream().anyMatch(l -> l.matches("\tat directFail\\(#\\d+:2\\)")),
                "Expected method frame without leading dot using #id fallback: " + plain
        );
        assertTrue(
                plain.stream().anyMatch(l -> l.matches("\tat \\(#\\d+:4\\)")),
                "Expected top-level frame without leading dot using #id fallback: " + plain
        );

        EvalException synthetic = newEvalException(
                "boom",
                "java.lang.RuntimeException",
                new StackTraceElement[]{
                        new StackTraceElement("", "", "#99", 5),
                        new StackTraceElement("", "topFn", "#98", 2),
                        new StackTraceElement("Pkg.Clz", "m", "#97", 4),
                        new StackTraceElement("java.lang.String", "valueOf", "String.java", 10)
                },
                null);
        List<String> syntheticPlain = stripAnsi(kernel.formatError(synthetic));
        assertEquals("\tat (#99:5)", syntheticPlain.get(1));
        assertEquals("\tat topFn(#98:2)", syntheticPlain.get(2));
        assertEquals("\tat Pkg.Clz.m(#97:4)", syntheticPlain.get(3));
        assertEquals("\tat java.lang.String.valueOf(String.java:10)", syntheticPlain.get(4));
    }

    private static List<String> executeCell(JavaKernel targetKernel, String code) throws Exception {
        CapturingEnv env = new CapturingEnv();
        Method handleExecuteRequest = BaseKernel.class.getDeclaredMethod(
                "handleExecuteRequest", ShellReplyEnvironment.class, Message.class);
        handleExecuteRequest.setAccessible(true);
        Message<ExecuteRequest> message = new Message<>(
                null,
                MessageType.EXECUTE_REQUEST,
                new ExecuteRequest(code, false, false, Collections.emptyMap(), false, false));
        handleExecuteRequest.invoke(targetKernel, env, message);
        env.resolveDeferrals();

        for (Message<?> msg : env.published) {
            if (msg.getContent() instanceof PublishError) {
                return stripAnsi(((PublishError) msg.getContent()).getStacktrace());
            }
        }
        return List.of();
    }

    private static final class CapturingEnv extends ShellReplyEnvironment {
        final List<Message<?>> published = new ArrayList<>();

        CapturingEnv() {
            super(null, null, null, null);
        }

        @Override
        public void publish(Message<?> msg) {
            published.add(msg);
        }

        @Override
        public void reply(Message<?> msg) {
        }

        @Override
        public ShellReplyEnvironment defer() {
            return this;
        }
    }
}
