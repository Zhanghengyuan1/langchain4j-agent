package com.example.agent.controller;

import com.example.agent.core.AgentService;
import com.example.agent.model.ModelRegistry;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * REST 控制器 — 暴露 Agent 的 HTTP 接口
 *
 * SSE 阅读路线：
 * 1. index.html 的 sendMessage() / sendSse() 发起 POST 请求；
 * 2. 本类 stream() 接收请求，调用 AgentService.chatStream()；
 * 3. AgentService 通过 StreamingChatModel 获取逐块文本；
 * 4. 本类把文本包装为 SSE 事件，index.html 的 consumeBlock() 显示出来。
 *
 * 旧的 POST /api/chat 是一次性返回完整 JSON 的接口；/reset 清空对话历史。
 */
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/chat")
public class AgentController {

    private final AgentService agentService;
    private final ModelRegistry modelRegistry;
    private final ExecutorService streamExecutor;

    public AgentController(AgentService agentService, ModelRegistry modelRegistry, ExecutorService streamExecutor) {
        this.agentService = agentService;
        this.modelRegistry = modelRegistry;
        this.streamExecutor = streamExecutor;
    }

    /** 页面加载时获取可选模型，以及是否已配置所需的 API Key。 */
    @GetMapping("/models")
    public List<ModelRegistry.ModelOption> models() {
        return modelRegistry.options();
    }

    /** 阅读顺序 2/6：SSE 入口。返回 SseEmitter 后，后台线程仍可不断向浏览器发送事件。 */
    @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody Map<String, String> request) {
        SseEmitter emitter = new SseEmitter(300_000L);
        String message = request.get("message");
        String modelId = request.get("model");
        // 模型生成可能很久：交给线程池，先把可持续写入的 emitter 返回给 HTTP 请求。
        streamExecutor.execute(() -> {
            try {
                if (message == null || message.isBlank()) {
                    sendEvent(emitter, "error", "请输入消息内容");
                } else {
                    // AgentService 每产生一个文本块，就通过回调立即发送 delta 事件。
                    agentService.chatStream(message, modelId, token -> {
                        try {
                            sendEvent(emitter, "delta", token);
                        } catch (IOException e) {
                            throw new IllegalStateException("SSE 连接已断开", e);
                        }
                    });
                    // chatStream 返回说明本次 Agent 循环已结束。
                    sendEvent(emitter, "done", "");
                }
                emitter.complete();
            } catch (Exception e) {
                try {
                    sendEvent(emitter, "error", e.getMessage());
                } catch (IOException ignored) {
                    // 客户端已断开，无法再发送错误事件。
                }
                emitter.complete();
            }
        });
        return emitter;
    }

    private void sendEvent(SseEmitter emitter, String type, String content) throws IOException {
        // 浏览器按 event 类型区分 delta / done / error；data 中保存 JSON 文本。
        emitter.send(SseEmitter.event().name(type).data(Map.of("content", content == null ? "" : content)));
    }

    /**
     * 与 Agent 对话
     *
     * 请求示例:
     *   POST /api/chat
     *   Content-Type: application/json
     *   {"message": "今天北京天气怎么样？"}
     *
     * 响应示例:
     *   {"reply": "北京今天晴天，气温22°C..."}
     */
    @PostMapping
    public Map<String, String> chat(@RequestBody Map<String, String> request) {
        String userMessage = request.get("message");
        if (userMessage == null || userMessage.isBlank()) {
            return Map.of("reply", "请输入消息内容");
        }
        try {
            String reply = agentService.chat(userMessage, request.get("model"));
            if (reply == null || reply.isBlank()) {
                reply = "（模型未返回文本内容）";
            }
            return Map.of("reply", reply);
        } catch (Exception e) {
            e.printStackTrace();
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            return Map.of("reply", "【后端异常】" + e.getClass().getSimpleName() + ": " + msg);
        }
    }

    /**
     * 重置对话
     */
    @PostMapping("/reset")
    public Map<String, String> reset() {
        agentService.resetConversation();
        return Map.of("status", "对话已重置");
    }
}
