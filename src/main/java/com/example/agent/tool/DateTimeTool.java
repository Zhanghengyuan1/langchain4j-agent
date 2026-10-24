package com.example.agent.tool;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * ============================================
 * 第二层：工具定义 (Tools)
 * ============================================
 * 使用 @Tool 注解将普通 Java 方法暴露给 LLM。
 *
 * 工作原理：
 *   1. @Tool 注解的 value 会作为工具描述发送给 LLM
 *   2. @P 注解描述每个参数的含义
 *   3. LangChain4J 自动将这些信息转换为 OpenAI Function Calling 的 JSON Schema
 *   4. LLM 根据用户意图决定是否调用工具、传什么参数
 *   5. 框架自动调用对应方法并将结果返回给 LLM
 *
 * 注意：
 *   - 方法必须是 public 的
 *   - 返回值会被转为字符串发回给 LLM
 *   - 抛出异常时，异常信息也会作为工具结果返回
 */
@Component
public class DateTimeTool {

    @Tool("获取当前的日期和时间。当用户询问现在几点、今天几号、当前时间等问题时使用此工具。")
    public String getCurrentDateTime() {
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss (EEEE)");
        return "当前时间: " + now.format(formatter);
    }

    @Tool("计算两个日期之间相差的天数")
    public String daysBetween(
            @P("起始日期，格式: yyyy-MM-dd") String startDate,
            @P("结束日期，格式: yyyy-MM-dd") String endDate) {
        try {
            var start = java.time.LocalDate.parse(startDate);
            var end = java.time.LocalDate.parse(endDate);
            long days = java.time.temporal.ChronoUnit.DAYS.between(start, end);
            return String.format("从 %s 到 %s 共相差 %d 天", startDate, endDate, days);
        } catch (Exception e) {
            return "日期格式错误，请使用 yyyy-MM-dd 格式，例如: 2024-01-01";
        }
    }
}
