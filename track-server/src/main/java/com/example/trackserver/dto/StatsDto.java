package com.example.trackserver.dto;

import com.example.trackserver.entity.SpanEntity;

import java.util.List;

public class StatsDto {

    public record OverviewStats(long totalTraces, long totalSpans, double avgDuration, double qps) {}

    public record ServiceStat(String serviceName, long spanCount, double avgDuration, long errorCount, double errorRate) {}

    public record SlowEndpoint(String operationName, String serviceName, double avgDuration, long count, double maxDuration) {}

    public record TraceSummary(String traceId, long spanCount, long serviceCount, String startTime, double totalDuration) {}

    public record QpsPoint(String hour, long traceCount, double avgDuration) {}

    public record TraceDetailResponse(String traceId, List<SpanEntity> spans) {}
}
