package com.example.trackserver.repository;

import com.example.trackserver.entity.SpanEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SpanRepository extends JpaRepository<SpanEntity, Long> {

    List<SpanEntity> findByTraceIdOrderByStartTimeAsc(String traceId);

    @Query("SELECT COUNT(DISTINCT s.traceId) FROM SpanEntity s")
    long countDistinctTraceId();

    @Query("SELECT COALESCE(AVG(s.duration), 0) FROM SpanEntity s WHERE s.duration IS NOT NULL")
    double avgDuration();

    @Query(value = "SELECT COUNT(*) FROM monitor_span WHERE start_time > NOW() - INTERVAL '1 hour'", nativeQuery = true)
    long countLastHour();

    @Query(value = """
            SELECT s.service_name, COUNT(*), COALESCE(AVG(s.duration), 0),
                   COALESCE(SUM(CASE WHEN s.status_code >= 400 THEN 1 ELSE 0 END), 0)
            FROM monitor_span s
            GROUP BY s.service_name
            """, nativeQuery = true)
    List<Object[]> findServiceStats();

    @Query(value = """
            SELECT operation_name, service_name, AVG(duration), COUNT(*), MAX(duration)
            FROM monitor_span
            WHERE duration IS NOT NULL
            GROUP BY operation_name, service_name
            ORDER BY AVG(duration) DESC
            LIMIT 10
            """, nativeQuery = true)
    List<Object[]> findTopSlowEndpoints();

    @Query(value = """
            SELECT trace_id, COUNT(*), COUNT(DISTINCT service_name), MIN(start_time),
                   COALESCE(EXTRACT(EPOCH FROM (MAX(end_time) - MIN(start_time))) * 1000, 0)
            FROM monitor_span
            GROUP BY trace_id
            ORDER BY MIN(start_time) DESC
            LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<Object[]> findRecentTraces(@Param("limit") int limit, @Param("offset") int offset);

    @Query(value = """
            SELECT TO_CHAR(date_trunc('hour', start_time), 'YYYY-MM-DD HH24:MI'),
                   COUNT(DISTINCT trace_id), AVG(duration)
            FROM monitor_span
            WHERE start_time > NOW() - INTERVAL '24 hours'
            GROUP BY 1 ORDER BY 1
            """, nativeQuery = true)
    List<Object[]> findQpsTrend();
}
