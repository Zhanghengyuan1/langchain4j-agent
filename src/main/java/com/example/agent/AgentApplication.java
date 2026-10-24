package com.example.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SpringBootApplication
public class AgentApplication {

    private static final Logger log = LoggerFactory.getLogger(AgentApplication.class);

    // IDEA 运行入口：运行 main() 后，Spring 扫描并创建 Controller、AgentService 和模型 Bean。
    // 理解请求流程时从 index.html 的 sendMessage() 开始读，而不是从 main() 往下找。
    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(AgentApplication.class, args);
        int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        log.info("Agent 页面已就绪，点击打开：http://localhost:{}/index.html", port);
    }
}
