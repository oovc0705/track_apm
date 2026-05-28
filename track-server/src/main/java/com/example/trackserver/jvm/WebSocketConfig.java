package com.example.trackserver.jvm;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 配置：注册 /ws/jvm-metrics 端点。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final JvmMetricsBroadcaster broadcaster;

    public WebSocketConfig(JvmMetricsBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new JvmMetricsWebSocketHandler(broadcaster), "/ws/jvm-metrics")
                .setAllowedOrigins("http://localhost:5173");
    }

    private static class JvmMetricsWebSocketHandler implements WebSocketHandler {

        private final JvmMetricsBroadcaster broadcaster;

        JvmMetricsWebSocketHandler(JvmMetricsBroadcaster broadcaster) {
            this.broadcaster = broadcaster;
        }

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            broadcaster.register(session);
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            broadcaster.unregister(session);
        }

        @Override
        public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
            // 前端暂不需要向此通道发消息，忽略即可
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
            broadcaster.unregister(session);
        }

        @Override
        public boolean supportsPartialMessages() {
            return false;
        }
    }
}
