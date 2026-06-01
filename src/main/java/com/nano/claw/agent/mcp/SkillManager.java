package com.nano.claw.agent.mcp;

import com.nano.claw.llm.Model;
import com.nano.claw.llm.ModelFacade;
import com.nano.claw.llm.ModelRequest;
import com.nano.claw.llm.ModelResponse;
import com.nano.claw.messages.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 技能管理服务
 * <p>
 * 负责技能的全生命周期管理：
 * - 从 skills 目录扫描和加载技能
 * - 技能的安装/卸载/启用/禁用
 * - 基于 LLM 的智能技能选择
 * - 为 Agent 构建技能相关的 system prompt
 *
 * @author Jason
 * @description 技能全生命周期管理服务
 * @date 2026/5/21
 */
@Service
public class SkillManager {

    private static final Logger log = LoggerFactory.getLogger(SkillManager.class);

    @Value("${nanoclaw.skills.base-dir:./src/main/resources/static/skills}")
    private String skillsBaseDir;

    @Value("${nanoclaw.llm.model:MINI_MAX}")
    private String defaultModelName;

    private final SkillRegistry skillRegistry = new SkillRegistry();

    /** 技能安装记录，持久化到 data/skills/installed.json */
    private final Map<String, SkillInstallRecord> installRecords = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // 启动时扫描并加载所有技能
        int count = skillRegistry.scanAndLoad(skillsBaseDir);
        log.info("[SKILL-MGR] 初始化完成，加载技能: {}", count);

