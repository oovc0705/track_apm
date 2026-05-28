package com.example.trackserver.task;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Span 分区表自动运维守护线程
 * <p>
 * 职责：
 * 1. 应用启动时，确保当日分区存在（防止启动期间无分区可写）
 * 2. 每天 00:00 自动创建明天的分区子表
 * 3. 每天检测并物理删除超过 {retention-days} 天的过期分区（滑动窗口淘汰）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpanPartitionManager {

    private final JdbcTemplate jdbcTemplate;

    private static final String PARTITION_PREFIX = "monitor_span_";
    private static final DateTimeFormatter SUFFIX_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter ISO_DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    /** 分区数据保留天数，默认 30 天 */
    @Value("${partition.retention-days:30}")
    private int retentionDays;

    // ---- 启动时初始化：确保父表为分区表 + 当日分区存在 ----

    @PostConstruct
    public void ensureTodayPartition() {
        ensureParentTable();
        createPartitionForDate(LocalDate.now());
    }

    /**
     * 确保 monitor_span 父表是 PARTITION BY RANGE (create_time) 的分区表。
     * 如果是普通表，自动备份 → 重建分区表 → 迁移数据。
     */
    private void ensureParentTable() {
        // 注意：relkind = 'p' 表示分区父表；表不存在时返回 false
        Boolean isPartitioned = jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM pg_class WHERE relname = 'monitor_span' AND relkind = 'p')", Boolean.class);
        if (Boolean.TRUE.equals(isPartitioned)) {
            log.info("[Partition Manager] monitor_span 已是分区表，跳过建表");
            return;
        }

        // 表存在但不是分区表（或不是分区父表），需要处理
        Boolean tableExists = jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM pg_class WHERE relname = 'monitor_span')", Boolean.class);
        if (Boolean.TRUE.equals(tableExists)) {
            Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM monitor_span", Long.class);
            if (count != null && count > 0) {
                log.warn("[Partition Manager] monitor_span 不是分区表，含 {} 条数据，开始自动迁移...", count);
                migrateToPartitionedTable();
                return;
            }
        }
        log.warn("[Partition Manager] monitor_span 不存在或为空，将创建分区表");
        jdbcTemplate.execute("DROP TABLE IF EXISTS monitor_span");
        jdbcTemplate.execute("DROP TABLE IF EXISTS monitor_span_backup");
        createPartitionedParentTable();
    }

    private void migrateToPartitionedTable() {
        // 1. 重命名旧表为临时备份（先清理可能残留的备份表）
        jdbcTemplate.execute("DROP TABLE IF EXISTS monitor_span_backup");
        jdbcTemplate.execute("ALTER TABLE monitor_span RENAME TO monitor_span_backup");

        // 2. 创建分区父表
        createPartitionedParentTable();

        // 3. 查询备份表中包含哪些日期，为每个日期创建分区
        List<String> dates = jdbcTemplate.queryForList(
                "SELECT DISTINCT create_time::date::text FROM monitor_span_backup ORDER BY 1", String.class);
        for (String dateStr : dates) {
            createPartitionForDate(LocalDate.parse(dateStr));
        }

        // 4. 将备份数据插回分区表
        int migrated = jdbcTemplate.update(
                "INSERT INTO monitor_span (id, trace_id, span_id, parent_span_id, service_name, " +
                "operation_name, start_time, end_time, duration, status_code, create_time) " +
                "SELECT id, trace_id, span_id, parent_span_id, service_name, " +
                "operation_name, start_time, end_time, duration, status_code, create_time " +
                "FROM monitor_span_backup");
        log.info("[Partition Manager] 数据迁移完成，共迁移 {} 条记录", migrated);

        // 5. 删除备份表
        jdbcTemplate.execute("DROP TABLE monitor_span_backup");
        log.info("[Partition Manager] 备份表已删除，分区表迁移完成");
    }

    private void createPartitionedParentTable() {
        jdbcTemplate.execute(
                "CREATE TABLE monitor_span (" +
                "  id              BIGSERIAL," +
                "  trace_id        VARCHAR(64)  NOT NULL," +
                "  span_id         VARCHAR(64)  NOT NULL," +
                "  parent_span_id  VARCHAR(64)," +
                "  service_name    VARCHAR(128) NOT NULL," +
                "  operation_name  VARCHAR(256) NOT NULL," +
                "  start_time      TIMESTAMP    NOT NULL," +
                "  end_time        TIMESTAMP," +
                "  duration        BIGINT," +
                "  status_code     INTEGER," +
                "  create_time     TIMESTAMP    NOT NULL," +
                "  PRIMARY KEY (id, create_time)" +
                ") PARTITION BY RANGE (create_time)");

        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_trace_id     ON monitor_span (trace_id)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_span_id      ON monitor_span (span_id)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_service_name ON monitor_span (service_name)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_start_time   ON monitor_span (start_time)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_trace_start  ON monitor_span (trace_id, start_time)");

        log.info("[Partition Manager] monitor_span 分区父表创建完成");
    }

    // ---- 定时调度：每天 00:00 执行 ----

    @Scheduled(cron = "0 0 0 * * ?")
    public void managePartitions() {
        try {
            // 1. 创建明日分区
            createTomorrowPartition();
            // 2. 清理过期分区
            dropExpiredPartitions();
        } catch (Exception e) {
            log.error("分区自动运维任务执行异常", e);
        }
    }

    // ---- 创建明日分区 ----

    public void createTomorrowPartition() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        createPartitionForDate(tomorrow);
    }

    // ---- 清理过期分区 ----

    public void dropExpiredPartitions() {
        LocalDate cutoff = LocalDate.now().minusDays(retentionDays);
        String cutoffSuffix = cutoff.format(SUFFIX_FMT);

        List<String> partitions = listExistingPartitions();
        int dropped = 0;

        for (String partition : partitions) {
            // 从表名中提取日期后缀，例如 "monitor_span_20260420" -> "20260420"
            String suffix = partition.substring(PARTITION_PREFIX.length());
            if (suffix.compareTo(cutoffSuffix) < 0) {
                jdbcTemplate.execute("DROP TABLE IF EXISTS " + partition);
                log.info("[Partition Cleanup] 物理删除过期分区: {} (数据日期 < {})", partition, cutoff);
                dropped++;
            }
        }

        if (dropped == 0) {
            log.debug("[Partition Cleanup] 无过期分区需要清理");
        } else {
            log.info("[Partition Cleanup] 本轮共清理 {} 个过期分区，当前活跃分区数: {}", dropped, partitions.size() - dropped);
        }
    }

    // ---- 内部方法 ----

    /**
     * 为指定日期创建分区子表（幂等：先清理可能存在的孤立表，再创建）
     */
    private void createPartitionForDate(LocalDate date) {
        String suffix = date.format(SUFFIX_FMT);
        String tableName = PARTITION_PREFIX + suffix;
        String dateStr = date.format(ISO_DATE_FMT);
        String nextDateStr = date.plusDays(1).format(ISO_DATE_FMT);

        // 检查该表是否已作为 monitor_span 的分区存在
        Boolean isCorrectPartition = jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM pg_inherits i " +
                "JOIN pg_class c ON i.inhrelid = c.oid " +
                "JOIN pg_class p ON i.inhparent = p.oid " +
                "WHERE p.relname = 'monitor_span' AND c.relname = ?)", Boolean.class, tableName);

        if (Boolean.TRUE.equals(isCorrectPartition)) {
            log.debug("[Partition Manager] 分区 {} 已正确挂载，跳过", tableName);
            return;
        }

        // 表存在但不是 monitor_span 的分区（孤立表），先清理
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + tableName);

        String sql = String.format(
                "CREATE TABLE %s PARTITION OF monitor_span " +
                "FOR VALUES FROM ('%s 00:00:00') TO ('%s 00:00:00')",
                tableName, dateStr, nextDateStr
        );

        jdbcTemplate.execute(sql);
        log.info("[Partition Manager] 确保分区 {} 存在 (范围: {} ~ {})", tableName, dateStr, nextDateStr);
    }

    /**
     * 查询当前所有已存在的分区子表名
     */
    private List<String> listExistingPartitions() {
        return jdbcTemplate.queryForList(
                "SELECT c.relname " +
                "FROM pg_inherits i " +
                "JOIN pg_class c ON i.inhrelid = c.oid " +
                "JOIN pg_class p ON i.inhparent = p.oid " +
                "WHERE p.relname = 'monitor_span' " +
                "AND c.relname LIKE 'monitor_span_%' " +
                "ORDER BY c.relname",
                String.class
        );
    }
}
