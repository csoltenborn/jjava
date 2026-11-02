package org.dflib.jjava.distro;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class KernelStartupIT extends ContainerizedKernelCase {

    @Test
    public void startUp() throws Exception {
        KernelRun run = executeInKernel("1000d + 1").assertNoErrors();

        assertEquals("1001.0", run.cell(1).result());
    }

    @Test
    public void startUp_scriptRequiresClasspath() throws Exception {
        Map<String, String> env = Map.of(
                Env.JJAVA_CLASSPATH, TEST_CLASSPATH,
                Env.JJAVA_STARTUP_SCRIPT, "var obj = new org.dflib.jjava.Dummy()"
        );
        KernelRun run = executeInKernel(env, "\"hash = \" + obj.hashCode()").assertNoErrors();

        String result = run.cell(1).result();
        assertTrue(result.matches("hash = -?\\d+"), () -> "Unexpected result: " + result);
    }
}
