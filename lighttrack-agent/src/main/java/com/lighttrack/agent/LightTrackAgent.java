package com.lighttrack.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;

import java.lang.instrument.Instrumentation;

import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/**
 * LightTrack APM 零侵入探针入口
 * <p>
 * 通过 {@code -javaagent} 挂载后，自动拦截请求入口方法及消息队列生产/消费方法，
 * 在不修改目标应用任何代码的前提下，实现 TraceId 的自动注入与 MDC 绑定。
 * <p>
 * 拦截策略（HTTP + MQ 全链路）：
 * <ul>
 *   <li>HTTP 主入口：{@code DispatcherServlet#doDispatch} — 覆盖所有 Spring MVC 应用</li>
 *   <li>HTTP 备选：{@code StandardEngineValve#invoke} — 覆盖纯 Tomcat 场景</li>
 *   <li>Kafka 生产者：{@code KafkaProducer#send} — 拦截消息发送，注入 traceId 到 Headers</li>
 *   <li>Kafka 消费者：{@code MessagingMessageListenerAdapter#onMessage} — 提取 traceId 绑定 MDC</li>
 *   <li>RabbitMQ 生产者：{@code RabbitTemplate#send} — 拦截消息发送，注入 traceId 到 Headers</li>
 *   <li>RabbitMQ 消费者：{@code MessagingMessageListenerAdapter#onMessage} — 提取 traceId 绑定 MDC</li>
 * </ul>
 * <p>
 * 使用方式：
 * <pre>
 * java -javaagent:/path/to/lighttrack-agent-1.0.0.jar -jar your-app.jar
 * </pre>
 */
public class LightTrackAgent {

    // ── HTTP 拦截目标 ──
    private static final String DISPATCHER_SERVLET = "org.springframework.web.servlet.DispatcherServlet";
    private static final String STANDARD_ENGINE_VALVE = "org.apache.catalina.core.StandardEngineValve";

    // ── Kafka 拦截目标 ──
    private static final String KAFKA_PRODUCER = "org.apache.kafka.clients.producer.KafkaProducer";
    private static final String KAFKA_LISTENER_ADAPTER = "org.springframework.kafka.listener.adapter.MessagingMessageListenerAdapter";

    // ── RabbitMQ 拦截目标 ──
    private static final String RABBIT_TEMPLATE = "org.springframework.amqp.rabbit.core.RabbitTemplate";
    private static final String RABBIT_LISTENER_ADAPTER = "org.springframework.amqp.rabbit.listener.adapter.MessagingMessageListenerAdapter";

    /**
     * premain — JVM 启动时挂载的入口函数（对应 MANIFEST.MF 中的 Premain-Class）
     *
     * @param args            Agent 启动参数，可选，格式：key=value,key2=value2
     * @param instrumentation JVM Instrumentation 实例
     */
    public static void premain(String args, Instrumentation instrumentation) {
        installAgent(args, instrumentation);
    }

    /**
     * agentmain — 支持运行时动态挂载（Java Attach API）
     * <p>
     * 可通过 {@code com.sun.tools.attach.VirtualMachine#loadAgent()} 在运行中注入探针。
     *
     * @param args            Agent 启动参数
     * @param instrumentation JVM Instrumentation 实例
     */
    public static void agentmain(String args, Instrumentation instrumentation) {
        installAgent(args, instrumentation);
    }

    /**
     * 构建并安装 ByteBuddy Agent
     */
    private static void installAgent(String args, Instrumentation instrumentation) {
        AgentBuilder agentBuilder = new AgentBuilder.Default()
                // 允许对已加载的类进行重新转换
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                // 跳过初始化阶段的类加载（避免循环依赖）
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                // 使用 REBASE 策略保留原始方法（便于调试和问题排查）
                .with(AgentBuilder.TypeStrategy.Default.REBASE)
                // 忽略探针自身和 ByteBuddy 的类
                .ignore(nameStartsWith("com.lighttrack.agent."))
                .ignore(nameStartsWith("net.bytebuddy."));

        // ── 拦截 Spring DispatcherServlet#doDispatch ──
        agentBuilder = agentBuilder.type(named(DISPATCHER_SERVLET))
                .transform(new AgentBuilder.Transformer() {
                    @Override
                    public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder,
                                                            TypeDescription typeDescription,
                                                            ClassLoader classLoader,
                                                            JavaModule module) {
                        return builder.method(
                                named("doDispatch")
                                        .and(takesArguments(2))
                        ).intercept(Advice.to(TraceAdvice.class, classLoader));
                    }
                });

