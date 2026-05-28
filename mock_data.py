"""
APM Mock Data Generator
通过 POST /api/v1/collect 上报模拟 Span 数据，走完整的 Redis → Consumer → PostgreSQL 链路。

用法:
  python mock_data.py                          # 默认生成 100 条 trace，时间跨度 72 小时
  python mock_data.py --traces 500 --hours 48  # 自定义 trace 数量和时间跨度
  python mock_data.py --batch-size 20          # 每批发送 20 条 span（避免一次性发送太多）
  python mock_data.py --url http://localhost:8080  # 自定义服务地址

依赖: pip install requests
"""

import argparse
import json
import math
import random
import sys
import time
import uuid
from datetime import datetime, timedelta, timezone

try:
    import requests
except ImportError:
    print("请先安装 requests: pip install requests")
    sys.exit(1)

# ── 模拟的服务和操作定义 ──

SERVICES = [
    {
        "name": "gateway-service",
        "operations": [
            "GET /api/users",
            "GET /api/orders",
            "POST /api/orders",
            "GET /api/products",
            "POST /api/checkout",
            "GET /api/health",
        ],
        "base_latency": 150,
    },
    {
        "name": "user-service",
        "operations": [
            "GET /users/{id}",
            "GET /users/me",
            "POST /users/login",
            "POST /users/register",
            "SELECT * FROM users",
            "SELECT * FROM user_profiles",
        ],
        "base_latency": 350,
    },
    {
        "name": "order-service",
        "operations": [
            "GET /orders",
            "POST /orders",
            "GET /orders/{id}",
            "PUT /orders/{id}/status",
            "SELECT * FROM orders",
            "INSERT INTO orders",
        ],
        "base_latency": 450,
    },
    {
        "name": "payment-service",
        "operations": [
            "POST /payments",
            "GET /payments/{id}",
            "POST /payments/refund",
            "POST /api/alipay/callback",
            "SELECT * FROM payments",
        ],
        "base_latency": 600,
    },
    {
        "name": "inventory-service",
        "operations": [
            "GET /inventory/{sku}",
            "PUT /inventory/{sku}",
            "GET /inventory/check",
            "SELECT * FROM inventory",
            "UPDATE inventory SET stock",
        ],
        "base_latency": 280,
    },
    {
        "name": "notification-service",
        "operations": [
            "POST /notify/email",
            "POST /notify/sms",
            "POST /notify/webhook",
            "GET /notify/status/{id}",
        ],
        "base_latency": 520,
    },
]

# 典型的调用链路（模拟真实微服务调用关系）
TRACE_PATTERNS = [
    # 用户查询
    ["gateway-service", "user-service"],
    ["gateway-service", "user-service", "user-service"],
    # 订单创建
    ["gateway-service", "order-service", "inventory-service", "payment-service"],
    ["gateway-service", "order-service", "payment-service"],
    # 订单查询
    ["gateway-service", "order-service"],
    ["gateway-service", "order-service", "user-service"],
    # 支付流程
    ["gateway-service", "payment-service", "notification-service"],
    ["gateway-service", "order-service", "payment-service", "notification-service"],
    # 库存查询
    ["gateway-service", "inventory-service"],
    # 健康检查（单个 span）
    ["gateway-service"],
]


def random_id(length: int = 16) -> str:
    return uuid.uuid4().hex[:length]


def normal_latency(base: int) -> int:
    """生成正态分布的延迟（毫秒），模拟真实场景"""
    mean = base
    std = base * 0.6
    latency = random.gauss(mean, std)
    return max(1, int(latency))


