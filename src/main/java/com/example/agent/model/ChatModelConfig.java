package com.example.agent.model;

import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * ============================================
 * 模型封装 (Model)
 * ============================================
 * 将 LLM 的连接配置封装为 Spring Bean。
 * 这里使用 OpenAI 兼容接口连接本地 Qwen 或 DeepSeek。
 * modelRegistry() 负责把普通模型与流式模型按 ID 配对，供 AgentService 每次请求选择。
 */
@Configuration
public class ChatModelConfig {

    @Value("${llm.base-url}")
    private String baseUrl;

    @Value("${llm.api-key}")
    private String apiKey;

    @Value("${llm.model-name}")
    private String modelName;

    @Value("${llm.max-tokens}")
    private int maxTokens;

    @Value("${llm.temperature}")
    private double temperature;

    @Value("${llm.log-payloads:false}")
    private boolean logPayloads;

    @Value("${llm.deepseek.base-url}")
    private String deepseekBaseUrl;

    @Value("${llm.deepseek.api-key:}")
    private String deepseekApiKey;

    /**
     * 创建 ChatModel Bean
     *
     * 关键配置说明：
     *  vLLM / FastAPI 默认使用 uvicorn，对 Java 原生 HttpClient 的 HTTP/2 协议支持不完善，
     *  在 HTTP/2 下解析 POST body 时会丢失请求体，报：
     *    "1 validation error: {'type': 'missing', 'loc': ('body',), 'msg': 'Field required', 'input': None}"
     *  因此这里显式配置底层使用 HTTP/1.1 协议，彻底解决兼容性问题。
     */
    @Bean
    public ChatModel chatModel() {
        // 1. 显式指定 HTTP/1.1 协议
        HttpClient.Builder jdkHttpClientBuilder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(60));

        // 2. 封装为 LangChain4J 的 JdkHttpClientBuilder，并设置充足的读取超时（27B模型生成较慢）
        JdkHttpClientBuilder httpClientBuilder = JdkHttpClient.builder()
                .httpClientBuilder(jdkHttpClientBuilder)
                .readTimeout(Duration.ofSeconds(180));

        // 3. 构建 OpenAiChatModel
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .httpClientBuilder(httpClientBuilder)
                .logRequests(logPayloads)   // 默认关闭完整报文日志，避免泄露用户查询和工具返回内容
                .logResponses(logPayloads)
                .build();
    }

    /** 阅读顺序 4/6：Spring 创建流式模型 Bean，AgentService 注入后调用它。 */
    @Bean
    public StreamingChatModel streamingChatModel() {
        // 与普通模型使用同一地址和参数，但模型类型支持逐块回调。
        HttpClient.Builder jdkHttpClientBuilder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(60));

        JdkHttpClientBuilder httpClientBuilder = JdkHttpClient.builder()
                .httpClientBuilder(jdkHttpClientBuilder)
                .readTimeout(Duration.ofSeconds(180));

        return OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .httpClientBuilder(httpClientBuilder)
                .build();
    }

    /** 把本地 Qwen 和可用的 DeepSeek 型号放进同一个选择表。 */
    @Bean
    public ModelRegistry modelRegistry(ChatModel chatModel, StreamingChatModel streamingChatModel) {
        Map<String, ModelRegistry.ModelPair> models = new HashMap<>();
        models.put(ModelRegistry.DEFAULT_MODEL, new ModelRegistry.ModelPair(chatModel, streamingChatModel));

        // 没有 Key 时照常启动本地 Qwen；DeepSeek 选项会在页面上显示为不可用。
        if (!deepseekApiKey.isBlank()) {
            addDeepSeek(models, "deepseek-flash");
            addDeepSeek(models, "deepseek-v4-pro");
        }
        return new ModelRegistry(models);
    }

    private void addDeepSeek(Map<String, ModelRegistry.ModelPair> models, String modelId) {
        ChatModel chat = OpenAiChatModel.builder()
                .baseUrl(deepseekBaseUrl)
                .apiKey(deepseekApiKey)
                .modelName(modelId)
                .timeout(Duration.ofSeconds(180))
                .build();
        StreamingChatModel streaming = OpenAiStreamingChatModel.builder()
                .baseUrl(deepseekBaseUrl)
                .apiKey(deepseekApiKey)
                .modelName(modelId)
                .timeout(Duration.ofSeconds(180))
                .build();
        models.put(modelId, new ModelRegistry.ModelPair(chat, streaming));
    }
}
