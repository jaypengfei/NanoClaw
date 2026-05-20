package com.nano.claw.memory;

import com.nano.claw.messages.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 对话记忆 - 存储单次会话的对话历史
 *
 * @author Jason
 * @description 基于内存的对话历史管理
 * @date 2026/5/19
 */
public class ConversationMemory {

    private final String sessionId;
    private final List<Message> history;
    /** 最大保留的历史消息条数（防止上下文过长） */
    private int maxHistorySize = 50;

    public ConversationMemory(String sessionId) {
        this.sessionId = sessionId;
        this.history = new ArrayList<>();
    }

    public ConversationMemory(String sessionId, int maxHistorySize) {
        this.sessionId = sessionId;
        this.history = new ArrayList<>();
        this.maxHistorySize = maxHistorySize;
    }

    /**
     * 添加消息到历史
     */
    public void addMessage(Message message) {
        history.add(message);
        trimHistory();
    }

    /**
     * 添加多条消息
     */
    public void addMessages(List<Message> messages) {
        history.addAll(messages);
        trimHistory();
    }

    /**
     * 获取所有历史消息（只读视图）
     */
    public List<Message> getHistory() {
        return Collections.unmodifiableList(history);
    }

    /**
     * 获取最近N条消息
     */
    public List<Message> getRecentMessages(int n) {
        if (n >= history.size()) {
            return Collections.unmodifiableList(history);
        }
        return Collections.unmodifiableList(history.subList(history.size() - n, history.size()));
    }

    /**
     * 构建用于模型调用的消息列表（包含 system prompt）
     */
    public List<Message> buildMessages(String systemPrompt, String userQuery) {
        List<Message> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
            messages.add(new Message("system", systemPrompt));
        }
        messages.addAll(history);
        if (userQuery != null && !userQuery.trim().isEmpty()) {
            messages.add(new Message("user", userQuery));
        }
        return messages;
    }

    /**
     * 清空历史
     */
    public void clear() {
        history.clear();
    }

    public String getSessionId() {
        return sessionId;
    }

    public int size() {
        return history.size();
    }

    /**
     * 裁剪历史，保留最近的消息
     */
    private void trimHistory() {
        while (history.size() > maxHistorySize) {
            // 保留第一条 system 消息（如果有的话），移除最早的非 system 消息
            if (!history.isEmpty() && "system".equals(history.get(0).getRole())) {
                if (history.size() > 1) {
                    history.remove(1);
                } else {
                    break;
                }
            } else if (!history.isEmpty()) {
                history.remove(0);
            }
        }
    }
}