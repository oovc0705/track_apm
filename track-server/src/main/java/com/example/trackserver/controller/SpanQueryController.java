package com.example.trackserver.controller;

import com.example.trackserver.dto.StatsDto;
import com.example.trackserver.repository.SpanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class SpanQueryController {

    private final SpanRepository spanRepository;

    @GetMapping("/stats/overview")
    public ResponseEntity<StatsDto.OverviewStats> getOverview() {
        long totalTraces = spanRepository.countDistinctTraceId();
        long totalSpans = spanRepository.count();
        double avgDuration = spanRepository.avgDuration();
        long lastHourCount = spanRepository.countLastHour();
        double qps = Math.round(lastHourCount / 3600.0 * 100.0) / 100.0;
        return ResponseEntity.ok(new StatsDto.OverviewStats(totalTraces, totalSpans, avgDuration, qps));
    }

    @GetMapping("/stats/services")
    public ResponseEntity<List<StatsDto.ServiceStat>> getServiceStats() {
        List<Object[]> rows = spanRepository.findServiceStats();
        List<StatsDto.ServiceStat> stats = rows.stream().map(row -> {
            String serviceName = (String) row[0];
            long spanCount = ((Number) row[1]).longValue();
            double avgDuration = ((Number) row[2]).doubleValue();
            long errorCount = ((Number) row[3]).longValue();
            double errorRate = spanCount > 0 ? Math.round((double) errorCount / spanCount * 10000.0) / 10000.0 : 0;
            return new StatsDto.ServiceStat(serviceName, spanCount, avgDuration, errorCount, errorRate);
        }).toList();
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/stats/slow-endpoints")
    public ResponseEntity<List<StatsDto.SlowEndpoint>> getSlowEndpoints() {
        List<Object[]> rows = spanRepository.findTopSlowEndpoints();
        List<StatsDto.SlowEndpoint> endpoints = rows.stream().map(row -> new StatsDto.SlowEndpoint(
                (String) row[0],
                (String) row[1],
                ((Number) row[2]).doubleValue(),
                ((Number) row[3]).longValue(),
                ((Number) row[4]).doubleValue()
        )).toList();
        return ResponseEntity.ok(endpoints);
    }

    @GetMapping("/stats/qps-trend")
    public ResponseEntity<List<StatsDto.QpsPoint>> getQpsTrend() {
        List<Object[]> rows = spanRepository.findQpsTrend();
        List<StatsDto.QpsPoint> points = rows.stream().map(row -> new StatsDto.QpsPoint(
                (String) row[0],
                ((Number) row[1]).longValue(),
                row[2] != null ? ((Number) row[2]).doubleValue() : 0
        )).toList();
        return ResponseEntity.ok(points);
    }

    @GetMapping("/traces")
    public ResponseEntity<List<StatsDto.TraceSummary>> getRecentTraces(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        List<Object[]> rows = spanRepository.findRecentTraces(limit, offset);
        List<StatsDto.TraceSummary> traces = rows.stream().map(row -> new StatsDto.TraceSummary(
                (String) row[0],
                ((Number) row[1]).longValue(),
                ((Number) row[2]).longValue(),
                row[3] != null ? row[3].toString() : "",
                ((Number) row[4]).doubleValue()
        )).toList();
        return ResponseEntity.ok(traces);
    }

    @GetMapping("/traces/{traceId}")
    public ResponseEntity<StatsDto.TraceDetailResponse> getTraceDetail(@PathVariable String traceId) {
        var spans = spanRepository.findByTraceIdOrderByStartTimeAsc(traceId);
        return ResponseEntity.ok(new StatsDto.TraceDetailResponse(traceId, spans));
    }
}
