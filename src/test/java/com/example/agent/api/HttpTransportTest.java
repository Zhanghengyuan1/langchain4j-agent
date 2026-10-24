package com.example.agent.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真正连接本机 HTTP 服务：验证工厂的读取超时和禁用跳转，而不只是模拟异常。 */
class HttpTransportTest {
    @Test
    void timesOutWhenServerSendsHeadersButStallsBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newSingleThreadExecutor();
        CountDownLatch release = new CountDownLatch(1);
        server.setExecutor(executor);
        server.createContext("/slow", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 10);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            ApiHttpClient http = client(Duration.ofMillis(150));
            assertThatThrownBy(() -> http.get(uri(server, "/slow"), Map.class))
                    .isInstanceOfSatisfying(ExternalApiException.class,
                            e -> assertThat(e.code()).isEqualTo("NETWORK_ERROR"));
        } finally {
            release.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void refusesRedirectInsteadOfFollowingIt() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger targetCalls = new AtomicInteger();
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            targetCalls.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            assertThatThrownBy(() -> client(Duration.ofSeconds(1)).get(uri(server, "/redirect"), Map.class))
                    .isInstanceOfSatisfying(ExternalApiException.class,
                            e -> assertThat(e.code()).isEqualTo("UPSTREAM_REJECTED"));
            assertThat(targetCalls).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    private ApiHttpClient client(Duration readTimeout) {
        return new ApiHttpClient(new ExternalApiConfig().externalApiRestClient(RestClient.builder(),
                Duration.ofSeconds(1), readTimeout), new ObjectMapper());
    }

    private URI uri(HttpServer server, String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }
}
