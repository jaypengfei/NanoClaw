package com.nano.claw.llm;

/**
 * 大模型响应
 *
 * @author Jason
 * @description 大模型调用返回结果
 * @date 2026/5/18
 */
public class ModelResponse {

    private String traceId;
    /** 模型返回的文本内容 */
    private String content;
    /** 是否调用成功 */
    private boolean success;
    /** 错误信息 */
    private String error;
    /** 本次调用消耗的token总数 */
    private int totalTokens;

    public ModelResponse() {
    }

    public ModelResponse(String traceId, String content, boolean success, String error) {
        this.traceId = traceId;
        this.content = content;
        this.success = success;
        this.error = error;
    }

    public ModelResponse(String traceId, String content, boolean success, String error, int totalTokens) {
        this.traceId = traceId;
        this.content = content;
        this.success = success;
        this.error = error;
        this.totalTokens = totalTokens;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(int totalTokens) {
        this.totalTokens = totalTokens;
    }
}
