package com.example.trackserver.jvm;

import com.example.trackserver.jvm.dto.JvmMetricsSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * WebSocket 广播器：将 JVM 指标推送给所有已连接的前端客户端。
 */
@Slf4j
@Component
public class JvmMetricsBroadcaster {

    private final CopyOnWriteArrayList<WebSocketSession> sessions = new CopyOnWriteArrayList<>();
    private final ObjectMapper objectMapper;

    public JvmMetricsBroadcaster(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void register(WebSocketSession session) {
        sessions.add(session);
        log.info("WebSocket client connected: {}, total={}", session.getId(), sessions.size());
    }

    public void unregister(WebSocketSession session) {
        sessions.remove(session);
        log.info("WebSocket client disconnected: {}, total={}", session.getId(), sessions.size());
    }

    public void broadcast(JvmMetricsSnapshot snapshot) {
        if (sessions.isEmpty()) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(snapshot);
            TextMessage message = new TextMessage(json);
            for (WebSocketSession session : sessions) {
                if (session.isOpen()) {
                    session.sendMessage(message);
                }
            }
        } catch (IOException e) {
            log.error("Failed to broadcast JVM metrics", e);
        }
    }
}
