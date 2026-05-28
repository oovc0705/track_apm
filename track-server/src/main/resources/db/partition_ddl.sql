-- ============================================================
-- monitor_span 分区表 DDL（PostgreSQL 15+ Range Partition）
-- 执行前请确保旧表数据已备份（如有）
-- ============================================================

-- 1. 删除旧的非分区表（开发环境直接重建；生产环境请先备份）
DROP TABLE IF EXISTS monitor_span CASCADE;

-- 2. 创建分区主表
--    注意：分区键 create_time 必须包含在主键中
CREATE TABLE monitor_span (
    id              BIGSERIAL       NOT NULL,
    trace_id        VARCHAR(64)     NOT NULL,
    span_id         VARCHAR(64)     NOT NULL,
    parent_span_id  VARCHAR(64),
    service_name    VARCHAR(128)    NOT NULL,
    operation_name  VARCHAR(256)    NOT NULL,
    start_time      TIMESTAMP       NOT NULL,
    end_time        TIMESTAMP,
    duration        BIGINT,
    status_code     INTEGER,
    create_time     TIMESTAMP       NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id, create_time)
) PARTITION BY RANGE (create_time);

-- 3. 创建索引（PostgreSQL 会自动为每个分区子表创建对应索引）
CREATE INDEX idx_trace_id     ON monitor_span (trace_id);
CREATE INDEX idx_span_id      ON monitor_span (span_id);
CREATE INDEX idx_service_name ON monitor_span (service_name);
CREATE INDEX idx_start_time   ON monitor_span (start_time);
CREATE INDEX idx_trace_start  ON monitor_span (trace_id, start_time);
CREATE INDEX idx_create_time  ON monitor_span (create_time);

-- 4. 创建当日分区（应用启动时由 Java 代码自动保障，此处为手动兜底）
--    下面是模板示例，请将日期替换为实际执行日期：
--    CREATE TABLE monitor_span_20260525 PARTITION OF monitor_span
--    FOR VALUES FROM ('2026-05-25 00:00:00') TO ('2026-05-26 00:00:00');

-- 5. 验证分区是否创建成功
SELECT c.relname AS partition_name
FROM pg_inherits i
JOIN pg_class c ON i.inhrelid = c.oid
JOIN pg_class p ON i.inhparent = p.oid
WHERE p.relname = 'monitor_span'
ORDER BY c.relname;
