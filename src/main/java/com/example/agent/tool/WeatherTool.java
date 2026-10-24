package com.example.agent.tool;

import com.example.agent.api.ExternalApiException;
import com.example.agent.api.OpenMeteoWeatherClient;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/** 阅读 3：把原来的模拟天气替换为真实 API，保留 getWeather 工具名。 */
@Component
public class WeatherTool {
    private final OpenMeteoWeatherClient client;

    public WeatherTool(OpenMeteoWeatherClient client) { this.client = client; }

    @Tool("查询城市当前天气（Open-Meteo 模型数据，包括温度、湿度、风速），不支持未来或历史天气。"
            + "必须说明返回的匹配地点、当地数据时间和来源。多个地点匹配时请用户补充省份/国家，不要猜测。")
    public ToolResult<OpenMeteoWeatherClient.WeatherData> getWeather(
            @P("城市名，2 到 100 字符，例如北京；可用逗号限定行政区，如 北京, 北京市 或 Paris, France；不传完整问句") String city) {
        if (city == null || city.strip().length() < 2 || city.strip().length() > 100) {
            return ToolResult.failure("INVALID_ARGUMENT", "城市名需要 2 到 100 个字符。");
        }
        try {
            return ToolResult.ok(client.currentWeather(city.strip()));
        } catch (ExternalApiException e) {
            return ToolResult.failure(e.code(), e.getMessage());
        }
    }
}
