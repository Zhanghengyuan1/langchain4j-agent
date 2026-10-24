package com.example.agent.api;

/** API 边界的受控异常：只保存可公开的错误，不携带响应原文、URL 参数或密钥。 */
public class ExternalApiException extends RuntimeException {
    private final String code;

    public ExternalApiException(String code, String safeMessage) {
        super(safeMessage);
        this.code = code;
    }

    public String code() { return code; }

    public static ExternalApiException invalidResponse() {
        return new ExternalApiException("INVALID_RESPONSE", "外部服务返回的数据不完整或格式不正确，请勿编造结果。");
    }
}
