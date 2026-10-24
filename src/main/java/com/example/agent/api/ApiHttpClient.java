package com.example.agent.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.util.Map;

/**
 * 阅读 5：HTTP 公共边界。把协议错误翻译为业务能理解的错误码。
 * 不自动重试：搜索可能按请求计费；重试策略应在有总预算、退避和幂等判断后再增加。
 */
@Component
public class ApiHttpClient {
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;
    private final RestClient restClient;
    private final ObjectMapper mapper;

    public ApiHttpClient(@Qualifier("externalApiRestClient") RestClient restClient, ObjectMapper mapper) {
        this.restClient = restClient;
        this.mapper = mapper;
    }

    public <T> T get(URI uri, Class<T> type) {
        return read(restClient.get().uri(uri).accept(MediaType.APPLICATION_JSON), type);
    }

    public <T> T post(URI uri, Map<String, String> headers, Object body, Class<T> type) {
        return read(restClient.post().uri(uri).headers(h -> headers.forEach(h::set))
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).body(body), type);
    }

    private <T> T read(RestClient.RequestHeadersSpec<?> request, Class<T> type) {
        try {
            // exchange 让我们在读取 body 前检查状态和大小；不会把供应商的错误原文暴露给模型。
            return request.exchange((req, response) -> {
                int status = response.getStatusCode().value();
                if (status == 401 || status == 403) {
                    throw new ExternalApiException("UPSTREAM_AUTH", "外部服务鉴权失败，请检查服务端 API Key 或权限。");
                }
                if (status == 429) {
                    throw new ExternalApiException("RATE_LIMITED", "外部服务限流或额度不足，请稍后再试；不要连续重复调用。");
                }
                if (status >= 500) {
                    throw new ExternalApiException("UPSTREAM_UNAVAILABLE", "外部服务暂时不可用，请稍后再试。");
                }
                if (status < 200 || status >= 300) {
                    throw new ExternalApiException("UPSTREAM_REJECTED", "外部服务拒绝请求，请检查服务配置或输入。");
                }
                byte[] bytes = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) {
                    throw new ExternalApiException("RESPONSE_TOO_LARGE", "外部服务响应超过大小限制。");
                }
                try {
                    T result = mapper.readValue(bytes, type);
                    if (result == null) throw ExternalApiException.invalidResponse();
                    return result;
                } catch (JsonProcessingException e) {
                    throw ExternalApiException.invalidResponse();
                }
            });
        } catch (ResourceAccessException e) {
            // DNS、连接失败和超时均可能进入此分支，不能把所有网络错误都叫作“超时”。
            throw new ExternalApiException("NETWORK_ERROR", "外部服务连接失败或超时，请检查网络后再试。");
        } catch (RestClientException e) {
            throw ExternalApiException.invalidResponse();
        }
    }
}
