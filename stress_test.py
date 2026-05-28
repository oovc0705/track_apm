"""
APM Collect API 并发压力测试
模拟多线程高频率发送 span 数据，测试 Redis 队列 + Consumer + PostgreSQL 全链路吞吐。

用法:
  python stress_test.py                                 # 默认 20 并发, 30 秒
  python stress_test.py --concurrency 50 --duration 60  # 50 并发, 60 秒
  python stress_test.py --concurrency 10 --rps 100      # 每线程 100 RPS（速率限制）
  python stress_test.py --url http://192.168.1.100:8080 # 指定服务器

依赖: pip install requests
"""

import argparse
import json
import random
import statistics
import sys
import threading
import time
import uuid
from datetime import datetime, timedelta, timezone

try:
    import requests
except ImportError:
    print("请先安装 requests: pip install requests")
    sys.exit(1)

SERVICES = [
    {"name": "gateway-service", "operations": ["GET /api/users", "GET /api/orders", "POST /api/checkout"]},
    {"name": "user-service", "operations": ["GET /users/{id}", "POST /users/login", "SELECT * FROM users"]},
    {"name": "order-service", "operations": ["GET /orders", "POST /orders", "SELECT * FROM orders"]},
    {"name": "payment-service", "operations": ["POST /payments", "GET /payments/{id}"]},
    {"name": "inventory-service", "operations": ["GET /inventory/{sku}", "UPDATE inventory"]},
]

# 共享统计
stats_lock = threading.Lock()
total_requests = 0
total_errors = 0
latencies = []
total_spans_sent = 0


def generate_spans(count=5):
    """生成一批模拟 span（同一 traceId 的调用链）"""
    trace_id = uuid.uuid4().hex[:32]
    spans = []
    parent_id = None
    now = datetime.now(timezone.utc)
    t = now

    for _ in range(count):
        svc = random.choice(SERVICES)
        op = random.choice(svc["operations"])
        dur = max(1, int(random.gauss(300, 150)))
        span_id = uuid.uuid4().hex[:16]

        spans.append({
            "traceId": trace_id,
            "spanId": span_id,
            "parentSpanId": parent_id,
            "serviceName": svc["name"],
            "operationName": op,
            "startTime": t.isoformat(),
            "endTime": (t + timedelta(milliseconds=dur)).isoformat(),
            "duration": dur,
            "statusCode": random.choice([200] * 95 + [500, 502, 503, 404]),
        })
        parent_id = span_id
        t += timedelta(milliseconds=dur)

    return spans


def worker(url, duration, rps_limit, batch_size, stop_event):
    """工作线程：持续发送请求直到超时"""
    global total_requests, total_errors, total_spans_sent

    interval = 1.0 / rps_limit if rps_limit > 0 else 0
    session = requests.Session()

    while not stop_event.is_set():
        start = time.monotonic()

        spans = generate_spans(count=batch_size)
        try:
            t0 = time.monotonic()
            resp = session.post(
                f"{url}/api/v1/collect",
                json=spans,
                headers={"Content-Type": "application/json"},
                timeout=10,
            )
            latency_ms = (time.monotonic() - t0) * 1000

            with stats_lock:
                total_requests += 1
                total_spans_sent += len(spans)
                latencies.append(latency_ms)
                if resp.status_code != 202:
                    total_errors += 1

        except requests.exceptions.ConnectionError:
            with stats_lock:
                total_requests += 1
                total_errors += 1
        except requests.exceptions.Timeout:
            with stats_lock:
                total_requests += 1
                total_errors += 1
                latencies.append(10000)
        except Exception as e:
            with stats_lock:
                total_requests += 1
                total_errors += 1

        # 速率控制
        if interval > 0:
            elapsed = time.monotonic() - start
            if elapsed < interval:
                time.sleep(interval - elapsed)


def monitor(stop_event, interval=3):
    """后台监控线程：实时打印统计"""
    prev_requests = 0
    prev_time = time.monotonic()

    while not stop_event.is_set():
        stop_event.wait(interval)
        with stats_lock:
            cur_requests = total_requests
            cur_errors = total_errors
            cur_spans = total_spans_sent
        now = time.monotonic()
        dt = now - prev_time
        if dt > 0:
            rps = (cur_requests - prev_requests) / dt
            spans_per_sec = (cur_spans - (total_spans_sent - cur_errors * 5)) / dt  # 近似
            err_rate = (cur_errors / cur_requests * 100) if cur_requests > 0 else 0
            print(f"  [监控] RPS: {rps:.1f} req/s | 总请求: {cur_requests} | 总span: {cur_spans} | 错误: {cur_errors} ({err_rate:.1f}%)")
        prev_requests = cur_requests
        prev_time = now


