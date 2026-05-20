package com.nano.claw.sessions;

import com.nano.claw.memory.ConversationMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话管理器 - 管理所有活跃会话的对话记忆
 * <p>
 * 支持会话过期清理，防止内存泄漏。
 *
 * @author Jason
 * @description 会话生命周期管理
 * @date 2026/5/19
 */
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private final Map<String, SessionEntry> sessions = new ConcurrentHashMap<>();

    /** 默认最大历史条数 */
    private static final int DEFAULT_MAX_HISTORY = 50;

    /** 会话过期时间：30分钟无访问则过期 */
    private static final long SESSION_TIMEOUT_MS = 30 * 60 * 1000L;

    /** 最大活跃会话数 */
    private static final int MAX_SESSIONS = 1000;

    /** 清理检查间隔：每次访问时概率触发 */
    private static final double CLEANUP_PROBABILITY = 0.05;

    /**
     * 会话条目，包装 ConversationMemory 和最后访问时间
     */
    private static class SessionEntry {
        final ConversationMemory memory;
        volatile long lastAccessTime;

        SessionEntry(ConversationMemory memory) {
            this.memory = memory;
            this.lastAccessTime = System.currentTimeMillis();
        }

        void touch() {
            this.lastAccessTime = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - lastAccessTime > SESSION_TIMEOUT_MS;
        }
    }

    /**
     * 获取或创建会话记忆
     *
     * @param sessionId 会话ID
     * @return 对话记忆
     */
    public ConversationMemory getOrCreate(String sessionId) {
        // 概率触发过期清理
        if (Math.random() < CLEANUP_PROBABILITY) {
            cleanExpiredSessions();
        }

        SessionEntry entry = sessions.computeIfAbsent(sessionId,
                id -> new SessionEntry(new ConversationMemory(id, DEFAULT_MAX_HISTORY)));
        entry.touch();

        // 超过最大会话数时强制清理
        if (sessions.size() > MAX_SESSIONS) {
            cleanExpiredSessions();
            // 如果清理后仍然超限，移除最老的
            if (sessions.size() > MAX_SESSIONS) {
                evictOldestSession();
            }
        }

        return entry.memory;
    }

    /**
     * 获取已有会话（不存在返回 null）
     */
    public ConversationMemory get(String sessionId) {
        SessionEntry entry = sessions.get(sessionId);
        if (entry != null) {
            entry.touch();
            return entry.memory;
        }
        return null;
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

    /**
     * 清理过期会话
     */
    private void cleanExpiredSessions() {
        int before = sessions.size();
        sessions.entrySet().removeIf(e -> e.getValue().isExpired());
        int removed = before - sessions.size();
        if (removed > 0) {
            log.debug("[SESSION] 清理过期会话: {} 个, 剩余: {} 个", removed, sessions.size());
        }
    }

    /**
     * 驱逐最老的会话
     */
    private void evictOldestSession() {
        String oldestKey = null;
        long oldestTime = Long.MAX_VALUE;
        for (Map.Entry<String, SessionEntry> e : sessions.entrySet()) {
            if (e.getValue().lastAccessTime < oldestTime) {
                oldestTime = e.getValue().lastAccessTime;
                oldestKey = e.getKey();
            }
        }
        if (oldestKey != null) {
            sessions.remove(oldestKey);
            log.debug("[SESSION] 驱逐最老会话: {}", oldestKey);
        }
    }
}
