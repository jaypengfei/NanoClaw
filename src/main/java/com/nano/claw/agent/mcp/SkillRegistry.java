package com.nano.claw.agent.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 技能注册中心
 * <p>
 * 管理和查找可用的技能，支持：
 * - 技能注册/注销
 * - 启用/禁用技能
 * - 从 skills 目录自动扫描加载 Markdown 技能
 * - 按关键词/标签匹配技能
 *
 * @author Jason
 * @description 管理和查找可用的技能，支持启用/禁用和自动扫描
 * @date 2026/5/19
 */
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    /** 技能名称 -> Skill 实例 */
    private final Map<String, Skill> skills = new ConcurrentHashMap<>();

    /** 技能名称 -> 是否启用 */
    private final Map<String, Boolean> enabledStatus = new ConcurrentHashMap<>();

    /**
     * 注册技能
     */
    public void register(Skill skill) {
        skills.put(skill.getName(), skill);
        enabledStatus.putIfAbsent(skill.getName(), true); // 默认启用
        log.info("[SKILL] 注册技能: {} v{} ({})", skill.getName(), skill.getVersion(), skill.getSource());
    }

    /**
     * 注销技能
     */
    public void unregister(String name) {
        skills.remove(name);
        enabledStatus.remove(name);
        log.info("[SKILL] 注销技能: {}", name);
    }

    /**
     * 根据名称获取技能
     */
    public Skill getSkill(String name) {
        return skills.get(name);
    }

    /**
     * 判断技能是否已注册
     */
    public boolean hasSkill(String name) {
        return skills.containsKey(name);
    }

    /**
     * 启用技能
     */
    public boolean enable(String name) {
        if (!skills.containsKey(name)) return false;
        enabledStatus.put(name, true);
        log.info("[SKILL] 启用技能: {}", name);
        return true;
    }

    /**
     * 禁用技能
     */
    public boolean disable(String name) {
        if (!skills.containsKey(name)) return false;
        enabledStatus.put(name, false);
        log.info("[SKILL] 禁用技能: {}", name);
        return true;
    }

    /**
     * 判断技能是否启用
     */
    public boolean isEnabled(String name) {
        return enabledStatus.getOrDefault(name, false);
    }

    /**
     * 获取所有已注册技能
     */
    public List<Skill> getAllSkills() {
        return new ArrayList<>(skills.values());
    }

    /**
     * 获取所有已启用的技能
     */
    public List<Skill> getEnabledSkills() {
        return skills.values().stream()
                .filter(s -> enabledStatus.getOrDefault(s.getName(), false))
                .collect(Collectors.toList());
    }

    /**
     * 根据用户输入匹配最合适的技能
     * <p>
     * 匹配策略：
     * 1. 触发关键词精确匹配
     * 2. 标签模糊匹配
     * 3. 名称匹配
     *
     * @param query 用户输入
     * @return 匹配到的技能列表（按匹配度排序）
     */
    public List<Skill> matchSkills(String query) {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }

        String normalized = query.trim().toLowerCase();
        List<SkillMatch> matches = new ArrayList<>();

        for (Skill skill : getEnabledSkills()) {
            int score = 0;

            // 触发关键词匹配（高分）
            for (String keyword : skill.getTriggerKeywords()) {
                if (normalized.contains(keyword.toLowerCase())) {
                    score += 10;
                }
            }

            // 标签匹配（中分）
            for (String tag : skill.getTags()) {
                if (normalized.contains(tag.toLowerCase())) {
                    score += 5;
                }
            }

            // 名称匹配（低分）
            if (normalized.contains(skill.getName().toLowerCase())) {
                score += 3;
            }

            // 描述匹配（低分）
            if (skill.getDescription() != null && normalized.contains(skill.getDescription().toLowerCase())) {
                score += 1;
            }

            if (score > 0) {
                matches.add(new SkillMatch(skill, score));
            }
        }

        // 按分数降序排序
        matches.sort((a, b) -> Integer.compare(b.score, a.score));
        return matches.stream().map(m -> m.skill).collect(Collectors.toList());
    }

    /**
     * 生成技能描述文本（仅已启用的技能）
     */
    public String getSkillDescriptions() {
        List<Skill> enabled = getEnabledSkills();
        if (enabled.isEmpty()) {
            return "当前无可用技能。";
        }
        StringBuilder sb = new StringBuilder();
        for (Skill skill : enabled) {
            sb.append("- ").append(skill.getName())
              .append(": ").append(skill.getDescription());
            if (!skill.getTags().isEmpty()) {
                sb.append(" [tags: ").append(String.join(",", skill.getTags())).append("]");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 从指定目录扫描 skill.md 文件并加载为 MarkdownSkill
     *
     * @param skillsDir 技能目录路径
     * @return 加载的技能数量
     */
    public int scanAndLoad(String skillsDir) {
        if (skillsDir == null || skillsDir.isEmpty()) {
            return 0;
        }

        Path dir = Paths.get(skillsDir);
        if (!Files.isDirectory(dir)) {
            log.warn("[SKILL] 技能目录不存在: {}", skillsDir);
            return 0;
        }

        int count = 0;
        try {
            // 扫描子目录下的 skill.md
            File[] subDirs = dir.toFile().listFiles(File::isDirectory);
            if (subDirs != null) {
                for (File subDir : subDirs) {
                    File skillFile = new File(subDir, "skill.md");
                    if (skillFile.exists() && skillFile.isFile()) {
                        try {
                            String content = new String(Files.readAllBytes(skillFile.toPath()), "UTF-8");
                            String defaultName = subDir.getName();
                            MarkdownSkill skill = MarkdownSkill.parse(content, defaultName);
                            register(skill);
                            count++;
                        } catch (IOException e) {
                            log.error("[SKILL] 加载技能文件失败: {}", skillFile.getAbsolutePath(), e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("[SKILL] 扫描技能目录失败: {}", skillsDir, e);
        }

        log.info("[SKILL] 扫描完成，目录: {}，加载技能: {}", skillsDir, count);
        return count;
    }

    /**
     * 获取技能数量
     */
    public int size() {
        return skills.size();
    }

    /**
     * 技能匹配结果（内部类）
     */
    private static class SkillMatch {
        final Skill skill;
        final int score;

        SkillMatch(Skill skill, int score) {
            this.skill = skill;
            this.score = score;
        }
    }
}