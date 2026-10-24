package com.example.agent.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.time.Duration;

/** 阅读 5：统一设置出站 HTTP 超时；复用客户端，而不是每次工具调用都建立客户端。 */
@Configuration
public class ExternalApiConfig {
    @Bean
    public RestClient externalApiRestClient(
            RestClient.Builder builder,
            @Value("${tools.http.connect-timeout:3s}") Duration connectTimeout,
            @Value("${tools.http.read-timeout:8s}") Duration readTimeout) {
        validateTimeout(connectTimeout);
        validateTimeout(readTimeout);
        // 本项目的 Spring 6.1 使用此工厂设置 socket 读取超时，覆盖响应头之后的 body 读取。
        // 只设置 JDK HttpRequest.timeout 不能保证 ofInputStream 的后续 body 读取也有超时。
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String method) throws IOException {
                super.prepareConnection(connection, method);
                // 不跟随跳转，避免固定服务地址把请求/鉴权信息转发到其他站点。
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return builder.requestFactory(factory).build();
    }

    private void validateTimeout(Duration timeout) {
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("tools.http 超时必须大于 0 且不超过 30 秒");
        }
    }
}
