package com.studyflow.agent.runtime;

import com.studyflow.agent.HarnessProperties;
import com.studyflow.common.BusinessException;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;

/** Optional capability: no host mounts, no network, no Docker socket inside the sandbox. */
@Component
@RequiredArgsConstructor
public class DockerEnvironment {
    private final HarnessProperties properties;
    private final Semaphore slots = new Semaphore(2);

    public boolean available() {
        return properties.isSandboxEnabled()
                && properties.getSandboxImage().matches("[a-zA-Z0-9./:_-]+@sha256:[a-f0-9]{64}");
    }

    public Map<String, Object> python(String code) {
        if (!available()) throw new BusinessException(403, "隔离代码环境尚未启用");
        if (code == null || code.length() > 16000) throw new BusinessException(400, "代码超出大小限制");
        if (!slots.tryAcquire()) throw new BusinessException(429, "隔离环境繁忙");
        String name = "studyflow-sandbox-" + UUID.randomUUID();
        Process process = null;
        ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
        try {
            var cmd =
                    new ArrayList<>(
                            List.of(
                                    "docker",
                                    "run",
                                    "--rm",
                                    "--pull",
                                    "never",
                                    "--name",
                                    name,
                                    "--network",
                                    "none",
                                    "--read-only",
                                    "--cap-drop",
                                    "ALL",
                                    "--security-opt",
                                    "no-new-privileges",
                                    "--pids-limit",
                                    "64",
                                    "--memory",
                                    "256m",
                                    "--cpus",
                                    "1",
                                    "--user",
                                    "65534:65534",
                                    "--tmpfs",
                                    "/tmp:rw,noexec,nosuid,size=16777216",
                                    properties.getSandboxImage(),
                                    "python",
                                    "-I",
                                    "-c",
                                    code));
            process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            Process active = process;
            {
                var output = executor.submit(() -> active.getInputStream().readNBytes(64001));
                byte[] bytes = output.get(20, TimeUnit.SECONDS);
                if (bytes.length > 64000) throw new BusinessException(400, "执行输出超出限制");
                if (!process.waitFor(2, TimeUnit.SECONDS)) throw new BusinessException(408, "执行超时");
                return Map.of(
                        "exitCode",
                        process.exitValue(),
                        "output",
                        new String(bytes, java.nio.charset.StandardCharsets.UTF_8),
                        "network",
                        "NONE",
                        "filesystem",
                        "EPHEMERAL");
            }
        } catch (TimeoutException ex) {
            throw new BusinessException(408, "执行达到 20 秒上限");
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new BusinessException(502, "隔离执行未完成");
        } finally {
            executor.shutdownNow();
            if (process != null) process.destroyForcibly();
            try {
                new ProcessBuilder("docker", "rm", "-f", name)
                        .redirectErrorStream(true)
                        .start()
                        .waitFor(3, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
            slots.release();
        }
    }
}
