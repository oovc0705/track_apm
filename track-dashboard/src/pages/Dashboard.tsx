import { useEffect, useRef, useState } from 'react';
import { Card, Col, Row, Statistic, Table, Tag, Spin, Empty } from 'antd';
import {
  ThunderboltOutlined,
  ClockCircleOutlined,
  ApiOutlined,
  DashboardOutlined,
  LineChartOutlined,
} from '@ant-design/icons';
import * as echarts from 'echarts';
import {
  fetchOverview,
  fetchServiceStats,
  fetchSlowEndpoints,
  fetchQpsTrend,
  fetchRecentTraces,
  type OverviewStats,
  type ServiceStat,
  type SlowEndpoint,
  type QpsPoint,
  type TraceSummary,
} from '../services/api';
import { useNavigate } from 'react-router-dom';
import './Dashboard.css';

function formatDuration(ms: number): string {
  if (ms < 1) return `${(ms * 1000).toFixed(0)}μs`;
  if (ms < 1000) return `${ms.toFixed(1)}ms`;
  return `${(ms / 1000).toFixed(2)}s`;
}

export default function Dashboard() {
  const [overview, setOverview] = useState<OverviewStats | null>(null);
  const [services, setServices] = useState<ServiceStat[]>([]);
  const [slowEndpoints, setSlowEndpoints] = useState<SlowEndpoint[]>([]);
  const [qpsTrend, setQpsTrend] = useState<QpsPoint[]>([]);
  const [recentTraces, setRecentTraces] = useState<TraceSummary[]>([]);
  const [loading, setLoading] = useState(true);

  const qpsChartRef = useRef<HTMLDivElement>(null);
  const qpsChartInstance = useRef<echarts.ECharts | null>(null);
  const slowChartRef = useRef<HTMLDivElement>(null);
  const slowChartInstance = useRef<echarts.ECharts | null>(null);
  const navigate = useNavigate();

  useEffect(() => {
    loadData();
  }, []);

  useEffect(() => {
    const handleResize = () => {
      qpsChartInstance.current?.resize();
      slowChartInstance.current?.resize();
    };
    window.addEventListener('resize', handleResize);
    return () => {
      window.removeEventListener('resize', handleResize);
      qpsChartInstance.current?.dispose();
      slowChartInstance.current?.dispose();
    };
  }, []);

  useEffect(() => {
    if (!qpsChartRef.current || qpsTrend.length === 0) return;
    if (!qpsChartInstance.current) {
      qpsChartInstance.current = echarts.init(qpsChartRef.current, 'dark');
    }
    qpsChartInstance.current.setOption({
      backgroundColor: 'transparent',
      tooltip: { trigger: 'axis' },
      legend: {
        data: ['Traces', 'Avg RT'],
        textStyle: { color: '#6b7280', fontSize: 11 },
        top: 0,
        right: 0,
      },
      grid: { left: 60, right: 60, top: 36, bottom: 36 },
      xAxis: {
        type: 'category',
        data: qpsTrend.map(p => p.hour),
        axisLabel: { color: '#4b5563', fontSize: 10, rotate: 30 },
        axisLine: { lineStyle: { color: 'rgba(255,255,255,0.08)' } },
        axisTick: { lineStyle: { color: 'rgba(255,255,255,0.08)' } },
      },
      yAxis: [
        {
          type: 'value',
          axisLabel: { color: '#4b5563', fontSize: 10 },
          splitLine: { lineStyle: { color: 'rgba(255,255,255,0.04)' } },
        },
        {
          type: 'value',
          axisLabel: { color: '#4b5563', fontSize: 10 },
          splitLine: { show: false },
        },
      ],
      series: [
        {
          name: 'Traces',
          type: 'line',
          data: qpsTrend.map(p => p.traceCount),
          smooth: true,
          lineStyle: { color: '#00d4ff', width: 2 },
          itemStyle: { color: '#00d4ff' },
          symbol: 'circle',
          symbolSize: 4,
          areaStyle: {
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: 'rgba(0, 212, 255, 0.25)' },
              { offset: 1, color: 'rgba(0, 212, 255, 0.01)' },
            ]),
          },
        },
        {
          name: 'Avg RT',
          type: 'line',
          yAxisIndex: 1,
          data: qpsTrend.map(p => Number(p.avgDuration.toFixed(1))),
          smooth: true,
          lineStyle: { color: '#f9a825', width: 2 },
          itemStyle: { color: '#f9a825' },
          symbol: 'circle',
          symbolSize: 4,
        },
      ],
    }, true);
  }, [qpsTrend]);

  useEffect(() => {
    if (!slowChartRef.current || slowEndpoints.length === 0) return;
    if (!slowChartInstance.current) {
      slowChartInstance.current = echarts.init(slowChartRef.current, 'dark');
    }
    const sorted = [...slowEndpoints].reverse();
    slowChartInstance.current.setOption({
      backgroundColor: 'transparent',
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow' },
        formatter: (params: unknown) => {
          const p = params as { dataIndex: number }[];
          const ep = sorted[p[0].dataIndex];
          return `<div style="font-size:12px">
            <b>${ep.operationName}</b><br/>
            Service: ${ep.serviceName}<br/>
            Avg: ${formatDuration(ep.avgDuration)}<br/>
            Max: ${formatDuration(ep.maxDuration)}<br/>
            Count: ${ep.count}
          </div>`;
        },
      },
      grid: { left: 180, right: 50, top: 8, bottom: 24 },
      xAxis: {
        type: 'value',
        axisLabel: { color: '#4b5563', fontSize: 10, formatter: (v: number) => formatDuration(v) },
        splitLine: { lineStyle: { color: 'rgba(255,255,255,0.04)' } },
      },
      yAxis: {
        type: 'category',
        data: sorted.map(e => `${e.serviceName} | ${e.operationName}`),
        axisLabel: { color: '#9ca3af', fontSize: 10, width: 160, overflow: 'truncate' },
        axisLine: { lineStyle: { color: 'rgba(255,255,255,0.08)' } },
        axisTick: { show: false },
      },
      series: [
        {
          type: 'bar',
          data: sorted.map(e => Number(e.avgDuration.toFixed(1))),
          barWidth: 14,
          itemStyle: {
            color: new echarts.graphic.LinearGradient(0, 0, 1, 0, [
              { offset: 0, color: '#ff6b6b' },
              { offset: 1, color: '#fbbf24' },
            ]),
            borderRadius: [0, 3, 3, 0],
          },
        },
      ],
    }, true);
  }, [slowEndpoints]);

  async function loadData() {
    setLoading(true);
    try {
      const [ov, srv, slow, qps, traces] = await Promise.all([
        fetchOverview().catch(() => null),
        fetchServiceStats().catch(() => []),
        fetchSlowEndpoints().catch(() => []),
        fetchQpsTrend().catch(() => []),
        fetchRecentTraces(20, 0).catch(() => []),
      ]);
      if (ov) setOverview(ov);
      setServices(srv);
      setSlowEndpoints(slow);
      setQpsTrend(qps);
      setRecentTraces(traces);
    } catch (err) {
      console.error('Failed to load dashboard data', err);
    } finally {
      setLoading(false);
    }
  }

  const serviceColumns = [
    {
      title: 'Service', dataIndex: 'serviceName', key: 'serviceName',
      render: (v: string) => <Tag color="cyan" style={{ fontFamily: 'inherit' }}>{v}</Tag>,
    },
    { title: 'Spans', dataIndex: 'spanCount', key: 'spanCount', width: 90 },
    {
      title: 'Avg RT', dataIndex: 'avgDuration', key: 'avgDuration', width: 110,
      render: (v: number) => formatDuration(v),
    },
    { title: 'Errors', dataIndex: 'errorCount', key: 'errorCount', width: 80 },
    {
      title: 'Error Rate', dataIndex: 'errorRate', key: 'errorRate', width: 100,
      render: (v: number) => {
        const rate = v * 100;
        const color = rate < 1 ? '#00e396' : rate < 5 ? '#fbbf24' : '#ff6b6b';
        return <span style={{ color, fontWeight: 600 }}>{rate.toFixed(2)}%</span>;
      },
    },
    {
      title: 'Health', key: 'health', width: 80,
      render: (_: unknown, row: ServiceStat) => {
        const rate = row.errorRate * 100;
        const label = rate < 1 ? 'healthy' : rate < 5 ? 'warning' : 'critical';
        const color = rate < 1 ? 'success' : rate < 5 ? 'warning' : 'error';
        return <Tag color={color} style={{ fontFamily: 'inherit' }}>{label}</Tag>;
      },
    },
  ];

  const traceColumns = [
    {
      title: 'Trace ID', dataIndex: 'traceId', key: 'traceId',
      render: (v: string) => (
        <span className="trace-link" onClick={() => navigate(`/trace/${v}`)}>{v}</span>
      ),
    },
    { title: 'Services', dataIndex: 'serviceCount', key: 'serviceCount', width: 90 },
    { title: 'Spans', dataIndex: 'spanCount', key: 'spanCount', width: 80 },
    {
      title: 'Duration', dataIndex: 'totalDuration', key: 'totalDuration', width: 110,
      render: (v: number) => formatDuration(v),
    },
    {
      title: 'Start Time', dataIndex: 'startTime', key: 'startTime', width: 180,
      render: (v: string) => v ? new Date(v).toLocaleString() : '-',
    },
  ];

  if (loading) {
    return <div className="dashboard-loading"><Spin size="large" /></div>;
  }

  return (
    <div className="dashboard">
      <div className="dashboard-header">
        <DashboardOutlined className="dashboard-icon" />
        <span className="dashboard-title">APM Dashboard</span>
      </div>

      {/* Overview cards */}
      <Row gutter={[16, 16]} className="dashboard-cards">
        <Col xs={24} sm={12} lg={6}>
          <Card className="stat-card" bordered={false}>
            <Statistic
              title="Total Traces"
              value={overview?.totalTraces ?? 0}
              prefix={<ThunderboltOutlined />}
              valueStyle={{ color: '#00d4ff' }}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card className="stat-card" bordered={false}>
            <Statistic
              title="Total Spans"
              value={overview?.totalSpans ?? 0}
              prefix={<ApiOutlined />}
              valueStyle={{ color: '#a855f7' }}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card className="stat-card" bordered={false}>
            <Statistic
              title="Avg Response Time"
              value={overview ? formatDuration(overview.avgDuration) : '0ms'}
              prefix={<ClockCircleOutlined />}
              valueStyle={{ color: '#f9a825', fontSize: 20 }}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card className="stat-card" bordered={false}>
            <Statistic
              title="QPS (Last Hour)"
              value={overview?.qps ?? 0}
              precision={2}
              prefix={<LineChartOutlined />}
              valueStyle={{ color: '#00e396' }}
            />
          </Card>
        </Col>
      </Row>

      {/* Charts row */}
      <Row gutter={[16, 16]} className="dashboard-charts">
        <Col xs={24} lg={14}>
          <Card title="QPS & RT Trend (24h)" className="chart-card" bordered={false}>
            {qpsTrend.length > 0
              ? <div ref={qpsChartRef} className="chart-container" />
              : <Empty description="No data in last 24 hours" image={Empty.PRESENTED_IMAGE_SIMPLE} style={{ padding: 80 }} />
            }
          </Card>
        </Col>
        <Col xs={24} lg={10}>
          <Card title="Top 10 Slow Endpoints" className="chart-card" bordered={false}>
            {slowEndpoints.length > 0
              ? <div ref={slowChartRef} className="chart-container" />
              : <Empty description="No span data yet" image={Empty.PRESENTED_IMAGE_SIMPLE} style={{ padding: 80 }} />
            }
          </Card>
        </Col>
      </Row>

      {/* Tables row */}
      <Row gutter={[16, 16]} className="dashboard-tables">
        <Col xs={24} lg={10}>
          <Card title="Service Health" className="table-card" bordered={false}>
            {services.length > 0
              ? <Table
                  dataSource={services}
                  columns={serviceColumns}
                  rowKey="serviceName"
                  pagination={false}
                  size="small"
                  scroll={{ x: 600 }}
                />
              : <Empty description="No service data yet" image={Empty.PRESENTED_IMAGE_SIMPLE} style={{ padding: 60 }} />
            }
          </Card>
        </Col>
        <Col xs={24} lg={14}>
          <Card title="Recent Traces" className="table-card" bordered={false}>
            {recentTraces.length > 0
              ? <Table
                  dataSource={recentTraces}
                  columns={traceColumns}
                  rowKey="traceId"
                  pagination={{ pageSize: 10, size: 'small', showSizeChanger: false }}
                  size="small"
                  scroll={{ x: 700 }}
                />
              : <Empty description="No trace data yet" image={Empty.PRESENTED_IMAGE_SIMPLE} style={{ padding: 60 }} />
            }
          </Card>
        </Col>
      </Row>
    </div>
  );
}
