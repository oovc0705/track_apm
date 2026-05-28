import { useState, useEffect, useRef, useCallback } from 'react';
import {
  Card, Row, Col, Statistic, Button, Table, Tag, Alert, Progress, Spin, Typography, Tabs, Space, Tooltip, Badge,
} from 'antd';
import {
  DashboardOutlined, WarningOutlined, CheckCircleOutlined, ExclamationCircleOutlined, ReloadOutlined, BugOutlined,
} from '@ant-design/icons';
import * as echarts from 'echarts';
import type { ColumnsType } from 'antd/es/table';
import {
  type JvmMetricsSnapshot, type ThreadDumpResponse, type ThreadInfoSnapshot, type DeadlockCycle,
  fetchThreadDump,
} from '../services/api';

const { Title, Text } = Typography;

const WS_URL = `ws://${window.location.hostname}:8080/ws/jvm-metrics`;

// ── 工具函数 ──

function formatBytes(bytes: number): string {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  if (bytes < 1024 * 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + ' MB';
  return (bytes / 1024 / 1024 / 1024).toFixed(2) + ' GB';
}

function formatDuration(ms: number): string {
  if (ms < 1000) return ms + 'ms';
  if (ms < 60000) return (ms / 1000).toFixed(1) + 's';
  return (ms / 60000).toFixed(1) + 'min';
}

function stateColor(state: string): string {
  const map: Record<string, string> = {
    RUNNABLE: 'green',
    BLOCKED: 'red',
    WAITING: 'orange',
    TIMED_WAITING: 'blue',
    TERMINATED: 'default',
    NEW: 'default',
  };
  return map[state] || 'default';
}

// ── 主组件 ──

export default function JvmMonitor() {
  // JVM 指标状态
  const [metrics, setMetrics] = useState<JvmMetricsSnapshot | null>(null);
  const [metricsHistory, setMetricsHistory] = useState<JvmMetricsSnapshot[]>([]);
  const [wsStatus, setWsStatus] = useState<'connecting' | 'connected' | 'disconnected'>('disconnected');

  // 线程诊断状态
  const [dumpResult, setDumpResult] = useState<ThreadDumpResponse | null>(null);
  const [dumpLoading, setDumpLoading] = useState(false);

  const wsRef = useRef<WebSocket | null>(null);
  const heapChartRef = useRef<HTMLDivElement>(null);
  const cpuChartRef = useRef<HTMLDivElement>(null);
  const heapChartInstanceRef = useRef<echarts.ECharts | null>(null);
  const cpuChartInstanceRef = useRef<echarts.ECharts | null>(null);

  // 最大保留数据点
  const MAX_HISTORY = 60;

  // ── WebSocket 连接 ──
  useEffect(() => {
    const connect = () => {
      setWsStatus('connecting');
      const ws = new WebSocket(WS_URL);

      ws.onopen = () => {
        setWsStatus('connected');
        console.log('[JVM WS] Connected');
      };

      ws.onmessage = (event) => {
        try {
          const snapshot: JvmMetricsSnapshot = JSON.parse(event.data);
          setMetrics(snapshot);
          setMetricsHistory(prev => {
            const next = [...prev, snapshot];
            return next.length > MAX_HISTORY ? next.slice(-MAX_HISTORY) : next;
          });
        } catch (e) {
          console.error('[JVM WS] Parse error', e);
        }
      };

      ws.onclose = () => {
        setWsStatus('disconnected');
        console.log('[JVM WS] Disconnected, reconnecting in 3s...');
        setTimeout(connect, 3000);
      };

      ws.onerror = () => ws.close();
      wsRef.current = ws;
    };

    connect();
    return () => { wsRef.current?.close(); };
  }, []);

  // ── ECharts 初始化与更新 ──
  useEffect(() => {
    if (heapChartRef.current) {
      heapChartInstanceRef.current = echarts.init(heapChartRef.current, 'dark');
    }
    if (cpuChartRef.current) {
      cpuChartInstanceRef.current = echarts.init(cpuChartRef.current, 'dark');
    }
    const handleResize = () => {
      heapChartInstanceRef.current?.resize();
      cpuChartInstanceRef.current?.resize();
    };
    window.addEventListener('resize', handleResize);
    return () => {
      window.removeEventListener('resize', handleResize);
      heapChartInstanceRef.current?.dispose();
      cpuChartInstanceRef.current?.dispose();
    };
  }, []);

  useEffect(() => {
    if (metricsHistory.length < 2) return;

    const timeLabels = metricsHistory.map(m => {
      const d = new Date(m.timestamp);
      return d.toLocaleTimeString();
    });
    const heapUsedMb = metricsHistory.map(m => +(m.heapUsed / 1024 / 1024).toFixed(1));
    const cpuLoads = metricsHistory.map(m =>
      m.systemCpuLoad >= 0 ? +(m.systemCpuLoad * 100).toFixed(1) : null
    );

    const darkAxisStyle = {
      axisLine: { lineStyle: { color: '#333' } },
      axisLabel: { color: '#888', fontSize: 11 },
      splitLine: { lineStyle: { color: 'rgba(255,255,255,0.05)' } },
    };

    heapChartInstanceRef.current?.setOption({
      tooltip: { trigger: 'axis', valueFormatter: (v: number) => v + ' MB' },
      legend: { data: ['Heap Used'], textStyle: { color: '#aaa', fontSize: 11 } },
      grid: { left: 60, right: 20, top: 30, bottom: 30 },
      xAxis: { type: 'category', data: timeLabels, ...darkAxisStyle, boundaryGap: false },
      yAxis: { type: 'value', ...darkAxisStyle, name: 'MB', nameTextStyle: { color: '#888' } },
      series: [{
        name: 'Heap Used', type: 'line', data: heapUsedMb, smooth: true, showSymbol: false,
        areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
          { offset: 0, color: 'rgba(0,212,255,0.3)' },
          { offset: 1, color: 'rgba(0,212,255,0.02)' },
        ])},
        lineStyle: { color: '#00d4ff', width: 2 },
      }],
    }, true);

    cpuChartInstanceRef.current?.setOption({
      tooltip: { trigger: 'axis', valueFormatter: (v: number | null) => v != null ? v + '%' : 'N/A' },
      legend: { data: ['System CPU'], textStyle: { color: '#aaa', fontSize: 11 } },
      grid: { left: 60, right: 20, top: 30, bottom: 30 },
      xAxis: { type: 'category', data: timeLabels, ...darkAxisStyle, boundaryGap: false },
      yAxis: { type: 'value', max: 100, ...darkAxisStyle, name: '%', nameTextStyle: { color: '#888' } },
      series: [{
        name: 'System CPU', type: 'line', data: cpuLoads, smooth: true, showSymbol: false,
        areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
          { offset: 0, color: 'rgba(250,173,20,0.3)' },
          { offset: 1, color: 'rgba(250,173,20,0.02)' },
        ])},
        lineStyle: { color: '#faad14', width: 2 },
        markLine: { data: [{ yAxis: 80, label: { formatter: '80%', color: '#ff4d4f' }, lineStyle: { color: '#ff4d4f', type: 'dashed' } }] },
      }],
    }, true);
  }, [metricsHistory]);

  // ── 线程诊断 ──
  const handleThreadDump = useCallback(async () => {
    setDumpLoading(true);
    try {
      const result = await fetchThreadDump();
      setDumpResult(result);
    } catch (e) {
      console.error('Thread dump failed', e);
    } finally {
      setDumpLoading(false);
    }
  }, []);

  // ── 表格列定义 ──
  const threadColumns: ColumnsType<ThreadInfoSnapshot> = [
    { title: 'ID', dataIndex: 'threadId', width: 70 },
    { title: 'Thread Name', dataIndex: 'threadName', ellipsis: true, width: 240,
      render: (name: string) => <Text copyable={{ text: name }} ellipsis style={{ color: '#e4e8f0', maxWidth: 220 }}>{name}</Text>,
    },
    { title: 'State', dataIndex: 'state', width: 130,
      render: (state: string) => <Tag color={stateColor(state)}>{state}</Tag>,
    },
    { title: 'Lock Owner', dataIndex: 'lockOwnerName', width: 200, ellipsis: true,
      render: (v: string | null) => v ? <Text style={{ color: '#faad14' }}>{v}</Text> : '-',
    },
    { title: 'Top Frame', width: 300, ellipsis: true,
      render: (_: unknown, record: ThreadInfoSnapshot) => {
        if (!record.stackTrace?.length) return '-';
        return <Tooltip title={record.stackTrace.slice(0, 5).join('\n')}>
          <Text code style={{ fontSize: 11 }}>{record.stackTrace[0]}</Text>
        </Tooltip>;
      },
    },
    { title: 'Blocked', dataIndex: 'blockedCount', width: 90, sorter: (a, b) => a.blockedCount - b.blockedCount },
  ];

  const blockedColumns: ColumnsType<ThreadInfoSnapshot> = [
    { title: 'ID', dataIndex: 'threadId', width: 70 },
    { title: 'Thread Name', dataIndex: 'threadName', ellipsis: true, width: 260 },
    { title: 'Blocked Count', dataIndex: 'blockedCount', width: 120 },
    { title: 'Blocked Time (ms)', dataIndex: 'blockedTimeMs', width: 140,
      render: (v: number) => v >= 0 ? v : 'N/A',
    },
    { title: 'Waiting for Lock', dataIndex: 'lockName', ellipsis: true,
      render: (v: string | null) => v ? <Text code>{v}</Text> : '-',
    },
    { title: 'Lock Owner', dataIndex: 'lockOwnerName', width: 180,
      render: (v: string | null) => v ? <Tag color="volcano">{v}</Tag> : '-',
    },
    { title: 'Stack Trace (Top 3)', width: 400,
      render: (_: unknown, record: ThreadInfoSnapshot) => (
        <pre style={{ margin: 0, fontSize: 11, color: '#aaa', maxHeight: 80, overflow: 'auto' }}>
          {(record.stackTrace || []).slice(0, 3).join('\n')}
        </pre>
      ),
    },
  ];

  // ── 渲染 ──
  const heapPercent = metrics ? metrics.heapUsagePercent : 0;
  const cpuPercent = metrics?.systemCpuLoad != null && metrics.systemCpuLoad >= 0
    ? metrics.systemCpuLoad * 100 : 0;

  return (
    <div style={{ padding: 24, maxWidth: 1600, margin: '0 auto' }}>
      {/* 顶部：WebSocket 状态 */}
      <Row justify="space-between" align="middle" style={{ marginBottom: 16 }}>
        <Col>
          <Space>
            <Title level={4} style={{ margin: 0, color: '#e4e8f0' }}>
              <DashboardOutlined /> JVM Monitor & Profiling
            </Title>
            <Badge
              status={wsStatus === 'connected' ? 'success' : wsStatus === 'connecting' ? 'processing' : 'error'}
              text={<Text style={{ color: '#888', fontSize: 12 }}>{wsStatus.toUpperCase()}</Text>}
            />
          </Space>
        </Col>
        <Col>
          <Space>
            <Text style={{ color: '#666' }}>{metrics ? `PID: ${metrics.pid}` : '-'}</Text>
            <Text style={{ color: '#666' }}>{metrics ? `Uptime: ${formatDuration(metrics.uptimeMs)}` : '-'}</Text>
          </Space>
        </Col>
      </Row>

      {/* 核心指标卡片 */}
      <Row gutter={[16, 16]} style={{ marginBottom: 24 }}>
        <Col xs={24} sm={12} lg={6}>
          <Card size="small" style={{ background: 'rgba(255,255,255,0.03)', borderColor: 'rgba(0,212,255,0.15)' }}>
            <Statistic
              title={<Text style={{ color: '#888' }}>Heap Usage</Text>}
              value={heapPercent}
              precision={1}
              suffix="%"
              prefix={<DashboardOutlined style={{ color: heapPercent > 85 ? '#ff4d4f' : '#00d4ff' }} />}
              valueStyle={{ color: heapPercent > 85 ? '#ff4d4f' : '#00d4ff', fontSize: 24 }}
            />
            {metrics && (
              <div style={{ marginTop: 8 }}>
                <Progress percent={heapPercent} size="small" showInfo={false}
                  strokeColor={heapPercent > 85 ? '#ff4d4f' : '#00d4ff'} trailColor="rgba(255,255,255,0.06)" />
                <Text style={{ color: '#666', fontSize: 12 }}>
                  {formatBytes(metrics.heapUsed)} / {formatBytes(metrics.heapMax)}
                </Text>
              </div>
            )}
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card size="small" style={{ background: 'rgba(255,255,255,0.03)', borderColor: 'rgba(250,173,20,0.15)' }}>
            <Statistic
              title={<Text style={{ color: '#888' }}>System CPU Load</Text>}
              value={cpuPercent}
              precision={1}
              suffix="%"
              prefix={cpuPercent > 80 ? <ExclamationCircleOutlined style={{ color: '#ff4d4f' }} /> : <CheckCircleOutlined style={{ color: '#faad14' }} />}
              valueStyle={{ color: cpuPercent > 80 ? '#ff4d4f' : '#faad14', fontSize: 24 }}
            />
            <Text style={{ color: '#666', fontSize: 12 }}>
              Process CPU: {metrics?.processCpuLoad != null && metrics.processCpuLoad >= 0
                ? `${(metrics.processCpuLoad * 100).toFixed(1)}%` : 'N/A'}
              {' '}({metrics?.availableProcessors || '-'} cores)
            </Text>
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card size="small" style={{ background: 'rgba(255,255,255,0.03)', borderColor: 'rgba(82,196,26,0.15)' }}>
            <Statistic
              title={<Text style={{ color: '#888' }}>GC Total Count</Text>}
              value={metrics?.gcTotalCount ?? 0}
              prefix={<ReloadOutlined style={{ color: '#52c41a' }} />}
              valueStyle={{ color: '#52c41a', fontSize: 24 }}
            />
            <Text style={{ color: '#666', fontSize: 12 }}>
              Total Time: {formatDuration(metrics?.gcTotalTimeMs ?? 0)}
              {metrics?.gcDetails?.map(g => ` | ${g.name}: ${g.count}次/${(g.timeMs/1000).toFixed(1)}s`).join('')}
            </Text>
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card size="small" style={{ background: 'rgba(255,255,255,0.03)', borderColor: 'rgba(0,212,255,0.15)' }}>
            <Statistic
              title={<Text style={{ color: '#888' }}>Non-Heap Memory</Text>}
              value={metrics ? +(metrics.nonHeapUsed / 1024 / 1024).toFixed(1) : 0}
              suffix="MB"
              prefix={<WarningOutlined style={{ color: '#00d4ff' }} />}
              valueStyle={{ color: '#00d4ff', fontSize: 24 }}
            />
            <Text style={{ color: '#666', fontSize: 12 }}>
              Committed: {metrics ? formatBytes(metrics.nonHeapCommitted) : '-'}
            </Text>
          </Card>
        </Col>
      </Row>

      {/* 趋势图表 */}
      <Row gutter={[16, 16]} style={{ marginBottom: 24 }}>
        <Col xs={24} lg={12}>
          <Card title={<Text style={{ color: '#ccc' }}>Heap Memory Trend</Text>}
                size="small" style={{ background: 'rgba(255,255,255,0.02)', borderColor: 'rgba(255,255,255,0.08)' }}>
            <div ref={heapChartRef} style={{ height: 260 }} />
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card title={<Text style={{ color: '#ccc' }}>CPU Load Trend</Text>}
                size="small" style={{ background: 'rgba(255,255,255,0.02)', borderColor: 'rgba(255,255,255,0.08)' }}>
            <div ref={cpuChartRef} style={{ height: 260 }} />
          </Card>
        </Col>
      </Row>

      {/* 线程诊断区域 */}
      <Card
        title={
          <Space>
            <BugOutlined style={{ color: '#faad14' }} />
            <Text style={{ color: '#e4e8f0', fontWeight: 600 }}>Thread Diagnostics</Text>
          </Space>
        }
        size="small"
        style={{ background: 'rgba(255,255,255,0.02)', borderColor: 'rgba(255,255,255,0.08)' }}
        extra={
          <Button type="primary" danger loading={dumpLoading} onClick={handleThreadDump}
                  icon={<BugOutlined />}>
            一键线程诊断
          </Button>
        }
      >
        {dumpResult && (
          <>
            {/* 线程状态摘要 */}
            <Row gutter={[8, 8]} style={{ marginBottom: 16 }}>
              <Col>
                <Text style={{ color: '#888' }}>Total Threads: </Text>
                <Text strong style={{ color: '#e4e8f0' }}>{dumpResult.totalThreadCount}</Text>
              </Col>
              {Object.entries(dumpResult.stateSummary).map(([state, count]) => (
                <Col key={state}>
                  <Tag color={stateColor(state)}>{state}: {count}</Tag>
                </Col>
              ))}
            </Row>

            {/* 死锁告警 */}
            {dumpResult.deadlockCycleCount > 0 && (
              <Alert
                type="error"
                showIcon
                icon={<WarningOutlined />}
                message={`检测到 ${dumpResult.deadlockCycleCount} 个死锁环!`}
                description={
                  <div>
                    {dumpResult.deadlockCycles.map((cycle: DeadlockCycle, idx: number) => (
                      <Alert
                        key={idx}
                        type="error"
                        banner
                        style={{ marginBottom: 8 }}
                        message={<Text style={{ color: '#ff4d4f', fontWeight: 600 }}>Deadlock Cycle #{idx + 1}</Text>}
                        description={<pre style={{ margin: 0, fontSize: 11, color: '#e4e8f0', whiteSpace: 'pre-wrap', background: 'rgba(255,77,79,0.08)', padding: 8, borderRadius: 4 }}>{cycle.description}</pre>}
                      />
                    ))}
                    {dumpResult.deadlockThreads.length > 0 && (
                      <div style={{ marginTop: 8 }}>
                        <Text style={{ color: '#ff4d4f' }}>Deadlock Threads:</Text>
                        <ul style={{ color: '#e4e8f0', fontSize: 12 }}>
                          {dumpResult.deadlockThreads.map(t => (
                            <li key={t.threadId}>
                              <Text code style={{ color: '#ff4d4f' }}>{t.threadName}</Text> (id={t.threadId})
                              {t.stackTrace?.[0] && <Text style={{ color: '#888' }}> — at {t.stackTrace[0]}</Text>}
                            </li>
                          ))}
                        </ul>
                      </div>
                    )}
                  </div>
                }
                style={{ marginBottom: 16 }}
              />
            )}

            <Tabs
              items={[
                {
                  key: 'blocked',
                  label: <span><Tag color="red">{dumpResult.blockedThreads.length}</Tag> BLOCKED Threads</span>,
                  children: dumpResult.blockedThreads.length > 0 ? (
                    <Table<ThreadInfoSnapshot>
                      dataSource={dumpResult.blockedThreads}
                      columns={blockedColumns}
                      rowKey="threadId"
                      size="small"
                      pagination={false}
                      scroll={{ x: 1000 }}
                      style={{ background: 'transparent' }}
                    />
                  ) : (
                    <Alert type="success" message="No BLOCKED threads found. All clear!" showIcon />
                  ),
                },
                {
                  key: 'all',
                  label: `All Threads (${dumpResult.totalThreadCount})`,
                  children: (
                    <Table<ThreadInfoSnapshot>
                      dataSource={dumpResult.allThreads}
                      columns={threadColumns}
                      rowKey="threadId"
                      size="small"
                      pagination={{ pageSize: 20, showSizeChanger: true, pageSizeOptions: ['20', '50', '100'] }}
                      scroll={{ x: 1000 }}
                      expandable={{
                        expandedRowRender: (record) => (
                          <pre style={{ margin: 0, fontSize: 11, color: '#aaa', maxHeight: 300, overflow: 'auto', background: 'rgba(0,0,0,0.3)', padding: 12, borderRadius: 4 }}>
                            {(record.stackTrace || []).join('\n') || 'No stack trace available'}
                          </pre>
                        ),
                      }}
                    />
                  ),
                },
              ]}
            />
          </>
        )}

        {!dumpResult && !dumpLoading && (
          <div style={{ textAlign: 'center', padding: '60px 0' }}>
            <BugOutlined style={{ fontSize: 48, color: '#333', marginBottom: 16 }} />
            <br />
            <Text style={{ color: '#666' }}>Click "一键线程诊断" to capture a full thread dump and analyze for deadlocks</Text>
          </div>
        )}

        {dumpLoading && (
          <div style={{ textAlign: 'center', padding: '60px 0' }}>
            <Spin size="large" tip="Dumping all threads..." />
          </div>
        )}
      </Card>
    </div>
  );
}
