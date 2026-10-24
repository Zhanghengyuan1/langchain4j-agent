package com.example.agent.core;

import com.example.agent.api.OpenMeteoWeatherClient;
import com.example.agent.api.SerperSearchClient;
import com.example.agent.message.ChatMemory;
import com.example.agent.model.ModelRegistry;
import com.example.agent.tool.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 使用真实 @Tool 扫描、执行器和消息历史，仅替换模型与外部服务，验证完整工具回路。 */
class AgentToolIntegrationTest {
    private ChatModel model;
    private StreamingChatModel streaming;
    private SerperSearchClient client;
    private ChatMemory memory;
    private AgentService agent;

    @BeforeEach
    void setUp() {
        model = mock(ChatModel.class);
        streaming = mock(StreamingChatModel.class);
        client = mock(SerperSearchClient.class);
        memory = new ChatMemory();
        ModelRegistry registry = new ModelRegistry(Map.of("qwen", new ModelRegistry.ModelPair(model, streaming)));
        agent = new AgentService(registry, memory, new DateTimeTool(), new CalculatorTool(),
                new WeatherTool(mock(OpenMeteoWeatherClient.class)), new GoogleSearchTool(client));
        ReflectionTestUtils.setField(agent, "maxIterations", 3);
        ReflectionTestUtils.setField(agent, "maxToolCalls", 1);
        ReflectionTestUtils.setField(agent, "systemPrompt", "test");
        agent.init();
        when(client.search("Java", 1)).thenReturn(new SerperSearchClient.SearchData("test", "Java",
                List.of(new SerperSearchClient.SearchHit("Java docs", "https://example.com", "test snippet"))));
    }

    @Test
    void synchronousLoopRegistersSearchExecutesAndFeedsJsonBackToModel() {
        when(model.chat(any(ChatRequest.class))).thenReturn(response(AiMessage.from(searchRequest())), response(AiMessage.from("完成")));
        assertThat(agent.chat("搜索 Java", "qwen")).isEqualTo("完成");
        var captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model, times(2)).chat(captor.capture());
        assertThat(captor.getAllValues().get(0).toolSpecifications()).extracting("name")
                .contains("googleSearch", "getWeather");
        assertThat(toolResults()).hasSize(1);
        assertThat(toolResults().get(0).id()).isEqualTo("search-1");
        assertThat(toolResults().get(0).text()).contains("\"success\":true", "https://example.com");
        assertThat(captor.getAllValues().get(1).messages()).anyMatch(ToolExecutionResultMessage.class::isInstance);
    }

    @Test
    void streamingLoopAlsoReturnsToolResultBeforeFinalTokens() {
        AtomicInteger rounds = new AtomicInteger();
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            if (rounds.getAndIncrement() == 0) {
                handler.onCompleteResponse(response(AiMessage.from(searchRequest())));
            } else {
                handler.onPartialResponse("完成");
                handler.onCompleteResponse(response(AiMessage.from("完成")));
            }
            return null;
        }).when(streaming).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        StringBuilder tokens = new StringBuilder();
        agent.chatStream("搜索 Java", "qwen", tokens::append);
        assertThat(tokens.toString()).isEqualTo("完成");
        assertThat(toolResults().get(0).text()).contains("\"success\":true");
        verify(client).search("Java", 1);
    }

    @Test
    void malformedArgumentsReturnSafeErrorInsteadOfBreakingConversation() {
        ToolExecutionRequest bad = ToolExecutionRequest.builder().id("bad-1").name("googleSearch")
                .arguments("not-json-private").build();
        when(model.chat(any(ChatRequest.class))).thenReturn(response(AiMessage.from(bad)), response(AiMessage.from("调用失败")));
        assertThat(agent.chat("搜索", "qwen")).isEqualTo("调用失败");
        assertThat(toolResults().get(0).text()).contains("TOOL_EXECUTION_FAILED").doesNotContain("not-json-private");
        verifyNoInteractions(client);
    }

    @Test
    void budgetLimitsActualExternalCallsButPairsEveryToolRequestWithAResult() {
        ToolExecutionRequest second = ToolExecutionRequest.builder().id("search-2").name("googleSearch")
                .arguments("{\"query\":\"Java\",\"maxResults\":1}").build();
        when(model.chat(any(ChatRequest.class))).thenReturn(response(AiMessage.from(searchRequest(), second)), response(AiMessage.from("完成")));
        agent.chat("搜索", "qwen");
        verify(client, times(1)).search("Java", 1);
        assertThat(toolResults()).hasSize(2);
        assertThat(toolResults().get(1).id()).isEqualTo("search-2");
        assertThat(toolResults().get(1).text()).contains("TOOL_BUDGET_EXCEEDED");
    }

    private List<ToolExecutionResultMessage> toolResults() {
        return memory.getMessages().stream().filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast).toList();
    }

    private ToolExecutionRequest searchRequest() {
        return ToolExecutionRequest.builder().id("search-1").name("googleSearch")
                .arguments("{\"query\":\"Java\",\"maxResults\":1}").build();
    }

    private ChatResponse response(AiMessage message) { return ChatResponse.builder().aiMessage(message).build(); }
}
