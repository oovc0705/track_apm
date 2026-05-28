import { useEffect, useMemo, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Tag, Tooltip, Input, Button, Spin, Empty } from 'antd';
import { ClockCircleOutlined, NodeIndexOutlined, SearchOutlined, ArrowLeftOutlined } from '@ant-design/icons';
import { fetchTraceDetail, type SpanData } from '../services/api';
import './TraceDetail.css';

interface InternalSpan {
  traceId: string;
  spanId: string;
  parentSpanId: string | null;
  serviceName: string;
  operationName: string;
  startTime: number;
  endTime: number;
  duration: number;
  statusCode?: number;
}

interface ProcessedSpan extends InternalSpan {
  depth: number;
  offsetPercent: number;
  widthPercent: number;
}

const SERVICE_COLORS: Record<string, string> = {};
const PALETTE = [
  '#00d4ff', '#00e396', '#f9a825', '#a855f7',
  '#ff6b6b', '#36d399', '#f472b6', '#60a5fa',
  '#fbbf24', '#34d399', '#c084fc', '#fb923c',
];

let colorIndex = 0;
function getServiceColor(serviceName: string): string {
  if (!SERVICE_COLORS[serviceName]) {
    SERVICE_COLORS[serviceName] = PALETTE[colorIndex % PALETTE.length];
    colorIndex++;
  }
  return SERVICE_COLORS[serviceName];
}

function processSpans(spans: InternalSpan[]): ProcessedSpan[] {
  if (spans.length === 0) return [];

  const depthMap = new Map<string, number>();
  for (const span of spans) {
    if (span.parentSpanId === null) {
      depthMap.set(span.spanId, 0);
    } else {
      const parentDepth = depthMap.get(span.parentSpanId) ?? 0;
      depthMap.set(span.spanId, parentDepth + 1);
    }
  }

  const minStart = Math.min(...spans.map(s => s.startTime));
  const maxEnd = Math.max(...spans.map(s => s.endTime));
  const totalDuration = maxEnd - minStart;

  return spans.map(span => ({
    ...span,
    depth: depthMap.get(span.spanId) ?? 0,
    offsetPercent: totalDuration > 0 ? ((span.startTime - minStart) / totalDuration) * 100 : 0,
    widthPercent: totalDuration > 0 ? (span.duration / totalDuration) * 100 : 0,
  }));
}

