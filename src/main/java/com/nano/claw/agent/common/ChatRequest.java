package com.nano.claw.agent.common;

/**
 * 聊天请求 - 对外暴露的API请求模型
 *
 * @author Jason
 * @description 对外聊天请求参数
 * @date 2026/5/18
 */
public class ChatRequest {

    /** 用户消息内容 */
    private String message;
    /** 会话ID，为空时新建会话 */
    private String sessionId;
    /** Agent模式：chat / react / plan_and_execute / reflection */
    private String mode;
    /** 渠道来源：feishu / api / web */
    private String channel;
    /** 项目工作区ID，为空时自动创建新工作区 */
    private String projectId;

    public ChatRequest() {
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }
}
