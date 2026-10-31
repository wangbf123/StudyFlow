package com.studyflow.agent.harness;

import static org.junit.jupiter.api.Assertions.*;

import com.studyflow.agent.HarnessProperties;
import com.studyflow.agent.runtime.DockerEnvironment;
import com.studyflow.common.BusinessException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;

class DockerEnvironmentTest {
    @Test
    void disabledAndUnpinnedEnvironmentsCannotExecute() {
        var config = new HarnessProperties();
        var runtime = new DockerEnvironment(config);
        assertFalse(runtime.available());
        assertThrows(BusinessException.class, () -> runtime.python("print(1)"));
        config.setSandboxEnabled(true);
        config.setSandboxImage("python:latest");
        assertFalse(runtime.available());
    }

    @Test
    @EnabledIfEnvironmentVariable(
            named = "STUDYFLOW_SANDBOX_TEST_IMAGE",
            matches = ".+@sha256:[a-f0-9]{64}")
    void realContainerHasNoNetworkNoHostFilesAndEnforcesOutputAndTimeLimits() {
        var config = new HarnessProperties();
        config.setSandboxEnabled(true);
        config.setSandboxImage(System.getenv("STUDYFLOW_SANDBOX_TEST_IMAGE"));
        var runtime = new DockerEnvironment(config);
        var result =
                runtime.python(
                        """
                        import os, pathlib, socket
                        assert os.getuid() == 65534
                        assert not pathlib.Path('/var/run/docker.sock').exists()
                        assert not pathlib.Path('/workspace/StudyFlow').exists()
                        pathlib.Path('/tmp/allowed').write_text('ephemeral')
                        try:
                            pathlib.Path('/etc/forbidden').write_text('blocked')
                            raise AssertionError('root filesystem writable')
                        except OSError: pass
                        sock=socket.socket(); sock.settimeout(1)
                        try:
                            sock.connect(('1.1.1.1',53))
                            raise AssertionError('network available')
                        except OSError: pass
                        print('isolation-verified')
                        """);
        assertEquals(0, result.get("exitCode"));
        assertTrue(result.get("output").toString().contains("isolation-verified"));
        assertThrows(BusinessException.class, () -> runtime.python("print('x'*70000)"));
        assertTimeoutPreemptively(
                Duration.ofSeconds(27),
                () -> {
                    var error =
                            assertThrows(
                                    BusinessException.class,
                                    () -> runtime.python("while True: pass"));
                    assertEquals(408, error.getCode());
                });
    }
}