function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}μs`;
  if (ms < 1_000_000) return `${(ms / 1000).toFixed(1)}ms`;
  return `${(ms / 1_000_000).toFixed(2)}s`;
}

function normalizeSpan(span: SpanData): InternalSpan {
  return {
    ...span,
    startTime: new Date(span.startTime).getTime(),
    endTime: new Date(span.endTime).getTime(),
  };
}

export default function TraceDetail() {
  const { traceId: urlTraceId } = useParams<{ traceId?: string }>();
  const navigate = useNavigate();
  const [searchValue, setSearchValue] = useState(urlTraceId || '');
  const [traceId, setTraceId] = useState(urlTraceId || '');
  const [rawSpans, setRawSpans] = useState<SpanData[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (urlTraceId) {
      setSearchValue(urlTraceId);
      setTraceId(urlTraceId);
      loadTrace(urlTraceId);
    }
  }, [urlTraceId]);

  async function loadTrace(tid: string) {
    if (!tid.trim()) return;
    setLoading(true);
    try {
      const data = await fetchTraceDetail(tid.trim());
      setTraceId(data.traceId);
      setRawSpans(data.spans);
    } catch (err) {
      setRawSpans([]);
      setTraceId(tid.trim());
    } finally {
      setLoading(false);
    }
  }

  function handleSearch() {
    if (searchValue.trim()) {
      navigate(`/trace/${searchValue.trim()}`);
    }
  }

  const internalSpans = useMemo(() => rawSpans.map(normalizeSpan), [rawSpans]);
  const spans = useMemo(() => processSpans(internalSpans), [internalSpans]);
  const totalDuration = internalSpans.length > 0
    ? Math.max(...internalSpans.map(s => s.endTime)) - Math.min(...internalSpans.map(s => s.startTime))
    : 0;
  const minStart = internalSpans.length > 0 ? Math.min(...internalSpans.map(s => s.startTime)) : 0;

  return (
    <div className="trace-detail">
      {/* Search bar */}
      <div className="trace-search">
        <Input.Search
          placeholder="Enter TraceId to search..."
          value={searchValue}
          onChange={e => setSearchValue(e.target.value)}
          onSearch={handleSearch}
          enterButton={
            <Button type="primary" icon={<SearchOutlined />} loading={loading}>
              Search
            </Button>
          }
          style={{ maxWidth: 600 }}
          allowClear
        />
        {urlTraceId && (
          <Button
            type="text"
            icon={<ArrowLeftOutlined />}
            className="back-btn"
            onClick={() => navigate('/trace')}
          >
            Clear
          </Button>
        )}
      </div>

      {loading ? (
        <div className="trace-loading">
          <Spin size="large" tip="Loading trace..." />
        </div>
      ) : spans.length > 0 ? (
        <>
          {/* Header */}
          <div className="trace-header">
            <div className="trace-header-title">
              <NodeIndexOutlined className="trace-icon" />
              <span className="trace-title-text">Trace Detail</span>
              <Tag color="cyan" className="trace-id-tag">{traceId}</Tag>
            </div>
            <div className="trace-header-stats">
              <span className="stat-item">
                <ClockCircleOutlined />
                <span>Total: <strong>{formatDuration(totalDuration)}</strong></span>
              </span>
              <span className="stat-item">
                <NodeIndexOutlined />
                <span>Spans: <strong>{spans.length}</strong></span>
              </span>
              <span className="stat-item">
                <span>Services: <strong>{new Set(spans.map(s => s.serviceName)).size}</strong></span>
              </span>
            </div>
          </div>

          {/* Timeline ruler */}
          <div className="trace-timeline-ruler">
            <div className="ruler-tree-label">Service / Operation</div>
            <div className="ruler-bar-area">
              {Array.from({ length: 5 }).map((_, i) => (
                <div
                  key={i}
                  className="ruler-tick"
                  style={{ left: `${(i / 4) * 100}%` }}
                >
                  <span className="ruler-tick-line" />
                  <span className="ruler-tick-label">
                    {formatDuration(Math.round((i / 4) * totalDuration))}
                  </span>
                </div>
              ))}
            </div>
          </div>

          {/* Span rows */}
          <div className="trace-spans">
            {spans.map((span, index) => {
              const color = getServiceColor(span.serviceName);
              return (
                <Tooltip
                  key={span.spanId}
                  title={
                    <div className="span-tooltip">
                      <div><strong>{span.serviceName}</strong></div>
                      <div>{span.operationName}</div>
                      <div>Duration: {formatDuration(span.duration)}</div>
                      <div>Start: +{formatDuration(span.startTime - minStart)}</div>
                      {span.statusCode && <div>Status: {span.statusCode}</div>}
                    </div>
                  }
                  placement="left"
                >
                  <div className="span-row" style={{ animationDelay: `${index * 40}ms` }}>
                    {/* Left: Tree structure */}
                    <div className="span-tree-cell">
                      <div
                        className="span-tree-content"
                        style={{ paddingLeft: `${span.depth * 24 + 8}px` }}
                      >
                        {span.depth > 0 && (
                          <>
                            <span className="tree-connector-vertical" style={{ left: `${(span.depth - 1) * 24 + 12}px` }} />
                            <span className="tree-connector-horizontal" style={{ left: `${span.depth * 24}px` }} />
                          </>
                        )}
                        <span className="tree-toggle expanded">
                          <span className="tree-dot" style={{ backgroundColor: color }} />
                        </span>
                        <div className="span-label">
                          <span className="span-service" style={{ color }}>
                            {span.serviceName}
                          </span>
                          <span className="span-operation">{span.operationName}</span>
                        </div>
                      </div>
                    </div>

                    {/* Right: Gantt bar */}
                    <div className="span-bar-cell">
                      <div className="span-bar-track">
                        <div
                          className="span-bar"
                          style={{
                            left: `${span.offsetPercent}%`,
                            width: `${Math.max(span.widthPercent, 0.3)}%`,
                            '--bar-color': color,
                          } as React.CSSProperties}
                        >
                          {span.widthPercent > 8 && (
                            <span className="span-bar-label">
                              {formatDuration(span.duration)}
                            </span>
                          )}
                        </div>
                      </div>
                      <div className="span-duration-label">
                        {span.widthPercent <= 8 && formatDuration(span.duration)}
                      </div>
                    </div>
                  </div>
                </Tooltip>
              );
            })}
          </div>

          {/* Service legend */}
          <div className="trace-legend">
            {Object.entries(SERVICE_COLORS).map(([name, color]) => (
              <div key={name} className="legend-item">
                <span className="legend-dot" style={{ backgroundColor: color }} />
                <span className="legend-name">{name}</span>
              </div>
            ))}
          </div>
        </>
      ) : traceId ? (
        <Empty
          description={<span style={{ color: '#6b7280' }}>No spans found for trace: <code style={{ color: '#00d4ff' }}>{traceId}</code></span>}
          style={{ marginTop: 80 }}
        />
      ) : (
        <div className="trace-empty">
          <NodeIndexOutlined style={{ fontSize: 48, color: '#2a2d3a', marginBottom: 16 }} />
          <p style={{ color: '#4b5563' }}>Enter a TraceId above to view the trace waterfall</p>
        </div>
      )}
    </div>
  );
}
