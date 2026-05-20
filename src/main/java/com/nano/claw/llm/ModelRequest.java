package com.nano.claw.llm;

import com.nano.claw.messages.Message;
import java.util.List;

/**
 * 大模型请求
 *
 * @author Jason
 * @description 大模型调用请求参数
 * @date 2026/5/18
 */
public class ModelRequest {

    /** 指定使用的大模型 */
    private Model model;
    /** 链路追踪ID */
    private String traceId;
    /** 对话消息列表 */
    private List<Message> messages;

    public ModelRequest() {
    }

    public ModelRequest(Model model, String traceId, List<Message> messages) {
        this.model = model;
        this.traceId = traceId;
        this.messages = messages;
    }

    public Model getModel() {
        return model;
    }

    public void setModel(Model model) {
        this.model = model;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public void setMessages(List<Message> messages) {
        this.messages = messages;
    }
}
