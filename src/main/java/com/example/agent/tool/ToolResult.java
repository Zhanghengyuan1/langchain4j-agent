package com.example.agent.tool;

/**
 * 阅读 2：统一工具结果。DefaultToolExecutor 会把这个对象序列化为 JSON 给模型。
 * success=false 时 data=null，避免把错误当成天气或搜索结果。
 */
// Java 16+的record类型，专门用来写不可变数据载体，自动生成构造、getter、equals等所有模板代码
// 泛型<T>代表工具返回的具体业务数据类型，比如天气工具就是Weather，搜索工具就是List<SearchResult>
public record ToolResult<T>(
        // 标记本次工具调用是否成功：true=调用正常返回业务数据，false=调用出错
        boolean success,
        // 机器可读的错误码，比如PARAM_INVALID、API_TIMEOUT，方便排查问题
        String code,
        // 人类可读的提示信息，成功时是固定文案，失败时是具体错误原因
        String message,
        // 真正的业务返回数据，只有success=true时才有值，失败时固定为null
        T data
) {
    // 静态工具方法：快速构造一个成功的返回结果，调用方只需要传业务数据即可
    public static <T> ToolResult<T> ok(T data) {
        // 固定填充成功的默认值，不需要每次调用都重复写重复参数
        return new ToolResult<>(true, "OK", "查询成功", data);
    }

    // 静态工具方法：快速构造一个失败的返回结果
    public static <T> ToolResult<T> failure(String code, String message) {
        // 固定把data设为null，避免错误信息被LLM当成正常业务数据解析产生幻觉
        return new ToolResult<>(false, code, message, null);
    }
}