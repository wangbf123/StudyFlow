package com.studyflow.agent.harness;

import static com.studyflow.agent.AgentTypes.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.studyflow.agent.*;
import com.studyflow.agent.observability.HarnessTelemetry;
import com.studyflow.ai.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

class TelemetryAndMockTest {
    @Test
    void validTraceIdsExistWithoutAnOtlpCollector() {
        var telemetry =
                new HarnessTelemetry(
                        mock(JdbcTemplate.class),
                        new SimpleMeterRegistry(),
                        new HarnessProperties());
        try {
            var run =
                    new Claim(
                            10L,
                            2L,
                            3L,
                            4L,
                            "research",
                            "PLAN",
                            "__START__",
                            "lease",
                            1,
                            CheckpointState.initial(List.of(), new AgentProperties()));
            var span = telemetry.start(run, "plan", "default");
            assertTrue(span.getSpanContext().isValid());
            telemetry.record(run, "plan", "default", "mock", "SUCCESS", 1, 0, 0, span);
        } finally {
            telemetry.close();
        }
    }

    @Test
    void mockAcceptsCompactedJsonExcerptsAndUsesExplicitCurrentNote() throws Exception {
        String context =
                "{\"contextNoteId\":\"42\",\"observations\":[{\"tool\":\"knowledge_overview\"},{\"tool\":\"search_notes\",\"compressed\":true,\"content\":\"{\\\"items\\\":[{\\\"id\\\":\"}]}";
        var result =
                new MockAiClient()
                        .callTool(
                                new AiToolRequest(
                                        "system", context, "read current note", null, List.of()));
        assertEquals("read_note", result.name());
        assertEquals("42", result.arguments().path("noteId").asText());
    }
}