        // 也扫描 data/skills 目录（用户安装的技能）
        String dataSkillsDir = "./data/skills";
        Path dataDir = Paths.get(dataSkillsDir);
        if (Files.isDirectory(dataDir)) {
            int dataCount = skillRegistry.scanAndLoad(dataSkillsDir);
            log.info("[SKILL-MGR] 加载用户技能: {}", dataCount);
        }
    }

    /**
     * 获取技能注册中心
     */
    public SkillRegistry getSkillRegistry() {
        return skillRegistry;
    }

    /**
     * 获取所有技能列表
     */
    public List<SkillInfo> listSkills() {
        return skillRegistry.getAllSkills().stream()
                .map(this::toSkillInfo)
                .collect(Collectors.toList());
    }

    /**
     * 获取已启用的技能列表
     */
    public List<SkillInfo> listEnabledSkills() {
        return skillRegistry.getEnabledSkills().stream()
                .map(this::toSkillInfo)
                .collect(Collectors.toList());
    }

    /**
     * 启用技能
     */
    public boolean enableSkill(String name) {
        return skillRegistry.enable(name);
    }

    /**
     * 禁用技能
     */
    public boolean disableSkill(String name) {
        return skillRegistry.disable(name);
    }

    /**
     * 安装技能（从 Markdown 内容创建技能）
     *
     * @param name        技能名称
     * @param markdownContent skill.md 内容
     * @return 安装结果
     */
    public SkillInstallResult installSkill(String name, String markdownContent) {
        if (name == null || name.trim().isEmpty()) {
            return SkillInstallResult.failure("技能名称不能为空");
        }
        if (markdownContent == null || markdownContent.trim().isEmpty()) {
            return SkillInstallResult.failure("技能内容不能为空");
        }

        // 解析 Markdown
        MarkdownSkill skill = MarkdownSkill.parse(markdownContent, name);

        // 保存到 data/skills/{name}/skill.md
        try {
            Path skillDir = Paths.get("./data/skills", name);
            Files.createDirectories(skillDir);
            Path skillFile = skillDir.resolve("skill.md");
            Files.write(skillFile, markdownContent.getBytes("UTF-8"));
        } catch (IOException e) {
            log.error("[SKILL-MGR] 保存技能文件失败: {}", name, e);
            return SkillInstallResult.failure("保存技能文件失败: " + e.getMessage());
        }

        // 注册到 Registry
        skillRegistry.register(skill);

        // 记录安装信息
        installRecords.put(name, new SkillInstallRecord(name, System.currentTimeMillis(), "user"));

        log.info("[SKILL-MGR] 安装技能: {} v{}", name, skill.getVersion());
        return SkillInstallResult.success(name, skill.getVersion());
    }

    /**
     * 卸载技能
     */
    public boolean uninstallSkill(String name) {
        if (!skillRegistry.hasSkill(name)) {
            return false;
        }

        // 从 Registry 注销
        skillRegistry.unregister(name);

        // 删除文件
        try {
            Path skillDir = Paths.get("./data/skills", name);
            if (Files.exists(skillDir)) {
                deleteDirectory(skillDir.toFile());
            }
        } catch (Exception e) {
            log.warn("[SKILL-MGR] 删除技能文件失败: {}", name, e);
        }

        installRecords.remove(name);
        log.info("[SKILL-MGR] 卸载技能: {}", name);
        return true;
    }

    /**
     * 根据用户输入自动选择最合适的技能
     * <p>
     * 先用关键词匹配，匹配不到则调用 LLM 智能选择
     *
     * @param query 用户输入
     * @return 匹配到的技能列表
     */
    public List<Skill> autoSelectSkills(String query) {
        // 1. 关键词匹配
        List<Skill> matched = skillRegistry.matchSkills(query);
        if (!matched.isEmpty()) {
            log.info("[SKILL-MGR] 关键词匹配到技能: {}", matched.stream()
                    .map(Skill::getName).collect(Collectors.joining(",")));
            return matched;
        }

        // 2. LLM 智能选择
        List<Skill> llmMatched = selectByLLM(query);
        if (!llmMatched.isEmpty()) {
            log.info("[SKILL-MGR] LLM选择到技能: {}", llmMatched.stream()
                    .map(Skill::getName).collect(Collectors.joining(",")));
            return llmMatched;
        }

        return Collections.emptyList();
    }

    /**
     * 为 Agent 构建 skill 相关的 system prompt 片段
     */
    public String buildSkillPrompt(List<Skill> activeSkills) {
        if (activeSkills == null || activeSkills.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## 可用技能\n\n");
        sb.append("你可以使用以下技能来增强你的能力。当用户的问题与某个技能相关时，");
        sb.append("请在 Thought 中声明你将使用该技能，然后在 Action 中调用对应的技能工具。\n\n");

        for (Skill skill : activeSkills) {
            sb.append("### 技能: ").append(skill.getName()).append("\n");
            sb.append("- 描述: ").append(skill.getDescription()).append("\n");
            if (!skill.getTags().isEmpty()) {
                sb.append("- 标签: ").append(String.join(", ", skill.getTags())).append("\n");
            }
            if (!skill.getTriggerKeywords().isEmpty()) {
                sb.append("- 触发词: ").append(String.join(", ", skill.getTriggerKeywords())).append("\n");
            }
            sb.append("- 使用方式: Action: skill_").append(skill.getName()).append("\n");
            sb.append("- Action Input: {\"query\": \"用户的问题\"}\n\n");
        }

        return sb.toString();
    }

    /**
     * 判断某个 action name 是否是 skill 工具
     */
    public boolean isSkillAction(String actionName) {
        if (actionName == null) return false;
        if (!actionName.startsWith("skill_")) return false;
        String skillName = actionName.substring("skill_".length());
        return skillRegistry.hasSkill(skillName) && skillRegistry.isEnabled(skillName);
    }

    /**
     * 执行 skill 工具
     */
    public String executeSkillAction(String actionName, String input) {
        if (!isSkillAction(actionName)) {
            return "未知的技能工具: " + actionName;
        }
        String skillName = actionName.substring("skill_".length());
        Skill skill = skillRegistry.getSkill(skillName);
        if (skill == null) {
            return "技能不存在: " + skillName;
        }
        return skill.execute(input);
    }

    /**
     * 获取所有 skill 对应的 Action 名称列表（用于注入到 Agent 的工具列表）
     */
    public List<String> getSkillActionNames() {
        return skillRegistry.getEnabledSkills().stream()
                .map(s -> "skill_" + s.getName())
                .collect(Collectors.toList());
    }

    // ==================== 内部方法 ====================

    /**
     * LLM 智能技能选择
     */
    private List<Skill> selectByLLM(String query) {
        List<Skill> enabledSkills = skillRegistry.getEnabledSkills();
        if (enabledSkills.isEmpty()) {
            return Collections.emptyList();
        }

        // 构建技能摘要
        StringBuilder skillSummary = new StringBuilder();
        for (Skill skill : enabledSkills) {
            skillSummary.append("- ").append(skill.getName())
                    .append(": ").append(skill.getDescription());
            if (!skill.getTags().isEmpty()) {
                skillSummary.append(" [").append(String.join(",", skill.getTags())).append("]");
            }
            skillSummary.append("\n");
        }

        String prompt = "你是一个技能匹配专家。根据用户的问题，从以下可用技能中选择最合适的一个或多个技能。\n\n"
                + "可用技能:\n" + skillSummary.toString() + "\n"
                + "用户问题: " + query + "\n\n"
                + "请只输出匹配到的技能名称，多个用逗号分隔。如果没有匹配的技能，输出 NONE。"
                + "只输出技能名称，不要输出其他内容。";

        try {
            Model model;
            try {
                model = Model.valueOf(defaultModelName);
            } catch (Exception e) {
                model = Model.MINI_MAX;
            }

            List<Message> messages = new ArrayList<>();
            messages.add(new Message("system", prompt));
            messages.add(new Message("user", query));

            ModelRequest request = new ModelRequest(model, UUID.randomUUID().toString(), messages);
            ModelResponse response = ModelFacade.chatCompletion(request);

            if (!response.isSuccess() || response.getContent() == null) {
                return Collections.emptyList();
            }

            String content = response.getContent().trim();
            if ("NONE".equalsIgnoreCase(content) || content.isEmpty()) {
                return Collections.emptyList();
            }

            // 解析技能名称
            List<Skill> result = new ArrayList<>();
            String[] names = content.split("[,，]");
            for (String name : names) {
                String trimmed = name.trim();
                Skill skill = skillRegistry.getSkill(trimmed);
                if (skill != null && skillRegistry.isEnabled(trimmed)) {
                    result.add(skill);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("[SKILL-MGR] LLM技能选择失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Skill -> SkillInfo 转换 */
    private SkillInfo toSkillInfo(Skill skill) {
        SkillInfo info = new SkillInfo();
        info.setName(skill.getName());
        info.setDescription(skill.getDescription());
        info.setVersion(skill.getVersion());
        info.setAuthor(skill.getAuthor());
        info.setTags(skill.getTags());
        info.setTriggerKeywords(skill.getTriggerKeywords());
        info.setSource(skill.getSource());
        info.setEnabled(skillRegistry.isEnabled(skill.getName()));
        return info;
    }

    /** 递归删除目录 */
    private void deleteDirectory(File dir) {
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteDirectory(child);
                }
            }
        }
        dir.delete();
    }

    // ==================== 内部数据类 ====================

    /**
     * 技能信息（API 返回用）
     */
    public static class SkillInfo {
        private String name;
        private String description;
        private String version;
        private String author;
        private List<String> tags;
        private List<String> triggerKeywords;
        private String source;
        private boolean enabled;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
        public String getAuthor() { return author; }
        public void setAuthor(String author) { this.author = author; }
        public List<String> getTags() { return tags; }
        public void setTags(List<String> tags) { this.tags = tags; }
        public List<String> getTriggerKeywords() { return triggerKeywords; }
        public void setTriggerKeywords(List<String> triggerKeywords) { this.triggerKeywords = triggerKeywords; }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    /**
     * 安装结果
     */
    public static class SkillInstallResult {
        private boolean success;
        private String name;
        private String version;
        private String error;

        public static SkillInstallResult success(String name, String version) {
            SkillInstallResult r = new SkillInstallResult();
            r.success = true;
            r.name = name;
            r.version = version;
            return r;
        }

        public static SkillInstallResult failure(String error) {
            SkillInstallResult r = new SkillInstallResult();
            r.success = false;
            r.error = error;
            return r;
        }

        public boolean isSuccess() { return success; }
        public String getName() { return name; }
        public String getVersion() { return version; }
        public String getError() { return error; }
    }

    /**
     * 安装记录
     */
    private static class SkillInstallRecord {
        final String name;
        final long installedAt;
        final String installedBy;

        SkillInstallRecord(String name, long installedAt, String installedBy) {
            this.name = name;
            this.installedAt = installedAt;
            this.installedBy = installedBy;
        }
    }
}
