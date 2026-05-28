# Track APM

一个基于 Java Agent 字节码增强的零侵入应用性能监控（APM）系统，提供分布式链路追踪、实时日志监控与可视化分析能力。

## 项目架构

```
track-apm/
├── lighttrack-agent    # Java Agent 探针（字节码增强，自动注入 TraceId）
├── track-server        # Spring Boot 服务端（数据采集、存储、推送）
├── track-dashboard     # React 前端仪表盘（链路拓扑、日志查询、实时监控）
├── docker              # Docker Compose 基础设施（PostgreSQL + Redis）
└── stress_test.*       # 压测与 Mock 数据脚本
```

## 技术栈

| 模块 | 技术 |
|------|------|
| **探针** | Java 21 + ByteBuddy + SLF4J |
| **服务端** | Spring Boot 4.0.6 + Java 21 + WebSocket + JPA + Redis + PostgreSQL |
| **前端** | React 19 + TypeScript + Vite + Ant Design + ECharts + AntV G6 |
| **基础设施** | Docker Compose (PostgreSQL 16 + Redis 7) |
| **测试** | Python + JMeter |

## 快速开始

### 1. 启动基础设施

```bash
cd docker
docker-compose up -d
```

将启动：
- **PostgreSQL** `localhost:5432` — 链路数据持久化
- **Redis** `localhost:6379` — 高并发日志缓冲区

### 2. 启动服务端

```bash
cd track-server
./mvnw spring-boot:run
```

服务端默认运行在 `http://localhost:8080`。

### 3. 启动前端

```bash
cd track-dashboard
npm install
npm run dev
```

前端开发服务器默认运行在 `http://localhost:5173`。

### 4. 接入 Agent（零侵入）

打包 Agent：

```bash
cd lighttrack-agent
mvn clean package
```

在目标应用启动参数中添加：

```bash
-javaagent:/path/to/lighttrack-agent-1.0.0.jar
```

Agent 将自动通过字节码增强为所有方法注入 `TraceId`，实现分布式链路追踪。

## 主要特性

- **零侵入接入**：基于 Java Agent + ByteBuddy 字节码增强，无需修改业务代码
- **全链路追踪**：自动注入与传递 TraceId，覆盖线程池（TTL）、跨服务调用（Feign）等场景
- **实时监控**：WebSocket 推送日志与指标，前端实时刷新
- **链路拓扑**：基于 AntV G6 绘制服务调用关系与链路图
- **高性能缓冲**：Redis 作为高并发日志缓冲区，削峰填谷
- **数据持久化**：PostgreSQL 存储结构化链路数据，支持复杂查询

## 项目模块说明

### lighttrack-agent
零侵入 APM 探针。通过 `Premain-Class` 在 JVM 启动时加载，利用 ByteBuddy 拦截方法调用，自动注入 TraceId 并收集性能数据。

### track-server
Spring Boot 服务端，职责包括：
- 接收 Agent 上报的链路数据
- 通过 JPA 持久化到 PostgreSQL
- 利用 Redis 做高并发日志缓冲
- 通过 WebSocket 向前端推送实时数据
- 提供 RESTful API 供前端查询

### track-dashboard
React + Vite 前端项目，功能包括：
- 链路查询与详情展示
- 实时日志监控面板
- 服务拓扑图（AntV G6）
- 性能指标可视化（ECharts）

## 测试

- **mock_data.py** — 生成 Mock 链路数据
- **stress_test.py** — Python 压测脚本
- **stress_test.jmx** — JMeter 压测脚本

## 许可证

MIT
