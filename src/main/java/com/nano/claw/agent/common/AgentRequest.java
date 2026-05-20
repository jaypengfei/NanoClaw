package com.nano.claw.agent.common;

import com.nano.claw.llm.Model;

import java.util.function.Consumer;

/**
 * Agent 请求
 *
 * @author Jason
 * @description Agent运行请求参数
 * @date 2026/5/16
 */
public class AgentRequest {

    /** 用户输入的问题/指令 */
    private String query;
    /** 指定使用的大模型 */
    private Model model;
    /** 会话ID，用于多轮对话上下文 */
    private String sessionId;
    /** 自定义 system prompt 覆盖默认 */
    private String systemPrompt;
    /** 思考步骤实时回调（SSE流式推送时使用） */
    private Consumer<ThinkStep> thinkStepConsumer;

    public AgentRequest() {
    }

    public AgentRequest(String query, Model model, String sessionId) {
        this.query = query;
        this.model = model;
        this.sessionId = sessionId;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public Model getModel() {
        return model;
    }

    public void setModel(Model model) {
        this.model = model;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public Consumer<ThinkStep> getThinkStepConsumer() {
        return thinkStepConsumer;
    }

    public void setThinkStepConsumer(Consumer<ThinkStep> thinkStepConsumer) {
        this.thinkStepConsumer = thinkStepConsumer;
    }
}
