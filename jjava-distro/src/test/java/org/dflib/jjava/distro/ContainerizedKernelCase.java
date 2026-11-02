package org.dflib.jjava.distro;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.ExecConfig;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;

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
        // On Windows, Docker Desktop uses a non-default named pipe and requires Docker API >= 1.44.
        // docker-java (bundled in testcontainers 1.21.x) defaults to API version 1.32 and the wrong pipe.
        // Two fixes are applied when no DOCKER_HOST env var is already set:
        //   1. "api.version" system property  – the key docker-java reads for its apiVersion config
        //      (Docker Desktop 29+ requires minimum 1.44; docker-java's default is 1.32)
        //   2. "docker.host" in ~/.testcontainers.properties – testcontainers' strategy detection reads
        //      user properties, NOT Java system properties, so writing the file is required.
        //      Docker Desktop's Linux engine listens on "docker_engine_linux", not "docker_engine".
        if (System.getProperty("os.name", "").toLowerCase().contains("windows")
                && System.getenv("DOCKER_HOST") == null) {
            if (System.getProperty("api.version") == null) {
                System.setProperty("api.version", "1.44");
            }
            File tcPropsFile = new File(System.getProperty("user.home"), ".testcontainers.properties");
            try {
                Properties tcProps = new Properties();
                if (tcPropsFile.exists()) {
                    try (FileInputStream fis = new FileInputStream(tcPropsFile)) {
                        tcProps.load(fis);
                    }
                }
                if (!tcProps.containsKey("docker.host") && !tcProps.containsKey("tc.host")) {
                    tcProps.setProperty("docker.host", "npipe:////./pipe/docker_engine_linux");
                    try (FileOutputStream fos = new FileOutputStream(tcPropsFile)) {
                        tcProps.store(fos, "Auto-configured by jjava tests for Docker Desktop on Windows");
                    }
                    LOGGER.info("Configured docker.host in ~/.testcontainers.properties for Docker Desktop");
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to auto-configure ~/.testcontainers.properties: {}", e.getMessage());
            }
        }

        container = new GenericContainer<>(BASE_IMAGE)
                .withWorkingDirectory(WORKING_DIRECTORY)
                .withCopyToContainer(MountableFile.forHostPath(FS_KERNELSPEC), CONTAINER_KERNELSPEC)
                .withCopyToContainer(MountableFile.forHostPath(FS_RESOURCES), CONTAINER_RESOURCES)
                .withCommand("bash", "-c", getStartupCommand())
                .withLogConsumer(new Slf4jLogConsumer(LOGGER))
                .waitingFor(Wait.forSuccessfulCommand(getSuccessfulCommand()))
                .withStartupTimeout(Duration.ofMinutes(5));
        try {
            container.start();
        } catch (Exception e) {
            LOGGER.warn("Failed to start Docker container - container-based tests will be skipped: {}", e.getMessage());
        }
    }

    @BeforeAll
    static void setUp() throws IOException, InterruptedException {
        Assumptions.assumeTrue(container.isRunning(), "Skipping: Docker container is not running");
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
