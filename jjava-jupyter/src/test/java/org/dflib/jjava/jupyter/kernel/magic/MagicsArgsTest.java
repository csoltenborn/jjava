package org.dflib.jjava.jupyter.kernel.magic;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class MagicsArgsTest {

    @ParameterizedTest(name = "{index}: \"{0}\" with \"{1}\"")
    @MethodSource
    public void values(MagicsArgs schema, String args, Map<String, List<String>> expected) {
        assertParsed(schema, args, expected);
    }

    public static Stream<Arguments> values() {
        return Stream.of(
                Arguments.of(
                        args(b -> b.required("a")),
                        "value-a",
                        Map.of("a", List.of("value-a"))
                ),
                Arguments.of(
                        args(b -> b.required("a").optional("b")),
                        "value-a",
                        Map.of("a", List.of("value-a"), "b", List.of())
                ),
                Arguments.of(
                        args(b -> b.required("a").optional("b")),
                        "value-a value-b",
                        Map.of(
                                "a", List.of("value-a"),
                                "b", List.of("value-b")
                        )
                ),
                Arguments.of(
                        args(b -> b.required("a").optional("b").varargs("c")),
                        "value-a value-b",
                        Map.of(
                                "a", List.of("value-a"),
                                "b", List.of("value-b"),
                                "c", List.of()
                        )
                ),
                Arguments.of(args(b -> b.required("a").optional("b").varargs("c")),
                        "value-a value-b value-c",
                        Map.of(
                                "a", List.of("value-a"),
                                "b", List.of("value-b"),
                                "c", List.of("value-c")
                        )
                ),
                Arguments.of(
                        args(b -> b.required("a").optional("b").varargs("c")),
                        "value-a value-b value-c-1 value-c-2",
                        Map.of(
                                "a", List.of("value-a"),
                                "b", List.of("value-b"),
                                "c", List.of("value-c-1", "value-c-2")
                        )
                ),
                Arguments.of(
                        args(b -> b.required("a").required("b").varargs("c")),
                        "value-a value-b value-c-1 value-c-2",
                        Map.of(
                                "a", List.of("value-a"),
                                "b", List.of("value-b"),
                                "c", List.of("value-c-1", "value-c-2")
                        )
                ),
                Arguments.of(
                        args(b -> b.optional("a")),
                        "",
                        Map.of("a", List.of())
                ),
                Arguments.of(
                        args(b -> b.optional("a").varargs("b")),
                        "",
                        Map.of(
                                "a", List.of(),
                                "b", List.of()
                        )
                ),
                Arguments.of(
                        args(b -> b.optional("a").varargs("b")),
                        "value-a",
                        Map.of(
                                "a", List.of("value-a"),
                                "b", List.of()
                        )
                ),
                Arguments.of(
                        args(b -> b.varargs("a")),
                        "",
                        Map.of("a", List.of())
                ),
                Arguments.of(
                        args(b -> b.varargs("a")),
                        "value-a",
                        Map.of("a", List.of("value-a"))
                ),
                Arguments.of(
                        args(b -> b.required("a").optional("a")),
                        "value-a extra-a",
                        Map.of("a", List.of("value-a", "extra-a"))
                ),
                Arguments.of(
                        args(b -> b.required("a").optional("a")),
                        "value-a",
                        Map.of("a", List.of("value-a"))
                ),
                Arguments.of(
                        args(b -> b.required("a").varargs("a")),
                        "value-a extra-a extra-a-2",
                        Map.of("a", List.of("value-a", "extra-a", "extra-a-2"))
                )
        );
    }

    @ParameterizedTest(name = "{index}: \"{0}\" with \"{1}\"")
    @MethodSource
    public void flags(MagicsArgs schema, String args, Map<String, List<String>> expected) {
        assertParsed(schema, args, expected);
    }

    public static Stream<Arguments> flags() {
        return Stream.of(
                Arguments.of(
                        args(b -> {}),
                        "-f",
                        Map.of("f", List.of(""))
                ),
                Arguments.of(
                        args(b -> {}),
                        "-fff",
                        Map.of("f", List.of("", "", ""))
                ),
                Arguments.of(
                        args(b -> {}),
                        "-fg -g",
                        Map.of("f", List.of(""), "g", List.of("", ""))
                ),
                Arguments.of(
                        args(b -> b.flag("test", 'f')),
                        "",
                        Map.of("test", List.of())
                ),
                Arguments.of(
                        args(b -> b.flag("verbose", 'v', "true")),
                        "-v",
                        Map.of("verbose", List.of("true"))
                )
        );
    }

    @ParameterizedTest(name = "{index}: \"{0}\" with \"{1}\"")
    @MethodSource
    public void keywords(MagicsArgs schema, String args, Map<String, List<String>> expected) {
        assertParsed(schema, args, expected);
    }

    public static Stream<Arguments> keywords() {
        return Stream.of(
                Arguments.of(
                        args(b -> {}),
                        "--f=10",
                        Map.of("f", List.of("10"))
                ),
                Arguments.of(
                        args(b -> {}),
                        "--f=10 --f=11",
                        Map.of("f", List.of("10", "11"))
                ),
                Arguments.of(
                        args(b -> {}),
                        "--f 10 --f=11 --f 12",
                        Map.of("f", List.of("10", "11", "12"))
                ),
                Arguments.of(
                        args(b -> b.keyword("test")),
                        "--test=10 --test 11 --test=12",
                        Map.of("test", List.of("10", "11", "12"))
                ),
                Arguments.of(
                        args(b -> b.keyword("test", MagicsArgs.KeywordSpec.REPLACE)),
                        "--test=10 --test 11 --test=12",
                        Map.of("test", List.of("12"))
                ),
                Arguments.of(
                        args(b -> b.keyword("test")),
                        "",
                        Map.of("test", List.of())
                )
        );
    }

    @ParameterizedTest(name = "{index}: \"{0}\" with \"{1}\"")
    @MethodSource
    public void flagsAndKeyWords(MagicsArgs schema, String args, Map<String, List<String>> expected) {
        assertParsed(schema, args, expected);
    }

    public static Stream<Arguments> flagsAndKeyWords() {
        return Stream.of(
                Arguments.of(
                        args(b -> b.flag("log-level", 'v', "100").keyword("log-level")),
                        "-v --log-level=200 --log-level 300",
                        Map.of("log-level", List.of("100", "200", "300"))
                )
        );
    }

    @ParameterizedTest(name = "{index}: \"{0}\" with \"{1}\"")
    @MethodSource
    public void positionalsAndFlagsAndKeywords(MagicsArgs schema, String args,
                                               Map<String, List<String>> expected) {
        assertParsed(schema, args, expected);
    }

    public static Stream<Arguments> positionalsAndFlagsAndKeywords() {
        return Stream.of(
                Arguments.of(
                        args(b -> b.required("a")
                                .optional("b")
                                .flag("log-level", 'v', "100")
                                .keyword("log-level")),
                        "-v value-a --log-level=200 value-b --log-level 300",
                        Map.of(
                                "log-level", List.of("100", "200", "300"),
                                "a", List.of("value-a"),
                                "b", List.of("value-b")
                        ))
        );
    }

    @ParameterizedTest(name = "{index}: \"{0}\" with \"{1}\"")
    @MethodSource
    public void strange(MagicsArgs schema, String args, Map<String, List<String>> expected) {
        assertParsed(schema, args, expected);
    }

    public static Stream<Arguments> strange() {
        return Stream.of(
                Arguments.of(
                        args(b -> b.keyword("a")),
                        "\"--a=value with spaces\"",
                        Map.of("a", List.of("value with spaces"))
                ),
                Arguments.of(
                        args(b -> b.keyword("a")),
                        "--a=\"value with spaces\"",
                        Map.of("a", List.of("value with spaces"))
                ),
                Arguments.of(
                        args(b -> b.keyword("a")),
                        "--a \"value with spaces\"",
                        Map.of("a", List.of("value with spaces"))
                )
        );
    }

    @ParameterizedTest(name = "{index}: \"{0}\" with \"{1}\"")
    @MethodSource
    public void any_throws(MagicsArgs schema, String args) {
        List<String> rawArgs = MagicsResolver.split(args);

        assertThrows(MagicArgsParseException.class, () -> schema.parse(rawArgs));
    }

    public static Stream<Arguments> any_throws() {
        return Stream.of(
                Arguments.of(
                        args(b -> b.required("a")),
                        ""
                ),
                Arguments.of(
                        args(b -> b.required("a")),
                        "value-a extra-a"
                ),
                Arguments.of(
                        args(b -> b.optional("a")),
                        "value-a extra-a"
                ),
                Arguments.of(
                        args(b -> b.onlyKnownKeywords()),
                        "--unknown=val"
                ),
                Arguments.of(
                        args(b -> b.onlyKnownKeywords()),
                        "--unknown val"
                ),
                Arguments.of(
                        args(b -> b.onlyKnownFlags()),
                        "-idk"
                ),
                Arguments.of(
                        args(b -> b.flag("test", 'i').onlyKnownFlags()),
                        "-idk"
                ),
                Arguments.of(
                        args(b -> b.keyword("a", MagicsArgs.KeywordSpec.ONCE)),
                        "--a a --a not-ok..."
                )
        );
    }

    private static void assertParsed(MagicsArgs schema, String args, Map<String, List<String>> expected) {
        List<String> rawArgs = MagicsResolver.split(args);
        Map<String, List<String>> actual = assertDoesNotThrow(() -> schema.parse(rawArgs));

        assertEquals(expected, actual);
    }

    private static MagicsArgs args(Consumer<MagicsArgs.Builder> config) {
        MagicsArgs.Builder builder = MagicsArgs.builder();
        config.accept(builder);
        return builder.build();
    }
}
