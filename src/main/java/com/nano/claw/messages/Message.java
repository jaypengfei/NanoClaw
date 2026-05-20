package com.nano.claw.messages;

/**
 * 对话消息，兼容 OpenAI/MiniMax 格式
 *
 * @author Jason
 * @description 用于组装大模型请求的消息结构
 * @date 2026/5/19
 */
public class Message {

    private String role;
    private String content;

    public Message() {
    }

    public Message(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    @Override
    public String toString() {
        return "Message{role='" + role + "', content='" + content + "'}";
    }
}