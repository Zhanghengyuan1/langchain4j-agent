package com.example.agent.tool;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 计算器工具 —— 演示带参数的工具调用
 *
 * LLM 在需要精确数学计算时会调用这些工具，
 * 而不是自己尝试计算（LLM 算数经常出错）。
 * 阅读扩展分支：AgentService.init() 扫描 @Tool 注册工具；
 * chatStream() 收到工具请求后执行这里的方法，再把结果放回 ChatMemory。
 */
@Component
public class CalculatorTool {

    @Tool("计算两个数的加法。当需要做加法运算时使用。")
    public double add(
            @P("第一个数") double a,
            @P("第二个数") double b) {
        return a + b;
    }

    @Tool("计算两个数的减法。")
    public double subtract(
            @P("被减数") double a,
            @P("减数") double b) {
        return a - b;
    }

    @Tool("计算两个数的乘法。")
    public double multiply(
            @P("第一个数") double a,
            @P("第二个数") double b) {
        return a * b;
    }

    @Tool("计算两个数的除法。")
    public String divide(
            @P("被除数") double a,
            @P("除数") double b) {
        if (b == 0) {
            return "错误: 除数不能为零";
        }
        return String.valueOf(a / b);
    }
}
