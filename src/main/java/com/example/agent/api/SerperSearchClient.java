package com.example.agent.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.Map;

/** 阅读 4：供应商适配层。Serper 提供 Google 搜索结果，不是 Google 官方 API。 */
@Component
public class SerperSearchClient {
    // URL 由服务端控制，模型只提供搜索词，不能让模型任意指定 URL（防 SSRF）。
    private static final URI ENDPOINT = URI.create("https://google.serper.dev/search");
    private final ApiHttpClient http;
    private final String apiKey;

    public SerperSearchClient(ApiHttpClient http, @Value("${tools.search.api-key:}") String apiKey) {
        this.http = http;
        this.apiKey = apiKey;
    }

    public SearchData search(String query, int limit) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ExternalApiException("NOT_CONFIGURED", "搜索服务未配置：请在服务端设置 SERPER_API_KEY 并重启。");
        }
        // POST JSON：查询词放 body；密钥只放请求头，不进入 URL、Tool 参数或聊天历史。
        SearchResponse response = http.post(ENDPOINT, Map.of("X-API-KEY", apiKey),
                Map.of("q", query, "num", limit, "hl", "zh-cn"), SearchResponse.class);
        if (response.organic() == null) throw ExternalApiException.invalidResponse();
        List<SearchHit> hits = response.organic().stream().limit(limit).map(hit -> {
            if (hit == null || hit.title() == null || hit.link() == null || !isWebLink(hit.link())) {
                throw ExternalApiException.invalidResponse();
            }
            // 控制模型上下文大小。保留完整链接用于核对来源，不抓取链接内容。
            return new SearchHit(clip(hit.title(), 200), hit.link(), clip(hit.snippet(), 800));
        }).toList();
        return new SearchData("Serper / Google Search", query, hits);
    }

    private static boolean isWebLink(String value) {
        try {
            URI uri = URI.create(value);
            return value.length() <= 2048 && uri.getHost() != null && uri.getUserInfo() == null
                    && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String clip(String text, int max) {
        return text == null ? "" : text.substring(0, Math.min(text.length(), max));
    }

    // DTO（数据传输对象）只映射需要的字段。供应商加字段不会破坏我们的解析。
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SearchResponse(List<SearchHit> organic) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SearchHit(String title, String link, String snippet) {}
    public record SearchData(String source, String query, List<SearchHit> results) {}
}
