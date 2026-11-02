package org.dflib.jjava.distro;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The outputs of a sequence of notebook cells executed against a kernel, as captured by
 * "src/test/resources/kernel-driver.py".
 */
public class KernelRun {

    private static final String PREFIX = "@@JJAVA@@";

    private final List<CellOutput> cells;

    private KernelRun(List<CellOutput> cells) {
        this.cells = cells;
    }

    /**
     * Parses the driver transcript. Lines that do not belong to the driver protocol - kernel
     * logs, Jupyter warnings - are ignored.
     */
    static KernelRun parse(String[] sources, String transcript) {
        Map<Integer, Map<String, String>> byCell = new LinkedHashMap<>();
        for (String line : transcript.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith(PREFIX)) {
                continue;
            }

            String[] parts = trimmed.split("\\|", 4);
            if (parts.length != 4) {
                fail("Malformed driver output: " + trimmed);
            }

            int number = Integer.parseInt(parts[1]);
            String value = new String(Base64.getDecoder().decode(parts[3]), StandardCharsets.UTF_8);
            byCell.computeIfAbsent(number, n -> new LinkedHashMap<>()).put(parts[2], value);
        }

        List<CellOutput> cells = new ArrayList<>(sources.length);
        for (int i = 0; i < sources.length; i++) {
            Map<String, String> fields = byCell.get(i + 1);
            if (fields == null) {
                fail("Kernel produced no output for cell " + (i + 1) + ":\n" + transcript);
            }
            cells.add(new CellOutput(i + 1, sources[i], fields));
        }

        return new KernelRun(cells);
    }

    public List<CellOutput> cells() {
        return cells;
    }

    /**
     * Returns the output of a cell, with cells numbered from 1, the way Jupyter numbers them.
     */
    public CellOutput cell(int number) {
        if (number < 1 || number > cells.size()) {
            fail("No cell " + number + ", the run had " + cells.size() + " cell(s)");
        }
        return cells.get(number - 1);
    }

    /**
     * Asserts that every cell executed without an error.
     */
    public KernelRun assertNoErrors() {
        for (CellOutput cell : cells) {
            if (!cell.isOk()) {
                fail("Cell " + cell.number() + " failed:\n" + this);
            }
        }
        return this;
    }

    @Override
    public String toString() {
        return cells.stream().map(CellOutput::toString).collect(Collectors.joining());
    }
}
