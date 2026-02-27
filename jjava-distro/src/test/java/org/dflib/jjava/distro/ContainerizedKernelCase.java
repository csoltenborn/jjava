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
import java.util.Base64;
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
    static void compileSources() throws IOException, InterruptedException {
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

    protected static Container.ExecResult executeInKernel(String snippet) throws IOException, InterruptedException {
        return executeInKernel(snippet, Collections.emptyMap());
    }

    protected static Container.ExecResult executeInKernel(String snippet, Map<String, String> env) throws IOException, InterruptedException {
        String snippet64 = Base64.getEncoder().encodeToString(snippet.getBytes());
        String jupyterCommand = venvCommand("jupyter console --kernel=java --simple-prompt");
        String[] containerCommand = new String[]{"bash", "-c", "echo \"" + snippet64 + "\" | base64 -d | " + jupyterCommand};
        Container.ExecResult execResult = container.execInContainer(ExecConfig.builder()
                .envVars(env)
                .command(containerCommand)
                .build()
        );

        LOGGER.info("env = {}", env);
        LOGGER.info("snippet = {}", snippet);
        LOGGER.info("exitCode = {}", execResult.getExitCode());
        LOGGER.debug("stderr = {}", execResult.getStderr());
        LOGGER.debug("stdout = {}", execResult.getStdout());
        return execResult;
    }

    private static String getStartupCommand() {
        return String.join(" && ",
                "apt-get update",
                "apt-get install --no-install-recommends -y python3 python3-pip python3-venv",
                "python3 -m venv ./venv",
                venvCommand("pip install jupyter-console --progress-bar off"),
                "tail -f /dev/null"
        );
    }

    private static String getSuccessfulCommand() {
        return venvCommand("jupyter kernelspec list")
                + " | grep ' java ' && "
                + venvCommand("jupyter console --version");
    }

    private static String venvCommand(String command) {
        return WORKING_DIRECTORY + "/venv/bin/" + command;
    }
}
