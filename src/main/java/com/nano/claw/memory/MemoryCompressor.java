package com.nano.claw.memory;

import com.nano.claw.llm.Model;
import com.nano.claw.llm.ModelFacade;
import com.nano.claw.llm.ModelRequest;
import com.nano.claw.llm.ModelResponse;
import com.nano.claw.messages.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 记忆压缩器 - 使用 LLM 进行记忆压缩与冲突解决
 * <p>
 * 核心职责：
 * 1. 当记忆过长时，智能压缩保留关键信息
 * 2. 检测新旧记忆之间的冲突，基于判断进行更新
 * 3. 从对话中提取结构化用户画像
 *
 * @author Jason
 * @description 记忆压缩与冲突解决
 * @date 2026/5/19
 */
public class MemoryCompressor {

    private static final Logger log = LoggerFactory.getLogger(MemoryCompressor.class);

    /** 压缩用的 system prompt */
    private static final String COMPRESS_PROMPT =
            "你是一个记忆压缩专家。你的任务是将一段长记忆压缩为更短的版本，同时保留所有关键信息。\n\n"
            + "压缩规则：\n"
            + "1. 保留所有事实性信息（姓名、数字、日期、地点等）\n"
            + "2. 保留用户的偏好和习惯\n"
            + "3. 合并相似或重复的内容\n"
            + "4. 删除无关紧要的寒暄和过渡语句\n"
            + "5. 用更精炼的表述替代冗长的描述\n"
            + "6. 保持 Markdown 格式\n"
            + "7. 压缩后的长度应控制在原文的40-60%\n\n"
            + "直接输出压缩后的内容，不要添加任何前缀说明。";

    /** 冲突检测与解决的 system prompt */
    private static final String CONFLICT_RESOLVE_PROMPT =
            "你是一个记忆冲突解决专家。你的任务是比较新旧两段记忆，检测其中的冲突，并输出合并后的正确版本。\n\n"
            + "规则：\n"
            + "1. 如果新旧信息有矛盾，以新信息为准（用户可能改变了偏好或更正了信息）\n"
            + "2. 如果新旧信息互补（不矛盾），则都保留\n"
            + "3. 保持 Markdown 格式\n"
            + "4. 直接输出合并后的内容，不要添加说明\n\n"
            + "请对比以下新旧记忆并输出合并结果：";

    /** 用户画像提取的 system prompt */
    private static final String PROFILE_EXTRACT_PROMPT =
            "你是一个用户画像分析专家。你的任务是从对话记忆中提取用户的结构化画像信息。\n\n"
            + "请严格按照以下 Markdown 格式输出，不要添加其他内容：\n\n"
            + "## 基本信息\n\n"
            + "- **称呼**: 用户喜欢的称呼\n"
            + "- **职业**: 用户的职业或领域\n"
            + "- **语言**: 用户使用的语言\n"
            + "- **时区**: 推断的用户时区\n\n"
            + "## 偏好与习惯\n\n"
            + "- 偏好1\n"
            + "- 偏好2\n\n"
            + "## 兴趣领域\n\n"
            + "- 兴趣1\n"
            + "- 兴趣2\n\n"
            + "## 沟通风格\n\n"
            + "- **详细程度**: 简洁/适中/详细\n"
            + "- **技术深度**: 入门/进阶/专家\n"
            + "- **语言风格**: 正式/随意/技术化\n\n"
            + "## 关键标签\n\n"
            + "标签1 · 标签2 · 标签3\n\n"
            + "重要规则：\n"
            + "- 只输出能从记忆中推断出的信息，不要臆造\n"
            + "- 如果某个维度没有信息，输出 '暂无'\n"
            + "- 标签应该是关键词，便于快速理解用户特征";

    /**
     * 压缩记忆
     *
     * @param content      原始记忆内容
     * @param model        使用的模型
     * @return 压缩后的内容，压缩失败返回原文
     */
    public static String compress(String content, Model model) {
        if (content == null || content.trim().isEmpty()) return content;

        log.info("[MEM-COMP] 开始压缩记忆, 原始长度: {} 字符", content.length());

        try {
            String traceId = UUID.randomUUID().toString();
            List<Message> messages = new ArrayList<>();
            messages.add(new Message("system", COMPRESS_PROMPT));
            messages.add(new Message("user", "请压缩以下记忆：\n\n" + content));

            ModelRequest request = new ModelRequest(model, traceId, messages);
            ModelResponse response = ModelFacade.chatCompletion(request);

            if (response.isSuccess() && response.getContent() != null && !response.getContent().trim().isEmpty()) {
                String compressed = response.getContent().trim();
                log.info("[MEM-COMP] 压缩完成, 压缩后长度: {} 字符, 压缩率: {}%",
                        compressed.length(),
                        (100 - (compressed.length() * 100 / content.length())));
                return compressed;
            }

            log.warn("[MEM-COMP] 压缩失败: {}", response.getError());
            return content;
        } catch (Exception e) {
            log.error("[MEM-COMP] 压缩异常", e);
            return content;
        }
    }

