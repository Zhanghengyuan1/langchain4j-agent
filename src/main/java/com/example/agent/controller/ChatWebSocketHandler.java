package com.example.agent.controller;

import com.example.agent.core.AgentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
// 可选阅读分支：浏览器选择 WebSocket 时从这里进入；
// 核心生成逻辑仍是 AgentService.chatStream()，与 SSE 共用。
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final AgentService agentService;
    private final ExecutorService streamExecutor;
    private final ObjectMapper objectMapper;

    public ChatWebSocketHandler(AgentService agentService,
                                ExecutorService streamExecutor,
                                ObjectMapper objectMapper) {
        this.agentService = agentService;
        this.streamExecutor = streamExecutor;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        session.getAttributes().put("busy", new AtomicBoolean());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage incoming) throws IOException {
        JsonNode payload;
        try {
            payload = objectMapper.readTree(incoming.getPayload());
        } catch (Exception e) {
            send(session, "error", "消息必须是 JSON，例如 {\"message\":\"你好\"}");
            return;
        }

        String message = payload == null ? "" : payload.path("message").asText("");
        String modelId = payload == null ? "" : payload.path("model").asText("");
        if (message.isBlank()) {
            send(session, "error", "请输入消息内容");
            return;
        }

        AtomicBoolean busy = (AtomicBoolean) session.getAttributes().get("busy");
        if (!busy.compareAndSet(false, true)) {
            send(session, "error", "上一条消息仍在生成中");
            return;
        }

        streamExecutor.execute(() -> {
            try {
                agentService.chatStream(message, modelId, token -> {
                    try {
                        send(session, "delta", token);
                    } catch (IOException e) {
                        throw new IllegalStateException("WebSocket 连接已断开", e);
                    }
                });
                send(session, "done", "");
            } catch (Exception e) {
                try {
                    send(session, "error", e.getMessage());
                } catch (IOException ignored) {
                    // 客户端已断开。
                }
            } finally {
                busy.set(false);
            }
        });
    }

    private void send(WebSocketSession session, String type, String content) throws IOException {
        synchronized (session) {
            if (!session.isOpen()) throw new IOException("WebSocket 连接已断开");
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(
                    Map.of("type", type, "content", content == null ? "" : content))));
        }
    }
}