        // ── 拦截 Tomcat StandardEngineValve#invoke ──
        agentBuilder = agentBuilder.type(named(STANDARD_ENGINE_VALVE))
                .transform(new AgentBuilder.Transformer() {
                    @Override
                    public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder,
                                                            TypeDescription typeDescription,
                                                            ClassLoader classLoader,
                                                            JavaModule module) {
                        return builder.method(named("invoke"))
                                .intercept(Advice.to(TraceAdvice.class, classLoader));
                    }
                });

        // ── 拦截 KafkaProducer#send（1 参数 / 2 参数两个重载） ──
        // 拦截时机：消息发送前，从 MDC 提取 traceId 注入 ProducerRecord Headers
        agentBuilder = agentBuilder.type(named(KAFKA_PRODUCER))
                .transform(new AgentBuilder.Transformer() {
                    @Override
                    public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder,
                                                            TypeDescription typeDescription,
                                                            ClassLoader classLoader,
                                                            JavaModule module) {
                        return builder.method(
                                named("send")
                                        .and(takesArguments(1).or(takesArguments(2)))
                        ).intercept(Advice.to(KafkaProducerAdvice.class, classLoader));
                    }
                });

        // ── 拦截 Kafka MessagingMessageListenerAdapter#onMessage ──
        // 拦截时机：消费线程被唤醒、@KafkaListener 方法执行前，从 ConsumerRecord Headers 提取 traceId 绑定 MDC
        agentBuilder = agentBuilder.type(named(KAFKA_LISTENER_ADAPTER))
                .transform(new AgentBuilder.Transformer() {
                    @Override
                    public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder,
                                                            TypeDescription typeDescription,
                                                            ClassLoader classLoader,
                                                            JavaModule module) {
                        return builder.method(
                                named("onMessage")
                                        .and(takesArguments(1).or(takesArguments(2)))
                        ).intercept(Advice.to(KafkaConsumerAdvice.class, classLoader));
                    }
                });

        // ── 拦截 RabbitTemplate#send（1~4 参数所有重载） ──
        // 拦截时机：消息发送前，从 MDC 提取 traceId 注入 AMQP MessageProperties Headers
        // 注意：convertAndSend 内部最终调用 send，因此拦截 send 即可覆盖两条发送路径
        agentBuilder = agentBuilder.type(named(RABBIT_TEMPLATE))
                .transform(new AgentBuilder.Transformer() {
                    @Override
                    public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder,
                                                            TypeDescription typeDescription,
                                                            ClassLoader classLoader,
                                                            JavaModule module) {
                        return builder.method(
                                named("send")
                                        .and(takesArguments(1)
                                                .or(takesArguments(2))
                                                .or(takesArguments(3))
                                                .or(takesArguments(4)))
                        ).intercept(Advice.to(RabbitProducerAdvice.class, classLoader));
                    }
                });

        // ── 拦截 RabbitMQ MessagingMessageListenerAdapter#onMessage ──
        // 拦截时机：消费线程被唤醒、@RabbitListener 方法执行前，从 AMQP Message Headers 提取 traceId 绑定 MDC
        agentBuilder = agentBuilder.type(named(RABBIT_LISTENER_ADAPTER))
                .transform(new AgentBuilder.Transformer() {
                    @Override
                    public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder,
                                                            TypeDescription typeDescription,
                                                            ClassLoader classLoader,
                                                            JavaModule module) {
                        return builder.method(
                                named("onMessage")
                                        .and(takesArguments(1).or(takesArguments(2)))
                        ).intercept(Advice.to(RabbitConsumerAdvice.class, classLoader));
                    }
                });

        // 安装探针，并将字节码转换过程输出到标准输出（便于调试）
        agentBuilder.with(AgentBuilder.Listener.StreamWriting.toSystemOut().withTransformationsOnly())
                .installOn(instrumentation);

        System.out.println("========================================");
        System.out.println("[LightTrack Agent] 零侵入探针已挂载成功 v1.0.0");
        System.out.println("[LightTrack Agent] ── HTTP 链路 ──");
        System.out.println("[LightTrack Agent]   拦截: " + DISPATCHER_SERVLET + "#doDispatch");
        System.out.println("[LightTrack Agent]   拦截: " + STANDARD_ENGINE_VALVE + "#invoke");
        System.out.println("[LightTrack Agent] ── Kafka 链路 ──");
        System.out.println("[LightTrack Agent]   拦截: " + KAFKA_PRODUCER + "#send (生产者注入)");
        System.out.println("[LightTrack Agent]   拦截: " + KAFKA_LISTENER_ADAPTER + "#onMessage (消费者提取)");
        System.out.println("[LightTrack Agent] ── RabbitMQ 链路 ──");
        System.out.println("[LightTrack Agent]   拦截: " + RABBIT_TEMPLATE + "#send (生产者注入)");
        System.out.println("[LightTrack Agent]   拦截: " + RABBIT_LISTENER_ADAPTER + "#onMessage (消费者提取)");
        System.out.println("========================================");
    }
}
