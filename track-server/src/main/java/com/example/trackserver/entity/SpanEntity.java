package com.example.trackserver.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "monitor_span", indexes = {
        @Index(name = "idx_trace_id", columnList = "trace_id"),
        @Index(name = "idx_span_id", columnList = "span_id"),
        @Index(name = "idx_service_name", columnList = "service_name"),
        @Index(name = "idx_start_time", columnList = "start_time"),
        @Index(name = "idx_trace_start", columnList = "trace_id, start_time")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SpanEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trace_id", nullable = false, length = 64)
    private String traceId;

    @Column(name = "span_id", nullable = false, length = 64)
    private String spanId;

    @Column(name = "parent_span_id", length = 64)
    private String parentSpanId;

    @Column(name = "service_name", nullable = false, length = 128)
    private String serviceName;

    @Column(name = "operation_name", nullable = false, length = 256)
    private String operationName;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time")
    private Instant endTime;

    @Column(name = "duration")
    private Long duration;

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(name = "create_time", nullable = false, updatable = false)
    private Instant createTime;

    @PrePersist
    protected void onCreate() {
        if (createTime == null) {
            createTime = Instant.now();
        }
    }
}
