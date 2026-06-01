package com.nano.claw.memory;

import com.nano.claw.llm.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 记忆服务 - 统一的记忆管理入口
 * <p>
 * 串联 MemoryFileStore（存储）、MemoryCompressor（压缩）、UserProfile（画像），
 * 提供完整的记忆生命周期管理。
 * <p>
 * 核心流程：
 * 1. 对话时追加到当天的每日记忆 (memory-yyyy-mm-dd.md)
 * 2. 每次对话前加载上下文记忆（全局记忆 + 最近几天每日记忆 + 用户画像摘要）
 * 3. 定期检查：全局记忆过长则压缩，每日记忆过期则清理
 * 4. 画像提取：基于全局记忆 + 每日记忆提取用户画像
 *
 * @author Jason
 * @description 统一记忆服务
 * @date 2026/5/19
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private MemoryFileStore fileStore;

    @Value("${nanoclaw.memory.base-dir:./data/memory}")
    private String baseDir;

    @Value("${nanoclaw.memory.compress-threshold:4000}")
    private int compressThreshold;

    @Value("${nanoclaw.memory.max-retention-days:30}")
    private int maxRetentionDays;

    @Value("${nanoclaw.llm.model:MINI_MAX}")
    private String defaultModelName;

    /** 记录未压缩的对话追加计数，达到阈值后触发压缩 */
    private final AtomicInteger appendCountSinceLastCompress = new AtomicInteger(0);

    /** 每多少次追加后触发压缩检查 */
    private static final int COMPRESS_CHECK_INTERVAL = 10;

    /** 每多少次追加后触发画像更新 */
    private static final int PROFILE_UPDATE_INTERVAL = 20;

    @PostConstruct
    public void init() {
        this.fileStore = new MemoryFileStore(baseDir);
        log.info("[MEM-SERVICE] 记忆服务初始化完成, 目录: {}, 压缩阈值: {} 字符, 保留天数: {}",
                baseDir, compressThreshold, maxRetentionDays);

        // 启动时清理过期文件
        fileStore.cleanOldDailyMemories(maxRetentionDays);
    }

    // ==================== 对话记忆写入 ====================

    /**
     * 保存一轮对话到记忆
     *
     * @param userMessage     用户消息
     * @param assistantReply  助手回复
     */
    public void saveConversation(String userMessage, String assistantReply) {
        String timestamp = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + " " +
                java.time.LocalTime.now().format(TIME_FMT);

        StringBuilder entry = new StringBuilder();
        entry.append("\n### ").append(timestamp).append("\n\n");
        entry.append("**用户**: ").append(userMessage).append("\n\n");

        // 助手回复过长时截断
        String reply = assistantReply;
        if (reply != null && reply.length() > 500) {
            reply = reply.substring(0, 500) + "...(已截断)";
        }
        entry.append("**助手**: ").append(reply != null ? reply : "").append("\n\n");

        // 追加到当天的每日记忆
        fileStore.appendTodayMemory(entry.toString());

        int count = appendCountSinceLastCompress.incrementAndGet();
        log.info("[MEM-SERVICE] 对话已保存, 累计追加: {}", count);

        // 定期检查是否需要压缩
        if (count % COMPRESS_CHECK_INTERVAL == 0) {
            checkAndCompress();
        }

        // 定期更新用户画像
        if (count % PROFILE_UPDATE_INTERVAL == 0) {
            triggerProfileUpdate();
        }
    }

    /**
     * 保存一条用户问句到独立的 user-queries.md 文件
     * <p>
     * 独立存储便于后续检索（如仅拼接最近 N 轮提问作为上下文）
     */
    public void saveUserQuery(String userMessage) {
        if (userMessage == null || userMessage.trim().isEmpty()) return;
        try {
            fileStore.appendUserQuery(userMessage);
        } catch (Exception e) {
            log.warn("[MEM-SERVICE] 保存用户问句失败", e);
        }
    }

    // ==================== 记忆读取（构建上下文） ====================

    /**
     * 获取记忆上下文 - 用于注入到 Agent 的 system prompt
     * <p>
     * 包含：全局记忆摘要 + 用户画像 + 最近3天每日记忆精华
     *
     * @return 记忆上下文文本，无记忆时返回空字符串
     */
    public String getMemoryContext() {
        StringBuilder context = new StringBuilder();

        // 1. 用户画像
        String profile = fileStore.readUserProfile();
        if (profile != null && !profile.trim().isEmpty()) {
            context.append("## 用户画像\n\n").append(profile).append("\n\n");
        }

        // 2. 全局持久记忆
        String globalMemory = fileStore.readGlobalMemory();
        if (globalMemory != null && !globalMemory.trim().isEmpty()) {
            context.append("## 长期记忆\n\n").append(globalMemory).append("\n\n");
        }

        // 3. 最近 3 轮用户提问（仅问句本身，不拼接助手回复，避免上下文过长）
        try {
            java.util.List<String> recentQueries = fileStore.readRecentUserQueries(3);
            if (recentQueries != null && !recentQueries.isEmpty()) {
                context.append("## 最近用户提问（近 ").append(recentQueries.size()).append(" 轮）\n\n");
                for (String q : recentQueries) {
                    context.append("- ").append(q).append("\n");
                }
                context.append("\n");
            }
        } catch (Exception e) {
            log.warn("[MEM-SERVICE] 读取最近用户提问失败", e);
        }

        if (context.length() > 0) {
            log.info("[MEM-SERVICE] 加载记忆上下文, 长度: {} 字符", context.length());
        }

        return context.toString();
    }

    /**
     * 构建注入到 system prompt 的记忆片段
     *
     * @return 注入到 system prompt 的记忆文本
     */
    public String buildSystemPromptMemorySection() {
        String context = getMemoryContext();
        if (context.isEmpty()) return "";

        return "\n\n---\n\n# 关于用户的历史记忆\n\n"
                + "以下是你对用户的了解，请参考这些信息来提供更个性化的回答：\n\n"
                + context
                + "---\n\n";
    }

    // ==================== 压缩与清理 ====================

    /**
     * 检查并执行记忆压缩
     * <p>
     * 策略：
     * - 如果全局记忆超过阈值，压缩全局记忆
     * - 将较早的每日记忆合并到全局记忆中，然后清理
     */
    public void checkAndCompress() {
        try {
            Model model = resolveModel();

            // 1. 检查全局记忆是否需要压缩
            String globalMemory = fileStore.readGlobalMemory();
            if (MemoryCompressor.needsCompression(globalMemory, compressThreshold)) {
                log.info("[MEM-SERVICE] 全局记忆超阈值 ({} > {})，开始压缩",
                        globalMemory.length(), compressThreshold);
                String compressed = MemoryCompressor.compress(globalMemory, model);
                fileStore.writeGlobalMemory(compressed);
            }

            // 2. 将7天前的每日记忆合并到全局记忆
            LocalDate mergeThreshold = LocalDate.now().minusDays(7);
            List<LocalDate> dates = fileStore.listDailyMemoryDates();
            for (LocalDate date : dates) {
                if (date.isBefore(mergeThreshold)) {
                    String dailyMemory = fileStore.readDailyMemory(date);
                    if (dailyMemory != null && !dailyMemory.trim().isEmpty()) {
                        // 合并到全局记忆（使用冲突解决）
                        String oldGlobal = fileStore.readGlobalMemory();
                        String merged;
                        if (oldGlobal != null && !oldGlobal.trim().isEmpty()) {
                            merged = MemoryCompressor.resolveConflict(oldGlobal, dailyMemory, model);
                        } else {
                            merged = dailyMemory;
                        }
                        fileStore.writeGlobalMemory(merged);

                        // 压缩合并后的全局记忆
                        if (MemoryCompressor.needsCompression(merged, compressThreshold)) {
                            String compressed = MemoryCompressor.compress(merged, model);
                            fileStore.writeGlobalMemory(compressed);
                        }
                    }
                }
            }

            // 3. 清理过期的每日记忆
            fileStore.cleanOldDailyMemories(maxRetentionDays);

        } catch (Exception e) {
            log.error("[MEM-SERVICE] 压缩检查异常", e);
        }
    }

    /**
     * 触发用户画像更新
     */
    public void triggerProfileUpdate() {
        try {
            Model model = resolveModel();
            String globalMemory = fileStore.readGlobalMemory();
            String recentMemory = fileStore.readRecentDailyMemories(7);
            String existingProfile = fileStore.readUserProfile();

            // 合并所有记忆作为提取源
            StringBuilder allMemory = new StringBuilder();
            if (globalMemory != null && !globalMemory.trim().isEmpty()) {
                allMemory.append(globalMemory);
            }
            if (recentMemory != null && !recentMemory.trim().isEmpty()) {
                if (allMemory.length() > 0) allMemory.append("\n\n");
                allMemory.append(recentMemory);
            }

            if (allMemory.length() == 0) return;

            String newProfile = MemoryCompressor.extractUserProfile(allMemory.toString(), existingProfile, model);
            fileStore.writeUserProfile(newProfile);

            log.info("[MEM-SERVICE] 用户画像已更新");

        } catch (Exception e) {
            log.error("[MEM-SERVICE] 画像更新异常", e);
        }
    }

    // ==================== 查询接口 ====================

    /**
     * 获取所有记忆概览
     */
    public Map<String, Object> getMemoryOverview() {
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("storageInfo", fileStore.getStorageInfo());

        // 全局记忆前200字
        String globalMemory = fileStore.readGlobalMemory();
        overview.put("globalMemoryPreview", globalMemory != null && globalMemory.length() > 200
                ? globalMemory.substring(0, 200) + "..."
                : globalMemory);

        // 用户画像
        String profile = fileStore.readUserProfile();
        UserProfile userProfile = UserProfile.fromMarkdown(profile);
        overview.put("userProfile", userProfile.toDisplayMap());

        // 每日记忆列表
        overview.put("dailyMemoryDates", fileStore.listDailyMemoryDates());

        return overview;
    }

    /**
     * 获取全局记忆全文
     */
    public String getGlobalMemory() {
        return fileStore.readGlobalMemory();
    }

    /**
     * 获取用户画像 Markdown
     */
    public String getUserProfileMarkdown() {
        return fileStore.readUserProfile();
    }

    /**
     * 获取用户画像对象
     */
    public UserProfile getUserProfile() {
        return UserProfile.fromMarkdown(fileStore.readUserProfile());
    }

    /**
     * 获取指定日期的每日记忆
     */
    public String getDailyMemory(String dateStr) {
        try {
            LocalDate date = LocalDate.parse(dateStr);
            return fileStore.readDailyMemory(date);
        } catch (Exception e) {
            return "日期格式错误: " + dateStr;
        }
    }

    /**
     * 获取今天的每日记忆
     */
    public String getTodayMemory() {
        return fileStore.readTodayMemory();
    }

    /**
     * 手动写入全局记忆
     */
    public void writeGlobalMemory(String content) {
        fileStore.writeGlobalMemory(content);
    }

    /**
     * 手动写入用户画像
     */
    public void writeUserProfile(String content) {
        fileStore.writeUserProfile(content);
    }

    private Model resolveModel() {
        try {
            return Model.valueOf(defaultModelName);
        } catch (Exception e) {
            return Model.MINI_MAX;
        }
    }
}
