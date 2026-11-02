package org.dflib.jjava.jupyter.kernel.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PathsHandlerTest {

    @Test
    public void testSpit() {
        assertEquals(List.of(), PathsHandler.split(""));
        assertEquals(List.of("a"), PathsHandler.split("a"));
        assertEquals(List.of("a", "b/c/d"), PathsHandler.split("a" + File.pathSeparator + "b/c/d"));
    }

    @Test
    public void testSpitAndResolveGlobs(@TempDir Path dir) throws IOException {

        Files.createFile(dir.resolve("a1.txt"));
        Files.createFile(dir.resolve("a2.txt"));
        Files.createFile(dir.resolve("b1.txt"));
        Files.createFile(dir.resolve("b2.txt"));
        Files.createFile(dir.resolve("c1.txt"));

        // GlobResolver requires "/" as the path separator (even on Windows)
        String baseGlob = dir.toString().replace(File.separator, "/") + "/";

        assertEquals(List.of(), PathsHandler.splitAndResolveGlobs(""));
        assertEquals(List.of(), PathsHandler.splitAndResolveGlobs(baseGlob + "a"));
        assertEquals(List.of(), PathsHandler.splitAndResolveGlobs(baseGlob + "a" + File.pathSeparator + "b/c/d"));

assertEquals(List.of(baseGlob + "a1.txt", baseGlob + "a2.txt"),
                PathsHandler.splitAndResolveGlobs(baseGlob + "a*").stream().map(p -> p.toAbsolutePath().toString().replace(File.separator, "/")).sorted().collect(Collectors.toList()));
        assertEquals(
                List.of(baseGlob + "a1.txt", baseGlob + "a2.txt", baseGlob + "b1.txt", baseGlob + "b2.txt"),
                PathsHandler.splitAndResolveGlobs(baseGlob + "a*" + File.pathSeparator + baseGlob + "b*").stream().map(p -> p.toAbsolutePath().toString().replace(File.separator, "/")).sorted().collect(Collectors.toList()));
    }
}
