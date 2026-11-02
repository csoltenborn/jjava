package org.dflib.jjava.distro;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class KernelExecutionIT extends ContainerizedKernelCase {

    /**
     * The variable and the later import must be in the same cell. Split across cells, this
     * scenario passes even with the bug present.
     */
    @Test
    public void variableSurvivesLaterImports() throws Exception {
        KernelRun run = executeInKernel(
                "%maven com.fasterxml.jackson.core:jackson-databind:2.21.2",
                String.join("\n",
                        "import com.fasterxml.jackson.databind.*;",
                        "var om = new ObjectMapper().findAndRegisterModules();",
                        "import com.fasterxml.jackson.databind.node.*; import com.fasterxml.jackson.databind.type.*;",
                        "om.getClass().getName()"
                )
        ).assertNoErrors();

        assertEquals("com.fasterxml.jackson.databind.ObjectMapper", run.cell(2).result());
    }

    @Test
    public void variableRedeclarationUsesLatestValue() throws Exception {
        KernelRun run = executeInKernel(
                "int v = 1;",
                "v",
                "float v = 2.4f;",
                "v"
        ).assertNoErrors();

        assertEquals("1", run.cell(2).result());
        assertEquals("2.4", run.cell(4).result());
    }

    @Test
    public void variableRedeclarationInSameCell() throws Exception {
        KernelRun run = executeInKernel(
                "var v = \"a\";\nv",
                "var v = \"b\";\nv"
        ).assertNoErrors();

        assertEquals("a", run.cell(1).result());
        assertEquals("b", run.cell(2).result());
    }
}