    /**
     * 解决新旧记忆冲突
     *
     * @param oldMemory  旧记忆
     * @param newMemory  新记忆
     * @param model      使用的模型
     * @return 合并后的记忆
     */
    public static String resolveConflict(String oldMemory, String newMemory, Model model) {
        if (oldMemory == null || oldMemory.trim().isEmpty()) return newMemory;
        if (newMemory == null || newMemory.trim().isEmpty()) return oldMemory;

        log.info("[MEM-COMP] 开始冲突解决, 旧记忆: {} 字符, 新记忆: {} 字符",
                oldMemory.length(), newMemory.length());

        try {
            String traceId = UUID.randomUUID().toString();
            List<Message> messages = new ArrayList<>();
            messages.add(new Message("system", CONFLICT_RESOLVE_PROMPT));
            messages.add(new Message("user",
                    "【旧记忆】\n" + oldMemory + "\n\n【新记忆】\n" + newMemory));

            ModelRequest request = new ModelRequest(model, traceId, messages);
            ModelResponse response = ModelFacade.chatCompletion(request);

            if (response.isSuccess() && response.getContent() != null && !response.getContent().trim().isEmpty()) {
                String merged = response.getContent().trim();
                log.info("[MEM-COMP] 冲突解决完成, 合并后长度: {} 字符", merged.length());
                return merged;
            }

            // 冲突解决失败，简单拼接
            log.warn("[MEM-COMP] 冲突解决失败，简单拼接");
            return oldMemory + "\n\n" + newMemory;
        } catch (Exception e) {
            log.error("[MEM-COMP] 冲突解决异常", e);
            return oldMemory + "\n\n" + newMemory;
        }
    }

    /**
     * 从记忆内容中提取用户画像
     *
     * @param memoryContent 记忆内容
     * @param existingProfile 现有画像（用于冲突合并）
     * @param model         使用的模型
     * @return 提取的用户画像 Markdown
     */
    public static String extractUserProfile(String memoryContent, String existingProfile, Model model) {
        if (memoryContent == null || memoryContent.trim().isEmpty()) return existingProfile;

        log.info("[MEM-COMP] 开始提取用户画像, 记忆长度: {} 字符", memoryContent.length());

        try {
            String traceId = UUID.randomUUID().toString();
            List<Message> messages = new ArrayList<>();
            messages.add(new Message("system", PROFILE_EXTRACT_PROMPT));

            StringBuilder userContent = new StringBuilder();
            userContent.append("请从以下记忆中提取用户画像：\n\n").append(memoryContent);

            if (existingProfile != null && !existingProfile.trim().isEmpty()) {
                userContent.append("\n\n【现有画像（请在此基础上更新，解决冲突）】\n").append(existingProfile);
            }

            messages.add(new Message("user", userContent.toString()));

            ModelRequest request = new ModelRequest(model, traceId, messages);
            ModelResponse response = ModelFacade.chatCompletion(request);

            if (response.isSuccess() && response.getContent() != null && !response.getContent().trim().isEmpty()) {
                String profile = response.getContent().trim();
                log.info("[MEM-COMP] 用户画像提取完成, 长度: {} 字符", profile.length());
                return profile;
            }

            log.warn("[MEM-COMP] 用户画像提取失败: {}", response.getError());
            return existingProfile != null ? existingProfile : "";
        } catch (Exception e) {
            log.error("[MEM-COMP] 用户画像提取异常", e);
            return existingProfile != null ? existingProfile : "";
        }
    }

    /**
     * 判断是否需要压缩
     *
     * @param content    记忆内容
     * @param threshold  压缩阈值（字符数）
     * @return 是否需要压缩
     */
    public static boolean needsCompression(String content, int threshold) {
        return content != null && content.length() > threshold;
    }
}
