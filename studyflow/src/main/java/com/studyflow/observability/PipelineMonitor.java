package com.studyflow.observability;

import com.studyflow.task.PipelineStage;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Fixed-cardinality cached gauges; scraping never executes a database query. */
@Component
public class PipelineMonitor {
    private final JdbcTemplate jdbc;
    private final Map<String,AtomicLong> counts = new ConcurrentHashMap<>();
    private final AtomicLong oldest = new AtomicLong();
    private final AtomicLong observedAt = new AtomicLong();
    private final AtomicLong available = new AtomicLong();

    public PipelineMonitor(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc=jdbc;
        for (PipelineStage stage : PipelineStage.values()) {
            for (String status : new String[]{"PENDING","PROCESSING","RETRYING","SUCCESS","FAILED","CANCELLED"}) {
                AtomicLong value=new AtomicLong();
                counts.put(stage+":"+status,value);
                Gauge.builder("studyflow.pipeline.tasks",value,AtomicLong::get)
                        .tag("stage",stage.name()).tag("status",status).register(registry);
            }
        }
        Gauge.builder("studyflow.pipeline.oldest.pending.seconds",oldest,AtomicLong::get).register(registry);
        Gauge.builder("studyflow.pipeline.snapshot.epoch.seconds",observedAt,AtomicLong::get).register(registry);
        Gauge.builder("studyflow.pipeline.snapshot.available",available,AtomicLong::get).register(registry);
    }

    @Scheduled(fixedDelayString="${studyflow.metrics.refresh-ms:15000}",initialDelay=5000)
    public void refresh() {
        try {
            Map<String,Long> snapshot=new java.util.HashMap<>();
            jdbc.query("SELECT stage,status,COUNT(*) FROM parse_task GROUP BY stage,status",rs -> {
                snapshot.put(rs.getString(1)+":"+rs.getString(2),rs.getLong(3));
            });
            Long age=jdbc.queryForObject("""
                    SELECT COALESCE(GREATEST(TIMESTAMPDIFF(SECOND,MIN(t.next_attempt_time),NOW()),0),0)
                    FROM parse_task t JOIN document d ON d.id=t.document_id AND d.generation=t.generation
                    JOIN knowledge_base k ON k.id=d.knowledge_base_id
                    WHERE t.status IN ('PENDING','RETRYING') AND t.next_attempt_time<=NOW(6) AND k.status='ACTIVE'
                      AND ((t.stage='CLEANUP' AND d.parse_status='DELETED') OR (t.stage<>'CLEANUP' AND d.parse_status<>'DELETED'))
                    """,Long.class);
            counts.forEach((key,value)->value.set(snapshot.getOrDefault(key,0L)));
            oldest.set(age==null ? 0 : age);
            observedAt.set(System.currentTimeMillis()/1000);
            available.set(1);
        } catch (RuntimeException unavailable) {
            // Preserve the last observation and expose staleness explicitly.
            available.set(0);
        }
    }
}
