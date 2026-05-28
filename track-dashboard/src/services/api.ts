import axios from 'axios';

const api = axios.create({
  baseURL: '/api/v1',
  timeout: 10000,
});

// ── 现有接口 ──

export interface OverviewStats {
  totalTraces: number;
  totalSpans: number;
  avgDuration: number;
  qps: number;
}

export function fetchOverview() {
  return api.get<OverviewStats>('/stats/overview').then(r => r.data);
}

export interface ServiceStat {
  serviceName: string;
  spanCount: number;
  avgDuration: number;
  errorCount: number;
  errorRate: number;
}

export function fetchServiceStats() {
  return api.get<ServiceStat[]>('/stats/services').then(r => r.data);
}

export interface SlowEndpoint {
  operationName: string;
  serviceName: string;
  avgDuration: number;
  count: number;
  maxDuration: number;
}

export function fetchSlowEndpoints() {
  return api.get<SlowEndpoint[]>('/stats/slow-endpoints').then(r => r.data);
}

export interface QpsPoint {
  hour: string;
  traceCount: number;
  avgDuration: number;
}

export function fetchQpsTrend() {
  return api.get<QpsPoint[]>('/stats/qps-trend').then(r => r.data);
}

export interface TraceSummary {
  traceId: string;
  spanCount: number;
  serviceCount: number;
  startTime: string;
  totalDuration: number;
}

export function fetchRecentTraces(limit = 20, offset = 0) {
  return api.get<TraceSummary[]>('/traces', { params: { limit, offset } }).then(r => r.data);
}

export interface SpanData {
  traceId: string;
  spanId: string;
  parentSpanId: string | null;
  serviceName: string;
  operationName: string;
  startTime: string;
  endTime: string;
  duration: number;
  statusCode?: number;
}

export interface TraceDetailResponse {
  traceId: string;
  spans: SpanData[];
}

export function fetchTraceDetail(traceId: string) {
  return api.get<TraceDetailResponse>(`/traces/${encodeURIComponent(traceId)}`).then(r => r.data);
}

// ── JVM 监控接口 ──

export interface GcStat {
  name: string;
  count: number;
  timeMs: number;
}

export interface JvmMetricsSnapshot {
  pid: string;
  jvmName: string;
  timestamp: number;
  heapUsed: number;
  heapMax: number;
  heapUsagePercent: number;
  nonHeapUsed: number;
  nonHeapCommitted: number;
  gcTotalCount: number;
  gcTotalTimeMs: number;
  gcDetails: GcStat[];
  systemCpuLoad: number;
  processCpuLoad: number;
  availableProcessors: number;
  uptimeMs: number;
}

export function fetchJvmMetrics() {
  return api.get<JvmMetricsSnapshot>('/jvm/metrics').then(r => r.data);
}

// ── 线程诊断接口 ──

export interface MonitorInfoSnapshot {
  className: string;
  identityHashCode: string;
  stackDepth: number;
  stackFrame: string | null;
}

export interface ThreadInfoSnapshot {
  threadId: number;
  threadName: string;
  state: string;
  cpuTimeNanos: number;
  userTimeNanos: number;
  isInNative: boolean;
  isSuspended: boolean;
  priority: number;
  lockName: string | null;
  lockOwnerName: string | null;
  lockOwnerId: number;
  waitedLockName: string | null;
  waitedCount: number;
  waitedTimeMs: number;
  blockedCount: number;
  blockedTimeMs: number;
  stackTrace: string[] | null;
  lockedSynchronizers: { className: string; identityHashCode: string; toString: string }[] | null;
  lockedMonitors: MonitorInfoSnapshot[] | null;
}

export interface DeadlockCycle {
  cycleChain: string[];
  description: string;
  severity: string;
}

export interface ThreadDumpResponse {
  timestamp: number;
  totalThreadCount: number;
  allThreads: ThreadInfoSnapshot[];
  blockedThreads: ThreadInfoSnapshot[];
  deadlockThreads: ThreadInfoSnapshot[];
  deadlockCycles: DeadlockCycle[];
  deadlockCycleCount: number;
  stateSummary: Record<string, number>;
}

export function fetchThreadDump() {
  return api.get<ThreadDumpResponse>('/profiling/thread-dump').then(r => r.data);
}
