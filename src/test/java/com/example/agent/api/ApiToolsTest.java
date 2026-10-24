package com.example.agent.api;

import com.example.agent.tool.GoogleSearchTool;
import com.example.agent.tool.WeatherTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * 阅读 7：在 HTTP 边界拦截真实 RestClient 请求，验证契约而不是只 mock 业务方法。
 * 这些测试不访问公网，不需要 Serper/LLM Key，也不消耗搜索额度。
 */
class ApiToolsTest {
    private MockRestServiceServer server;
    private ApiHttpClient http;
    private GoogleSearchTool search;
    private WeatherTool weather;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        http = new ApiHttpClient(builder.build(), new ObjectMapper());
        search = new GoogleSearchTool(new SerperSearchClient(http, "test-only-key"));
        weather = new WeatherTool(new OpenMeteoWeatherClient(http));
    }

    @Test
    void searchSendsPostJsonAndHeaderThenTrimsResult() {
        server.expect(requestTo("https://google.serper.dev/search"))
                .andExpect(method(HttpMethod.POST)).andExpect(header("X-API-KEY", "test-only-key"))
                .andExpect(content().json("""
                        {"q":"Java + 中文 & API", "num":1, "hl":"zh-cn"}
                        """))
                .andRespond(withSuccess("""
                        {"organic":[
                          {"title":"官方文档", "link":"https://example.com/docs", "snippet":"%s", "extra":"ignored"},
                          {"title":"第二项", "link":"https://example.com/2"}], "credits":1}
                        """.formatted("文".repeat(900)), MediaType.APPLICATION_JSON));
        var result = search.googleSearch(" Java + 中文 & API ", 1);
        assertThat(result.success()).isTrue();
        assertThat(result.data().results()).hasSize(1);
        assertThat(result.data().results().get(0).snippet()).hasSize(800);
        server.verify();
    }

    @Test
    void invalidArgumentsDoNotMakeHttpRequests() {
        assertThat(search.googleSearch(null, 3).code()).isEqualTo("INVALID_ARGUMENT");
        assertThat(search.googleSearch(" ", 3).code()).isEqualTo("INVALID_ARGUMENT");
        assertThat(search.googleSearch("x".repeat(201), 3).code()).isEqualTo("INVALID_ARGUMENT");
        assertThat(search.googleSearch("Java", 0).code()).isEqualTo("INVALID_ARGUMENT");
        assertThat(search.googleSearch("Java", 6).code()).isEqualTo("INVALID_ARGUMENT");
        assertThat(weather.getWeather(null).code()).isEqualTo("INVALID_ARGUMENT");
        assertThat(weather.getWeather(" ").code()).isEqualTo("INVALID_ARGUMENT");
        server.verify();
    }

    @Test
    void missingKeyDoesNotCallProviderOrPretendToSucceed() {
        var tool = new GoogleSearchTool(new SerperSearchClient(http, ""));
        var result = tool.googleSearch("Java", 3);
        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("NOT_CONFIGURED");
        assertThat(result.data()).isNull();
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({"401,UPSTREAM_AUTH", "403,UPSTREAM_AUTH", "429,RATE_LIMITED",
            "500,UPSTREAM_UNAVAILABLE", "503,UPSTREAM_UNAVAILABLE", "400,UPSTREAM_REJECTED", "302,UPSTREAM_REJECTED"})
    void mapsHttpErrorsWithoutLeakingProviderBodyOrRetrying(int status, String code) {
        server.expect(requestTo("https://google.serper.dev/search"))
                .andRespond(withStatus(HttpStatusCode.valueOf(status)).body("private-provider-debug-and-key"));
        var result = search.googleSearch("Java", 3);
        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo(code);
        assertThat(result.message()).doesNotContain("private-provider", "test-only-key");
        server.verify(); // 默认恰好一次；额外重试会失败。
    }

    @Test
    void translatesNetworkTimeout() {
        server.expect(requestTo("https://google.serper.dev/search"))
                .andRespond(withException(new SocketTimeoutException("private URL")));
        var result = search.googleSearch("Java", 3);
        assertThat(result.code()).isEqualTo("NETWORK_ERROR");
        assertThat(result.message()).doesNotContain("private URL");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "null", "{}", "{\"organic\":[null]}",
            "{\"organic\":[{\"title\":\"bad\",\"link\":\"javascript:alert(1)\"}]}"})
    void rejectsMalformedSearchResponses(String body) {
        server.expect(requestTo("https://google.serper.dev/search"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThat(search.googleSearch("Java", 3).code()).isEqualTo("INVALID_RESPONSE");
        server.verify();
    }

    @Test
    void acceptsEmptySearchResults() {
        server.expect(requestTo("https://google.serper.dev/search"))
                .andRespond(withSuccess("{\"organic\":[]}", MediaType.APPLICATION_JSON));
        var result = search.googleSearch("unlikely query", 3);
        assertThat(result.success()).isTrue();
        assertThat(result.data().results()).isEmpty();
        server.verify();
    }

    @Test
    void capsUpstreamResponseSize() {
        server.expect(requestTo("https://google.serper.dev/search"))
                .andRespond(withSuccess("x".repeat(512 * 1024 + 1), MediaType.APPLICATION_JSON));
        assertThat(search.googleSearch("Java", 3).code()).isEqualTo("RESPONSE_TOO_LARGE");
        server.verify();
    }

    @Test
    void weatherResolvesCityThenRequestsCurrentDataWithExplicitUnits() {
        expectBeijing();
        server.expect(requestTo(containsString("https://api.open-meteo.com/v1/forecast?")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("latitude", "39.9"))
                .andExpect(queryParam("longitude", "116.4"))
                .andExpect(queryParam("timezone", "auto"))
                .andExpect(queryParam("temperature_unit", "celsius"))
                .andExpect(queryParam("wind_speed_unit", "kmh"))
                .andExpect(queryParam("current", "temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m"))
                .andRespond(withSuccess("""
                        {"timezone":"Asia/Shanghai","current":{
                          "time":"2026-10-19T10:00","temperature_2m":21.5,
                          "relative_humidity_2m":65,"wind_speed_10m":12.3,"weather_code":3}}
                        """, MediaType.APPLICATION_JSON));
        var result = weather.getWeather(" 北京 ");
        assertThat(result.success()).isTrue();
        assertThat(result.data().matchedLocation()).contains("北京", "中国");
        assertThat(result.data().temperatureCelsius()).isEqualTo(21.5);
        assertThat(result.data().windSpeedKmh()).isEqualTo(12.3);
        assertThat(result.data().localTime()).isEqualTo("2026-10-19T10:00");
        assertThat(result.data().timezone()).isEqualTo("Asia/Shanghai");
        server.verify();
    }

    @Test
    void encodesCityAsOneParameter() {
        server.expect(requestTo("https://geocoding-api.open-meteo.com/v1/search?name=A%2BB%20%26%20C&count=5&language=zh&format=json"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThat(weather.getWeather("A+B & C").code()).isEqualTo("CITY_NOT_FOUND");
        server.verify();
    }

    @Test
    void ambiguousCityDoesNotFetchWeatherForAnArbitraryLocation() {
        server.expect(requestTo(containsString("geocoding-api.open-meteo.com")))
                .andRespond(withSuccess("""
                        {"results":[{"name":"Paris","country":"France"},{"name":"Paris","country":"US"}]}
                        """, MediaType.APPLICATION_JSON));
        var result = weather.getWeather("Paris");
        assertThat(result.code()).isEqualTo("AMBIGUOUS_CITY");
        assertThat(result.message()).contains("France", "US");
        server.verify();
    }

    @Test
    void missingTemperatureIsNotReportedAsZero() {
        expectBeijing();
        server.expect(requestTo(containsString("api.open-meteo.com/v1/forecast")))
                .andRespond(withSuccess("""
                        {"timezone":"Asia/Shanghai","current":{"time":"2026-10-19T10:00",
                         "relative_humidity_2m":65,"wind_speed_10m":12.3,"weather_code":3}}
                        """, MediaType.APPLICATION_JSON));
        var result = weather.getWeather("北京");
        assertThat(result.code()).isEqualTo("INVALID_RESPONSE");
        assertThat(result.data()).isNull();
        server.verify();
    }

    private void expectBeijing() {
        server.expect(requestTo("https://geocoding-api.open-meteo.com/v1/search?name=%E5%8C%97%E4%BA%AC&count=5&language=zh&format=json"))
                .andRespond(withSuccess("""
                        {"results":[{"name":"北京","country":"中国","latitude":39.9,"longitude":116.4}]}
                        """, MediaType.APPLICATION_JSON));
    }
}