def run_stress_test(concurrency, duration, rps_per_thread, batch_size, url):
    global total_requests, total_errors, total_spans_sent
    total_requests = 0
    total_errors = 0
    total_spans_sent = 0
    latencies.clear()

    stop_event = threading.Event()

    print("=" * 60)
    print("  APM Collect API 压力测试")
    print("=" * 60)
    print(f"  目标地址:   {url}/api/v1/collect")
    print(f"  并发线程:   {concurrency}")
    print(f"  持续时间:   {duration} 秒")
    print(f"  每线程RPS:  {rps_per_thread if rps_per_thread > 0 else '不限（最大努力）'}")
    print(f"  每批span数: {batch_size}")
    print(f"  理论总RPS:  {concurrency * rps_per_thread if rps_per_thread > 0 else '无上限'}")
    print(f"  理论span/s: {concurrency * rps_per_thread * batch_size if rps_per_thread > 0 else '无上限'}")
    print("=" * 60)
    print()

    # 启动监控线程
    monitor_thread = threading.Thread(target=monitor, args=(stop_event,), daemon=True)
    monitor_thread.start()

    # 启动工作线程
    start_time = time.monotonic()
    threads = []
    for i in range(concurrency):
        t = threading.Thread(
            target=worker,
            args=(url, duration, rps_per_thread, batch_size, stop_event),
            daemon=True,
        )
        t.start()
        threads.append(t)

    # 等待持续时间结束
    time.sleep(duration)
    stop_event.set()

    # 等待所有线程结束（最多 5 秒）
    for t in threads:
        t.join(timeout=5)

    elapsed = time.monotonic() - start_time

    # 输出汇总结果
    print()
    print("=" * 60)
    print("  测试结果汇总")
    print("=" * 60)

    success = total_requests - total_errors
    with stats_lock:
        sorted_lat = sorted(latencies) if latencies else [0]

    p50 = sorted_lat[int(len(sorted_lat) * 0.50)] if sorted_lat else 0
    p90 = sorted_lat[int(len(sorted_lat) * 0.90)] if sorted_lat else 0
    p95 = sorted_lat[int(len(sorted_lat) * 0.95)] if sorted_lat else 0
    p99 = sorted_lat[int(len(sorted_lat) * 0.99)] if sorted_lat else 0

    avg_rps = total_requests / elapsed if elapsed > 0 else 0
    avg_spans = total_spans_sent / elapsed if elapsed > 0 else 0
    err_rate = (total_errors / total_requests * 100) if total_requests > 0 else 0

    print(f"  总耗时:       {elapsed:.1f} 秒")
    print(f"  总请求数:     {total_requests}")
    print(f"  成功请求:     {success}")
    print(f"  失败请求:     {total_errors} ({err_rate:.1f}%)")
    print(f"  总 Span 数:   {total_spans_sent}")
    print(f"  平均 RPS:     {avg_rps:.1f} req/s")
    print(f"  平均 Span/s:  {avg_spans:.0f} span/s")
    print()
    print(f"  延迟 P50:     {p50:.1f} ms")
    print(f"  延迟 P90:     {p90:.1f} ms")
    print(f"  延迟 P95:     {p95:.1f} ms")
    print(f"  延迟 P99:     {p99:.1f} ms")
    if len(latencies) > 1:
        print(f"  平均延迟:     {statistics.mean(latencies):.1f} ms")
        print(f"  标准差:       {statistics.stdev(latencies):.1f} ms")
    print("=" * 60)

    # 判定结果
    print()
    if err_rate > 5:
        print("  [!] 错误率超过 5%，建议降低并发或检查服务状态")
    if p99 > 500:
        print("  [!] P99 延迟超过 500ms，可能存在瓶颈")
    if avg_spans >= 10000:
        print("  [OK] 吞吐量达到 10K span/s 目标!")
    elif avg_spans >= 5000:
        print("  [~]  吞吐量达到 5K span/s，可尝试提高并发")
    else:
        print("  [!] 吞吐量偏低，建议检查链路瓶颈")


def main():
    parser = argparse.ArgumentParser(description="APM Collect API 压力测试")
    parser.add_argument("-c", "--concurrency", type=int, default=20, help="并发线程数（默认 20）")
    parser.add_argument("-d", "--duration", type=int, default=30, help="测试持续时间秒（默认 30）")
    parser.add_argument("-r", "--rps", type=int, default=10, help="每线程目标 RPS（默认 10，0=不限）")
    parser.add_argument("-b", "--batch-size", type=int, default=5, help="每请求 span 数量（默认 5）")
    parser.add_argument("-u", "--url", type=str, default="http://localhost:8080", help="服务器地址（默认 http://localhost:8080）")
    args = parser.parse_args()

    run_stress_test(args.concurrency, args.duration, args.rps, args.batch_size, args.url)


if __name__ == "__main__":
    main()
