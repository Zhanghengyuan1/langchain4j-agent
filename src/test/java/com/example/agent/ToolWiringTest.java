package com.example.agent;

import com.example.agent.tool.GoogleSearchTool;
import com.example.agent.tool.WeatherTool;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 Spring 配置绑定和构造器注入；覆盖本地密钥导入，测试绝不依赖真实 Key。 */
@SpringBootTest(properties = {"spring.config.import=", "llm.deepseek.api-key=", "tools.search.api-key="})
class ToolWiringTest {
    @Autowired GoogleSearchTool search;
    @Autowired WeatherTool weather;

    @Test
    void startsWithoutSearchKeyAndRetainsWeatherTool() {
        assertThat(search.googleSearch("Java", 1).code()).isEqualTo("NOT_CONFIGURED");
        assertThat(weather).isNotNull();
    }
}
