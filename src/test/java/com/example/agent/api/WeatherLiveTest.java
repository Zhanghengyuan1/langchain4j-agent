package com.example.agent.api;

import com.example.agent.tool.WeatherTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** 手动联网冒烟测试，默认跳过；mvn -Dtools.live-weather=true -Dtest=WeatherLiveTest test。 */
@EnabledIfSystemProperty(named = "tools.live-weather", matches = "true")
class WeatherLiveTest {
    @Test
    void queriesRealBeijingWeatherWithoutLlmOrApiKey() {
        var rest = new ExternalApiConfig().externalApiRestClient(RestClient.builder(),
                Duration.ofSeconds(3), Duration.ofSeconds(8));
        var tool = new WeatherTool(new OpenMeteoWeatherClient(new ApiHttpClient(rest, new ObjectMapper())));
        // “北京”还会匹配重庆/四川的同名地点；用行政区限定，验证真实的消歧路径。
        var result = tool.getWeather("北京, 北京市");
        assertThat(result.success()).as("%s: %s", result.code(), result.message()).isTrue();
        assertThat(result.data().matchedLocation()).contains("北京");
        assertThat(result.data().localTime()).isNotBlank();
    }
}
