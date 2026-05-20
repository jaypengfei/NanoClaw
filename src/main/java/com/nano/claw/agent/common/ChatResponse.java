package com.nano.claw.agent.common;

import java.util.List;

/**
 * 聊天响应 - 对外暴露的API响应模型
 *
 * @author Jason
 * @description 对外聊天响应结果
 * @date 2026/5/18
 */
public class ChatResponse {

    /** 回答内容 */
    private String answer;
    /** 是否成功 */
    private boolean success;
    /** 会话ID */
    private String sessionId;
    /** 错误信息 */
    private String error;

    /** Agent 模式（由系统自动选择） */
    private String mode;
    /** 思考过程步骤（用于前端展示） */
    private List<ThinkStep> thinkSteps;
    /** 响应用时（毫秒） */
    private long durationMs;
    /** 累计消耗的token总数 */
    private int totalTokens;
    /** 是否来自缓存 */
    private boolean cached;

    public ChatResponse() {
    }

    public static ChatResponse success(String answer, String sessionId, String mode, List<ThinkStep> thinkSteps) {
        ChatResponse response = new ChatResponse();
        response.setAnswer(answer);
        response.setSuccess(true);
        response.setSessionId(sessionId);
        response.setMode(mode);
        response.setThinkSteps(thinkSteps);
        return response;
    }

    public static ChatResponse success(String answer, String sessionId, String mode) {
        ChatResponse response = new ChatResponse();
        response.setAnswer(answer);
        response.setSuccess(true);
        response.setSessionId(sessionId);
        response.setMode(mode);
        return response;
    }

    public static ChatResponse success(String answer, String sessionId) {
        return success(answer, sessionId, null);
    }

    public static ChatResponse failure(String error, String sessionId) {
        ChatResponse response = new ChatResponse();
        response.setSuccess(false);
        response.setError(error);
        response.setSessionId(sessionId);
        return response;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public List<ThinkStep> getThinkSteps() {
        return thinkSteps;
    }

    public void setThinkSteps(List<ThinkStep> thinkSteps) {
        this.thinkSteps = thinkSteps;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(int totalTokens) {
        this.totalTokens = totalTokens;
    }

    public boolean isCached() {
        return cached;
    }

    public void setCached(boolean cached) {
        this.cached = cached;
    }
}
