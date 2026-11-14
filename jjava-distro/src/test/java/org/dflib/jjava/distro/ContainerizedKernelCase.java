package org.dflib.jjava.distro;

import org.junit.jupiter.api.BeforeAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.ExecConfig;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

public abstract class ContainerizedKernelCase {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContainerizedKernelCase.class);

    protected static final GenericContainer<?> container;
    protected static final String WORKING_DIRECTORY = "/test";
    protected static final String CONTAINER_KERNELSPEC = "/usr/share/jupyter/kernels/java";
    protected static final String CONTAINER_RESOURCES = WORKING_DIRECTORY + "/resources";
    protected static final String TEST_CLASSPATH = CONTAINER_RESOURCES + "/classes";

    private static final String BASE_IMAGE = String.format("eclipse-temurin:%s", Runtime.version().feature());
    private static final String FS_KERNELSPEC = "../kernelspec/java";
    private static final String FS_RESOURCES = "src/test/resources";
    private static final String KERNEL_DRIVER = CONTAINER_RESOURCES + "/kernel-driver.py";

    static {
        container = new GenericContainer<>(BASE_IMAGE)
                .withWorkingDirectory(WORKING_DIRECTORY)
                .withCopyToContainer(MountableFile.forHostPath(FS_KERNELSPEC), CONTAINER_KERNELSPEC)
                .withCopyToContainer(MountableFile.forHostPath(FS_RESOURCES), CONTAINER_RESOURCES)
                .withCommand("bash", "-c", getStartupCommand())
                .withLogConsumer(new Slf4jLogConsumer(LOGGER))
                .waitingFor(Wait.forSuccessfulCommand(getSuccessfulCommand()))
                .withStartupTimeout(Duration.ofMinutes(1));
        container.start();
    }

    @BeforeAll
    static void setUp() throws IOException, InterruptedException {
        String source = "$(find " + CONTAINER_RESOURCES + "/src -name '*.java')";
        Container.ExecResult compileResult = executeInContainer("javac -d " + TEST_CLASSPATH + " " + source);

        assertEquals("", compileResult.getStdout());
        assertEquals("", compileResult.getStderr());
    }

    protected static Container.ExecResult executeInContainer(String... commands) throws IOException, InterruptedException {
        List<String> wrappedCommands = new ArrayList<>();
        wrappedCommands.add("bash");
        wrappedCommands.add("-c");
        wrappedCommands.addAll(List.of(commands));
        return container.execInContainer(wrappedCommands.toArray(new String[]{}));
    }

    /**
     * Starts a fresh kernel and executes the given cells against it, one Jupyter "execute_request"
     * per cell. A cell may span multiple lines, and is evaluated by the kernel as a single unit,
     * exactly like a notebook cell.
     */
    protected static KernelRun executeInKernel(String... cells) throws IOException, InterruptedException {
        return executeInKernel(Collections.emptyMap(), cells);
    }

    /**
     * Same as {@link #executeInKernel(String...)}, with extra environment variables visible to the
     * kernel process.
     */
    protected static KernelRun executeInKernel(Map<String, String> env, String... cells) throws IOException, InterruptedException {

        List<String> command = new ArrayList<>();
        command.add(venvCommand("python"));
        command.add(KERNEL_DRIVER);
        command.addAll(Arrays.asList(cells));

        Container.ExecResult execResult = container.execInContainer(ExecConfig.builder()
                .envVars(env)
                .command(command.toArray(new String[]{}))
                .build()
        );

        LOGGER.info("env = {}", env);
        LOGGER.info("cells = {}", Arrays.asList(cells));
        LOGGER.debug("stdout = {}", execResult.getStdout());
        LOGGER.debug("stderr = {}", execResult.getStderr());

        assertEquals(0, execResult.getExitCode(),
                "Kernel driver failed:\n" + execResult.getStdout() + execResult.getStderr());

        KernelRun run = KernelRun.parse(cells, execResult.getStdout());
        LOGGER.info("run =\n{}", run);
        return run;
    }

    private static String getStartupCommand() {
        return String.join(" && ",
                "apt-get update",
                "apt-get install --no-install-recommends -y python3 python3-pip python3-venv curl",
                "python3 -m venv ./venv",
                venvCommand("pip install jupyter-client --progress-bar off"),
                "tail -f /dev/null"
        );
    }

    private static String getSuccessfulCommand() {
        return venvCommand("jupyter kernelspec list")
                + " | grep ' java ' && "
                + venvCommand("python") + " -c 'import jupyter_client'";
    }

    private static String venvCommand(String command) {
        return WORKING_DIRECTORY + "/venv/bin/" + command;
    }
}
