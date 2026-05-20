package com.nano.claw.sessions;

import com.nano.claw.memory.ConversationMemory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话管理器 - 管理所有活跃会话的对话记忆
 *
 * @author Jason
 * @description 会话生命周期管理
 * @date 2026/5/19
 */
public class SessionManager {

    private final Map<String, ConversationMemory> sessions = new ConcurrentHashMap<>();

    /** 默认最大历史条数 */
    private static final int DEFAULT_MAX_HISTORY = 50;

    /**
     * 获取或创建会话记忆
     *
     * @param sessionId 会话ID
     * @return 对话记忆
     */
    public ConversationMemory getOrCreate(String sessionId) {
        return sessions.computeIfAbsent(sessionId,
                id -> new ConversationMemory(id, DEFAULT_MAX_HISTORY));
    }

    /**
     * 获取已有会话（不存在返回 null）
     */
    public ConversationMemory get(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * 删除会话
     */
    public void remove(String sessionId) {
        sessions.remove(sessionId);
    }

    /**
     * 清空所有会话
     */
    public void clearAll() {
        sessions.clear();
    }

    /**
     * 获取活跃会话数
     */
    public int activeSessionCount() {
        return sessions.size();
    }
}