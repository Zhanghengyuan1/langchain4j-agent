package com.example.agent.model;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

import java.util.List;
import java.util.Map;

/**
 * 前端只传模型 ID；服务端在这里决定实际使用哪个模型实例。
 * 每条请求独立选择模型，不修改全局默认值。
 */
public class ModelRegistry {

    public static final String DEFAULT_MODEL = "qwen";

    public record ModelPair(ChatModel chat, StreamingChatModel streaming) {}
    public record ModelOption(String id, String label, boolean available) {}

    private final Map<String, ModelPair> availableModels;
    private final List<ModelOption> options;

    public ModelRegistry(Map<String, ModelPair> availableModels) {
        this.availableModels = Map.copyOf(availableModels);
        this.options = List.of(
                new ModelOption(DEFAULT_MODEL, "本地 Qwen", true),
                new ModelOption("deepseek-flash", "DeepSeek Flash", availableModels.containsKey("deepseek-flash")),
                new ModelOption("deepseek-v4-pro", "DeepSeek V4 Pro", availableModels.containsKey("deepseek-v4-pro"))
        );
    }

    public List<ModelOption> options() {
        return options;
    }

    public ChatModel chatModel(String modelId) {
        return require(modelId).chat();
    }

    public StreamingChatModel streamingModel(String modelId) {
        return require(modelId).streaming();
    }

    private ModelPair require(String modelId) {
        String id = modelId == null || modelId.isBlank() ? DEFAULT_MODEL : modelId;
        ModelPair pair = availableModels.get(id);
        if (pair != null) return pair;
        if (id.equals("deepseek-flash") || id.equals("deepseek-v4-pro")) {
            throw new IllegalStateException("DeepSeek 尚未启用：请配置 DEEPSEEK_API_KEY 或 config/local-secrets.yml 并重启应用");
        }
        throw new IllegalArgumentException("不支持的模型: " + id);
    }
}
