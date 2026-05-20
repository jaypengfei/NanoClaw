package com.nano.claw.agent.common;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 响应
 *
 * @author Jason
 * @description Agent运行返回结果
 * @date 2026/5/16
 */
public class AgentResponse {

    /** 最终回答 */
    private String answer;
    /** 是否成功完成 */
    private boolean success;
    /** ReAct 循环迭代次数 */
    private int loopCount;
    /** 错误信息（失败时） */
    private String error;
    /** 链路追踪ID */
    private String traceId;
    /** 思考过程步骤列表 */
    private List<ThinkStep> thinkSteps = new ArrayList<>();
    /** 累计消耗的token总数 */
    private int totalTokens;

    public AgentResponse() {
    }

    /**
     * 创建成功的响应
     */
    public static AgentResponse success(String answer, int loopCount, String traceId) {
        AgentResponse response = new AgentResponse();
        response.setAnswer(answer);
        response.setSuccess(true);
        response.setLoopCount(loopCount);
        response.setTraceId(traceId);
        return response;
    }

    /**
     * 创建失败的响应
     */
    public static AgentResponse failure(String error, int loopCount, String traceId) {
        AgentResponse response = new AgentResponse();
        response.setSuccess(false);
        response.setError(error);
        response.setLoopCount(loopCount);
        response.setTraceId(traceId);
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

    public int getLoopCount() {
        return loopCount;
    }

    public void setLoopCount(int loopCount) {
        this.loopCount = loopCount;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public List<ThinkStep> getThinkSteps() {
        return thinkSteps;
    }

    public void setThinkSteps(List<ThinkStep> thinkSteps) {
        this.thinkSteps = thinkSteps;
    }

    public void addThinkStep(ThinkStep step) {
        this.thinkSteps.add(step);
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(int totalTokens) {
        this.totalTokens = totalTokens;
    }

    /**
     * 累加 token 用量
     */
    public void addTokens(int tokens) {
        this.totalTokens += tokens;
    }
}