def generate_traces(trace_count: int, hours_back: int):
    """生成模拟 trace 数据"""
    now = datetime.now(timezone.utc)
    earliest = now - timedelta(hours=hours_back)
    time_range_ms = (now - earliest).total_seconds() * 1000

    traces = []

    for _ in range(trace_count):
        trace_id = random_id(32)
        # 在时间范围内随机分布，但偏向近期（指数分布）
        trace_time_offset_ms = random.expovariate(2.0 / time_range_ms)
        trace_start = earliest + timedelta(milliseconds=trace_time_offset_ms)

        # 随机选择一个调用链路模式
        pattern = random.choice(TRACE_PATTERNS)

        # 决定是否产生错误（整体约 5% 的 trace 会包含错误）
        has_error = random.random() < 0.05
        error_position = random.randint(0, max(0, len(pattern) - 1)) if has_error else -1

        spans = []
        parent_span_id = None
        current_time = trace_start

        for i, service_name in enumerate(pattern):
            service = next(s for s in SERVICES if s["name"] == service_name)
            operation = random.choice(service["operations"])

            latency = normal_latency(service["base_latency"])

            # 如果是错误位置，增加延迟并标记错误码
            status_code = 200
            if i == error_position:
                latency += random.randint(500, 3000)
                status_code = random.choice([400, 401, 403, 404, 500, 502, 503, 504])

            span = {
                "traceId": trace_id,
                "spanId": random_id(16),
                "parentSpanId": parent_span_id,
                "serviceName": service_name,
                "operationName": operation,
                "startTime": current_time.isoformat(),
                "endTime": (current_time + timedelta(milliseconds=latency)).isoformat(),
                "duration": latency,
                "statusCode": status_code,
            }
            spans.append(span)

            parent_span_id = span["spanId"]
            # 下一个 span 的开始时间略有重叠（并行调用）或顺序执行
            overlap = random.random() < 0.3
            if overlap and i < len(pattern) - 1:
                # 部分重叠，模拟并行
                current_time = current_time + timedelta(milliseconds=int(latency * 0.3))
            else:
                current_time = current_time + timedelta(milliseconds=latency)

        traces.append(spans)

    return traces


def send_spans(url: str, spans: list, batch_size: int) -> None:
    """批量发送 span 数据到 Collect API"""
    collect_url = f"{url}/api/v1/collect"

    # 扁平化所有 trace 的 spans
    all_spans = [span for trace in spans for span in trace]

    total = len(all_spans)
    accepted_total = 0
    sampled_total = 0
    batches = math.ceil(total / batch_size)

    print(f"共 {total} 个 span，分 {batches} 批发送（每批 {batch_size}）")
    print(f"目标地址: {collect_url}")
    print("-" * 50)

    for i in range(0, total, batch_size):
        batch = all_spans[i : i + batch_size]
        batch_num = i // batch_size + 1

        try:
            resp = requests.post(
                collect_url,
                json=batch,
                headers={"Content-Type": "application/json"},
                timeout=30,
            )
            if resp.status_code == 202:
                body = resp.text
                accepted_total += batch_size
                print(f"  批次 {batch_num}/{batches}: {body}")
            else:
                print(f"  批次 {batch_num}/{batches}: HTTP {resp.status_code} - {resp.text}")
        except requests.exceptions.ConnectionError:
            print(f"  批次 {batch_num}/{batches}: 连接失败，请确认服务已启动")
            sys.exit(1)
        except requests.exceptions.Timeout:
            print(f"  批次 {batch_num}/{batches}: 请求超时")
        except Exception as e:
            print(f"  批次 {batch_num}/{batches}: 错误 - {e}")

        # 批次间稍微间隔，避免 Redis 队列积压
        if i + batch_size < total:
            time.sleep(0.2)

    print("-" * 50)
    print(f"发送完成! 共发送 {accepted_total} 个 span")
    print(f"数据已进入 Redis 队列，等待 SpanConsumerService 消费写入 PostgreSQL")
    print(f"稍后刷新 Dashboard 即可看到数据")


def main():
    parser = argparse.ArgumentParser(description="APM 模拟数据生成器")
    parser.add_argument(
        "--traces", type=int, default=100, help="生成的 trace 数量（默认 100）"
    )
    parser.add_argument(
        "--hours", type=int, default=72, help="时间跨度（小时，默认 72）"
    )
    parser.add_argument(
        "--batch-size", type=int, default=30, help="每批发送的 span 数量（默认 30）"
    )
    parser.add_argument(
        "--url",
        type=str,
        default="http://localhost:8080",
        help="服务器地址（默认 http://localhost:8080）",
    )
    args = parser.parse_args()

    print(f"正在生成 {args.traces} 条 trace（时间跨度 {args.hours} 小时）...")
    traces = generate_traces(args.traces, args.hours)
    span_count = sum(len(t) for t in traces)
    print(f"生成完毕: {len(traces)} traces, {span_count} spans")
    print()

    send_spans(args.url, traces, args.batch_size)


if __name__ == "__main__":
    main()
