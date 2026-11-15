package org.dflib.jjava.kernel.execution;

import jdk.jshell.EvalException;
import jdk.jshell.JShell;
import jdk.jshell.JShellException;
import jdk.jshell.Snippet;
import jdk.jshell.SnippetEvent;
import jdk.jshell.SourceCodeAnalysis;
import jdk.jshell.spi.ExecutionControl;
import jdk.jshell.spi.ExecutionControlProvider;
import jdk.jshell.spi.ExecutionEnv;
import org.dflib.jjava.jupyter.kernel.BaseKernel;
import org.dflib.jjava.jupyter.telemetry.TelemetryCollector;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CodeEvaluator {
    private static final Pattern WHITESPACE_PREFIX = Pattern.compile("(?:^|\r?\n)(?<ws>\\s*).*$");
    private static final Pattern LAST_LINE = Pattern.compile("(?:^|\r?\n)(?<last>.*)$");

    private static final String NO_MAGIC_RETURN = "\"__NO_MAGIC_RETURN\"";
    private static final String INDENTATION = "  ";

    private static final Method SNIPPET_CLASS_NAME_METHOD;

    static {
        try {
            SNIPPET_CLASS_NAME_METHOD = Snippet.class.getDeclaredMethod("classFullName");
            SNIPPET_CLASS_NAME_METHOD.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Unable to access jdk.jshell.Snippet.classFullName() method.", e);
        }
    }

    private final String name;
    private final long timeoutDuration;
    private final TimeUnit timeoutUnit;
    private final JJavaLoaderDelegate loaderDelegate;
    private final JJavaExecutionControl execControl;
    private final ConcurrentMap<String, SnippetLocation> snippetLocations = new ConcurrentHashMap<>();

    public CodeEvaluator(String name, long timeoutDuration, TimeUnit timeoutUnit) {
        this.name = name;
        this.timeoutDuration = timeoutDuration;
        this.timeoutUnit = timeoutUnit;
        this.loaderDelegate = new JJavaLoaderDelegate();
        this.execControl = new JJavaExecutionControl(loaderDelegate, timeoutDuration, timeoutUnit);
    }

    public void startThreadTelemetryCollection(TelemetryCollector<?> collector) {
        execControl.startThreadTelemetryCollection(collector);
    }

    public void stopThreadTelemetryCollection() {
        execControl.stopThreadTelemetryCollection();
    }

    /**
     * Returns ExecutionControlProvider required by JShell. This links JShell execution engine to our code evaluator.
     */
    public ExecutionControlProvider getExecControlProvider() {
        return new SimpleExecControlProvider(name, execControl);
    }

    public Object eval(JShell shell, String code) {
        return eval(shell, code, BaseKernel.NO_EXECUTION_COUNT);
    }

    /**
     * Evaluates all complete snippets in {@code code} and records each snippet's execution count
     * and 0-based starting line offset within {@code code}.
     */
    public Object eval(JShell shell, String code, int executionCount) {

        SourceCodeAnalysis sca = shell.sourceCodeAnalysis();

        Object lastResult = null;
        String remaining = code;
        int lineOffset = 0;
        SourceCodeAnalysis.CompletionInfo info = sca.analyzeCompletion(remaining);

        while (info.completeness().isComplete()) {
            String source = info.source();
            String nextRemaining = info.remaining();
            int snippetLineOffset = lineOffset + countLeadingWhitespaceNewlines(source);

            lastResult = evalSingle(shell, source, executionCount, snippetLineOffset);

            int consumedLength = Math.max(0, remaining.length() - nextRemaining.length());
            lineOffset += countNewlines(remaining, consumedLength);
            remaining = nextRemaining;
            info = sca.analyzeCompletion(remaining);
        }

        if (info.completeness() != SourceCodeAnalysis.Completeness.EMPTY) {
            throw new IncompleteSourceException(info.remaining().trim());
        }

        return lastResult;
    }

    protected Object evalSingle(JShell shell, String code) {
        return evalSingle(shell, code, BaseKernel.NO_EXECUTION_COUNT, 0);
    }

    protected Object evalSingle(JShell shell, String code, int executionCount, int lineOffset) {

        List<SnippetEvent> events = shell.eval(code.strip());
        recordSnippetLocations(events, executionCount, lineOffset);

        Object result = null;

        // We iterate twice to make sure throwing an early exception doesn't leak the memory
        // and we `takeResult` everything.
        for (SnippetEvent event : events) {
            if (event.status() == Snippet.Status.OVERWRITTEN) {
                // if a new snippet changed some other definition, drop the older one
                dropSnippet(shell, event.snippet());
                continue;
            }

            String key = event.value();
            if (key == null) {
                continue;
            }

            Snippet.SubKind subKind = event.snippet().subKind();

            // Only executable snippets make their way through the machinery we have setup in the
            // JJavaExecutionControl. Declarations for example simply take their default value without
            // being executed.
            Object value = subKind.isExecutable()
                    ? execControl.takeResult(key)
                    : event.value();

            switch (subKind) {
                case VAR_VALUE_SUBKIND:
                case OTHER_EXPRESSION_SUBKIND:
                case TEMP_VAR_EXPRESSION_SUBKIND:
                    result = NO_MAGIC_RETURN.equals(value) ? null : value;
                    break;
                default:
                    result = null;
                    break;
            }
        }

        for (SnippetEvent event : events) {
            // If fresh snippet
            if (event.causeSnippet() == null) {
                JShellException e = event.exception();
                if (e != null) {

                    if (e instanceof EvalException) {
                        EvalException ee = (EvalException) e;
                        switch (ee.getExceptionClassName()) {
                            case JJavaExecutionControl.EXECUTION_TIMEOUT_NAME:
                                throw new EvaluationTimeoutException(timeoutDuration, timeoutUnit, code.trim());
                            case JJavaExecutionControl.EXECUTION_INTERRUPTED_NAME:
                                throw new EvaluationInterruptedException(code.trim());
                            default:
                                throw new RuntimeException(ee.getExceptionClassName() + ", " + e.getMessage(), e);
                        }
                    }

                    throw new RuntimeException(e);
                }

                // Undefined snippets are generally bad, unless we can still recover from them. E.g.,
                // "Unresolved dependencies" errors are recoverable when those dependencies are defined in the later
                // snippets.
                if (event.status() != Snippet.Status.RECOVERABLE_NOT_DEFINED && !event.status().isDefined()) {
                    throw new CompilationException(event);
                }
            }
        }

        return result;
    }

    /**
     * Try to clean up information linked to a code snippet and the snippet itself
     */
    private void dropSnippet(JShell shell, Snippet snippet) {
        shell.drop(snippet);
        // snippet.classFullName() returns name of a wrapper class created for a snippet
        String className = snippetClassName(snippet);
        // check that this class is not used by other snippets
        if (shell.snippets()
                .map(this::snippetClassName)
                .noneMatch(className::equals)) {
            execControl.unloadClass(className);
        }
    }

    private String snippetClassName(Snippet snippet) {
        try {
            return SNIPPET_CLASS_NAME_METHOD.invoke(snippet).toString();
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    private String computeIndentation(String partialStatement) {
        // Find the indentation of the last line
        Matcher m = WHITESPACE_PREFIX.matcher(partialStatement);
        String currentIndentation = m.find() ? m.group("ws") : "";

        m = LAST_LINE.matcher(partialStatement);
        if (!m.find())
            throw new Error("Pattern broken. Every string should have a last line.");

        // If a brace or paren was opened on the last line and not closed, indent some more.
        String lastLine = m.group("last");
        int newlyOpenedBraces = -1;
        int newlyOpenedParens = -1;
        for (int i = 0; i < lastLine.length(); i++) {
            switch (lastLine.charAt(i)) {
                case '}':
                    // Ignore closing if one has not been opened on this line yet
                    if (newlyOpenedBraces == -1) continue;
                    // Otherwise close an opened one from this line
                    newlyOpenedBraces--;
                    break;
                case ')':
                    // Same as for braces, but with the parens
                    if (newlyOpenedParens == -1) continue;
                    newlyOpenedParens--;
                    break;
                case '{':
                    // A brace was opened on this line!
                    // If the first then get out og the -1 special case with an extra addition
                    if (newlyOpenedBraces == -1) newlyOpenedBraces++;
                    newlyOpenedBraces++;
                    break;
                case '(':
                    if (newlyOpenedParens == -1) newlyOpenedParens++;
                    newlyOpenedParens++;
                    break;
            }
        }

        return newlyOpenedBraces > 0 || newlyOpenedParens > 0
                ? currentIndentation + INDENTATION
                : currentIndentation;
    }

    public String isComplete(SourceCodeAnalysis sourceAnalyzer, String code) {
        SourceCodeAnalysis.CompletionInfo info = sourceAnalyzer.analyzeCompletion(code);
        while (info.completeness().isComplete()) {
            info = sourceAnalyzer.analyzeCompletion(info.remaining());
        }

        switch (info.completeness()) {
            case UNKNOWN:
                // Unknown means "bad code" and the only way to see if is complete is to execute it.
                return BaseKernel.IS_COMPLETE_BAD;
            case COMPLETE:
            case COMPLETE_WITH_SEMI:
            case EMPTY:
                return BaseKernel.IS_COMPLETE_YES;
            case CONSIDERED_INCOMPLETE:
            case DEFINITELY_INCOMPLETE:
                // Compute the indent of the last line and match it
                return computeIndentation(info.remaining());
            default:
                // For completeness, return an "I don't know" if we somehow get down here
                return BaseKernel.IS_COMPLETE_MAYBE;
        }
    }

    /**
     * Returns the recorded execution location for a JShell snippet ID (without leading {@code #}),
     * or {@code null} if unknown.
     */
    public SnippetLocation getSnippetLocation(String snippetId) {
        return snippetId == null ? null : snippetLocations.get(snippetId);
    }

    private void recordSnippetLocations(List<SnippetEvent> events, int executionCount, int lineOffset) {
        for (SnippetEvent event : events) {
            // Only update on the primary event; JShell reuses snippet IDs on redefinition, so
            // intentionally do not remove entries when a snippet becomes OVERWRITTEN.
            if (event.causeSnippet() == null && event.status() != Snippet.Status.OVERWRITTEN) {
                String id = event.snippet().id();
                if (id != null) {
                    snippetLocations.put(id, new SnippetLocation(executionCount, lineOffset));
                }
            }
        }
    }

    private static int countLeadingWhitespaceNewlines(String source) {
        int i = 0;
        int len = source.length();
        while (i < len) {
            int cp = source.codePointAt(i);
            if (!Character.isWhitespace(cp)) {
                break;
            }
            i += Character.charCount(cp);
        }
        return countNewlines(source, i);
    }

    private static int countNewlines(String s, int end) {
        int count = 0;
        for (int i = 0; i < end; i++) {
            char c = s.charAt(i);
            if (c == '\n') {
                count++;
            } else if (c == '\r') {
                count++;
                if (i + 1 < end && s.charAt(i + 1) == '\n') {
                    i++;
                }
            }
        }
        return count;
    }

    public void interrupt() {
        execControl.interrupt();
    }

    public ClassLoader getClassLoader() {
        return loaderDelegate.getClassLoader();
    }

    /**
     * Captures the notebook execution context and 0-based starting line offset of an evaluated snippet.
     */
    public static final class SnippetLocation {
        private final int executionCount;
        private final int lineOffset;

        public SnippetLocation(int executionCount, int lineOffset) {
            this.executionCount = executionCount;
            this.lineOffset = lineOffset;
        }

        public int getExecutionCount() {
            return executionCount;
        }

        public int getLineOffset() {
            return lineOffset;
        }
    }

    static final class SimpleExecControlProvider implements ExecutionControlProvider {

        private final String name;
        private final ExecutionControl control;

        public SimpleExecControlProvider(String name, ExecutionControl control) {
            this.name = name;
            this.control = control;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public ExecutionControl generate(ExecutionEnv env, Map<String, String> parameters) {
            return control;
        }
    }
}
