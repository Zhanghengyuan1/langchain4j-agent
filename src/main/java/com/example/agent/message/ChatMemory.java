package com.example.agent.message;

import dev.langchain4j.data.message.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ============================================
 * 消息管理 (Messages)
 * ============================================
 * 阅读顺序 5/6：管理 Agent 与 LLM 之间的对话历史。
 * 当前它是 Spring 单例，所有请求共用这份历史；AgentService 用同步方法避免交错写入。
 *
 * LangChain4J 的消息类型体系：
 *
 *   ChatMessage (接口)
 *     ├── SystemMessage      — 系统提示词，设定 AI 的行为和角色
 *     ├── UserMessage        — 用户输入的消息
 *     ├── AiMessage          — AI 的回复 (可能包含文本或工具调用请求)
 *     └── ToolExecutionResultMessage — 工具执行结果，返回给 LLM
 *
 * AiMessage 的两种形态：
 *   1. 纯文本回复:  aiMessage.text() 有值
 *   2. 工具调用:    aiMessage.toolExecutionRequests() 有值
 *      - 包含 toolName, arguments (JSON string)
 *      - Agent 需要执行工具后，将结果作为 ToolExecutionResultMessage 加入历史
 *
 * Agent 循环中的消息流：
 *   [SystemMessage] → [UserMessage] → LLM
 *                                      ↓
 *                              AiMessage(工具调用)
 *                                      ↓
 *                        ToolExecutionResultMessage
 *                                      ↓
 *                                     LLM → AiMessage(文本回复) → 返回用户
 */
@Component
public class ChatMemory {

    private final List<ChatMessage> messages = new ArrayList<>();

    /**
     * 添加系统消息 — 设定 Agent 的角色和行为
     */
    public void addSystemMessage(String content) {
        // 先移除已有的系统消息（只保留一个）
        messages.removeIf(m -> m instanceof SystemMessage);
        messages.add(0, SystemMessage.from(content));
    }

    /**
     * 添加用户消息
     */
    public void addUserMessage(String content) {
        messages.add(UserMessage.from(content));
    }

    /**
     * 添加 AI 回复消息
     * AiMessage 可能包含:
     *   - 纯文本: 直接回答用户
     *   - 工具调用请求: 需要框架执行工具后继续对话
     */
    public void addAiMessage(AiMessage aiMessage) {
        messages.add(aiMessage);
    }

    /**
     * 添加工具执行结果消息
     *
     * @param toolExecutionResultMessage 工具执行后的结果，
     *        包含 toolName 和执行结果的文本
     */
    public void addToolResult(ToolExecutionResultMessage toolExecutionResultMessage) {
        messages.add(toolExecutionResultMessage);
    }

    /**
     * 获取完整的消息历史（不可变视图）
     */
    public List<ChatMessage> getMessages() {
        return Collections.unmodifiableList(messages);
    }

    /**
     * 清空对话历史
     */
    public void clear() {
        messages.clear();
    }

    /**
     * 获取消息数量
     */
    public int size() {
        return messages.size();
    }
}
