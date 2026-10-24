package com.example.agent.core;

import com.example.agent.message.ChatMemory;
import com.example.agent.model.ModelRegistry;
import com.example.agent.tool.CalculatorTool;
import com.example.agent.tool.DateTimeTool;
import com.example.agent.tool.WeatherTool;
import com.example.agent.tool.GoogleSearchTool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * ============================================
 * Agent 循环 (Agent Loop)
 * ============================================
 * 这是整个 Agent 的核心 —— ReAct 循环（Reason + Act）。
 * 阅读 SSE 主线时直接跳到 chatStream()；chat() 是旧的一次性返回版本。
 *
 * Agent 循环的工作流程：
 *
 *   用户输入
 *      │
 *      ▼
 *   ┌─────────────────────────────┐
 *   │  1. 将用户消息加入历史        │
 *   │  2. 将消息历史 + 工具列表      │
 *   │     发送给 LLM               │
 *   └─────────────┬───────────────┘
 *                  │
 *                  ▼
 *          LLM 返回 AiMessage
 *                  │
 *          ┌───────┴────────┐
 *          │                │
 *     有工具调用?        无工具调用
 *          │           (纯文本回复)
 *          ▼                │
 *   ┌──────────────┐       │
 *   │ 执行工具      │       │
 *   │ 将结果加入历史 │       │
 *   └──────┬───────┘       │
 *          │               │
 *          ▼               │
 *    回到步骤 2             │
 *   (再次调用 LLM)         │
 *                          ▼
 *                   返回最终文本回复
 *
 * 关键概念：
 *   - ToolSpecification: 工具的元数据描述（名称、描述、参数 Schema）
 *   - ToolExecutionRequest: LLM 返回的工具调用请求（工具名 + 参数）
 *   - ToolExecutor: 实际执行工具方法的执行器
 *   - 迭代上限: 防止 LLM 陷入无限工具调用循环
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final ModelRegistry modelRegistry;
    private final ChatMemory chatMemory;

    // 所有工具的 Bean 实例
    private final DateTimeTool dateTimeTool;
    private final CalculatorTool calculatorTool;
    private final WeatherTool weatherTool;
    private final GoogleSearchTool googleSearchTool;

    // 每个用户请求最多实际执行这些工具调用；maxIterations 只限制模型轮数，两者不相同。
    @Value("${agent.max-tool-calls:6}")
    private int maxToolCalls;

    @Value("${agent.max-iterations}")
    private int maxIterations;

    @Value("${agent.system-prompt}")
    private String systemPrompt;

    // ToolSpecification 列表 — 告诉 LLM 有哪些工具可用
    private List<ToolSpecification> toolSpecifications;

    // 工具名称 → 执行器的映射
    private Map<String, DefaultToolExecutor> toolExecutors;

    public AgentService(ModelRegistry modelRegistry,
                        ChatMemory chatMemory,
                        DateTimeTool dateTimeTool,
                        CalculatorTool calculatorTool,
                        WeatherTool weatherTool,
                        GoogleSearchTool googleSearchTool) {
        this.modelRegistry = modelRegistry;
        this.chatMemory = chatMemory;
        this.dateTimeTool = dateTimeTool;
        this.calculatorTool = calculatorTool;
        this.weatherTool = weatherTool;
        this.googleSearchTool = googleSearchTool;
    }

    /**
     * 初始化：注册所有工具
     *
     * ToolSpecifications.toolSpecificationsFrom() 会扫描对象中所有
     * 带 @Tool 注解的方法，提取出：
     *   - 方法名 → toolName
     *   - @Tool 的 value → description
     *   - 方法参数 + @P 注解 → parameters JSON Schema
     */
    @PostConstruct
    public void init() {
        toolSpecifications = new ArrayList<>();
        toolExecutors = new HashMap<>();

        // 注册每个工具 Bean
        registerTools(dateTimeTool);
        registerTools(calculatorTool);
        registerTools(weatherTool);
        registerTools(googleSearchTool);

        // 设置系统提示词
        chatMemory.addSystemMessage(systemPrompt);

        log.info("Agent 初始化完成, 已注册 {} 个工具: {}",
                toolSpecifications.size(),
                toolSpecifications.stream().map(ToolSpecification::name).toList());
    }

    /**
     * 注册一个工具对象中的所有 @Tool 方法
     */
    private void registerTools(Object toolBean) {
        // 1. 从 @Tool 注解提取工具规范
        List<ToolSpecification> specs = ToolSpecifications.toolSpecificationsFrom(toolBean);
        toolSpecifications.addAll(specs);

        // 2. 为每个工具方法创建执行器
        for (var method : toolBean.getClass().getDeclaredMethods()) {
            if (method.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class)) {
                // DefaultToolExecutor 持有 toolBean 引用和 method 引用
                // 当 LLM 请求调用时，它会反射调用对应的方法
                DefaultToolExecutor executor = new DefaultToolExecutor(toolBean, method);
                toolExecutors.put(method.getName(), executor);
            }
        }
    }

    /**
     * ============================================
     *  Agent 循环的入口 —— 处理用户输入
     * ============================================
     *
     * 这是 Agent 模式的精髓：
     * 不像普通聊天那样一问一答，而是一个循环：
     *   LLM 可能需要多次工具调用后才能给出最终回答。
     */
    public synchronized String chat(String userMessage, String modelId) {
        log.info("开始处理聊天请求");
        var chatModel = modelRegistry.chatModel(modelId);

        // 步骤 1: 将用户消息加入对话历史
        chatMemory.addUserMessage(userMessage);
        int toolCalls = 0;

        // 步骤 2: 开始 Agent 循环
        for (int iteration = 0; iteration < maxIterations; iteration++) {
            log.info("----- Agent 循环 第 {} 轮 -----", iteration + 1);

            // 步骤 2.1: 构建请求 — 包含完整消息历史 + 工具列表
            ChatRequest chatRequest = ChatRequest.builder()
                    .messages(chatMemory.getMessages())
                    .toolSpecifications(toolSpecifications) // 告诉 LLM 有哪些工具可用
                    .build();

            // 步骤 2.2: 调用 LLM
            ChatResponse response = chatModel.chat(chatRequest);
            AiMessage aiMessage = response.aiMessage();

            // 步骤 2.3: 将 AI 回复加入历史
            chatMemory.addAiMessage(aiMessage);

            // 步骤 2.4: 判断 —— AI 是要调用工具还是直接回复？
            if (!aiMessage.hasToolExecutionRequests()) {
                // ✅ 没有工具调用 → LLM 给出了最终文本回复 → 结束循环
                String finalAnswer = aiMessage.text();
                if (finalAnswer == null || finalAnswer.isBlank()) {
                    finalAnswer = "（模型已处理完成，但未返回文本内容）";
                }
                log.info("聊天请求完成");
                return finalAnswer;
            }

            // 步骤 2.5: 有工具调用请求 → 逐个执行工具
            List<ToolExecutionRequest> toolRequests = aiMessage.toolExecutionRequests();
            log.info("LLM 请求调用 {} 个工具", toolRequests.size());

            for (ToolExecutionRequest toolRequest : toolRequests) {
                // 同步和流式入口使用同一条执行路径，防止一边能处理异常、一边直接断流。
                String result = executeTool(toolRequest, ++toolCalls);

                // 步骤 2.6: 将工具执行结果加入消息历史
                // 这样 LLM 在下一轮就能看到工具的返回值
                chatMemory.addToolResult(
                        ToolExecutionResultMessage.from(toolRequest, result));
            }

            // 回到循环顶部 → 带着工具结果再次调用 LLM
            log.info("  工具执行完毕，将结果发回 LLM...");
        }

        // 超过最大迭代次数，强制终止
        log.warn("Agent 循环达到最大迭代次数 ({})，强制终止", maxIterations);
        return "抱歉，处理过程过于复杂，请尝试简化您的问题。";
    }

    /**
     * 阅读顺序 3/6：SSE 与 WebSocket 共用的 Agent 核心。
     * 真正的模型流式生成；先把文本块交给 onToken，再等本轮完整响应来判断是否调用工具。
     * synchronized 保护当前项目共用的 ChatMemory，避免两次请求交错写入历史。
     */
    public synchronized void chatStream(String userMessage, String modelId, Consumer<String> onToken) {
        // 先验证模型 ID，再写入消息历史，避免错误请求污染对话。
        StreamingChatModel streamingChatModel = modelRegistry.streamingModel(modelId);
        // 用户消息进入历史；下一轮模型请求会带上它。
        chatMemory.addUserMessage(userMessage);
        int toolCalls = 0;

        for (int iteration = 0; iteration < maxIterations; iteration++) {
            // 每一轮都把目前的消息历史和可用工具说明发给模型。
            ChatRequest request = ChatRequest.builder()
                    .messages(chatMemory.getMessages())
                    .toolSpecifications(toolSpecifications)
                    .build();

            CompletableFuture<ChatResponse> completion = new CompletableFuture<>();
            // ModelRegistry 根据本次请求的 modelId 选出了对应的流式模型（阅读顺序 4/6）。
            streamingChatModel.chat(request, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String token) {
                    // 逐块回调：经 Controller 的 SSE emitter 发给浏览器，不等待整段回答完成。
                    try {
                        onToken.accept(token);
                    } catch (RuntimeException e) {
                        completion.completeExceptionally(e);
                    }
                }

                @Override
                public void onCompleteResponse(ChatResponse response) {
                    // 完整响应还包含工具调用信息；下面的 completion.join() 等待它。
                    completion.complete(response);
                }

                @Override
                public void onError(Throwable error) {
                    completion.completeExceptionally(error);
                }
            });

            ChatResponse response;
            try {
                response = completion.join();
            } catch (CompletionException e) {
                throw new IllegalStateException("模型流式生成失败", e.getCause());
            }

            AiMessage aiMessage = response.aiMessage();
            // 阅读顺序 5/6：ChatMemory 保存本轮回复，供后续轮次继续使用。
            chatMemory.addAiMessage(aiMessage);
            if (!aiMessage.hasToolExecutionRequests()) {
                // 没有工具请求：回答已完成，返回 Controller，由它发送 done。
                if (aiMessage.text() == null || aiMessage.text().isBlank()) {
                    onToken.accept("（模型已处理完成，但未返回文本内容）");
                }
                return;
            }

            for (ToolExecutionRequest toolRequest : aiMessage.toolExecutionRequests()) {
                // 有工具请求：执行工具并保存结果，随后回到循环顶部再次询问模型。
                String result = executeTool(toolRequest, ++toolCalls);
                chatMemory.addToolResult(ToolExecutionResultMessage.from(toolRequest, result));
            }
        }

        onToken.accept("抱歉，处理过程过于复杂，请尝试简化您的问题。");
    }

    /**
     * Tool + API 阅读 6：模型返回名字/JSON 参数 → 执行 Java 方法 → JSON 结果回填历史。
     * API 的预期失败由 ToolResult 表达；这里兜底处理反射、参数解析等未预期异常。
     * 每个请求都得到结果消息，保留 tool_call_id 配对，下一轮模型才能继续正常回答。
     */
    private String executeTool(ToolExecutionRequest request, int callNumber) {
        if (callNumber > maxToolCalls) {
            return "{\"success\":false,\"code\":\"TOOL_BUDGET_EXCEEDED\",\"message\":\"本次工具调用次数已用完，请根据已有结果回答，不要继续调用工具。\",\"data\":null}";
        }
        DefaultToolExecutor executor = toolExecutors.get(request.name());
        if (executor == null) {
            return "{\"success\":false,\"code\":\"UNKNOWN_TOOL\",\"message\":\"工具不存在，请使用已提供的工具。\",\"data\":null}";
        }
        long started = System.nanoTime();
        try {
            return executor.execute(request, null);
        } catch (RuntimeException e) {
            // 不记录异常 message/堆栈：第三方异常可能包含请求头或 URL 参数。
            log.warn("工具执行异常 tool={} type={}", request.name(), e.getClass().getSimpleName());
            return "{\"success\":false,\"code\":\"TOOL_EXECUTION_FAILED\",\"message\":\"工具执行失败，请检查工具参数；不要编造结果或连续重复请求。\",\"data\":null}";
        } finally {
            // 仅记录已注册工具名与耗时，默认不写搜索词、完整结果或密钥。
            log.info("工具调用 tool={} durationMs={}", request.name(), (System.nanoTime() - started) / 1_000_000);
        }
    }

    /** 重置对话，清空历史记录。 */
    public synchronized void resetConversation() {
        chatMemory.clear();
        chatMemory.addSystemMessage(systemPrompt);
        log.info("对话已重置");
    }
}
