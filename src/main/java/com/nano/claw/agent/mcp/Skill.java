package com.nano.claw.agent.mcp;

import java.util.List;

/**
 * 技能接口 - 比工具更高层次的抽象
 * <p>
 * Skill 是一组预定义的 prompt + tool 组合，用于完成特定领域的任务。
 * 例如：股票分析技能 = 股票查询工具 + 分析 prompt
 * <p>
 * 支持动态安装、启用/禁用、自动选择等能力。
 *
 * @author Jason
 * @description 技能定义接口
 * @date 2026/5/19
 */
public interface Skill {

    /**
     * 技能名称（唯一标识）
     */
    String getName();

    /**
     * 技能描述，供 Agent 选择使用
     */
    String getDescription();

    /**
     * 技能的 system prompt 片段，会被注入到 Agent 的 system prompt 中
     */
    String getPromptTemplate();

    /**
     * 执行技能逻辑
     *
     * @param input 用户输入
     * @return 技能执行结果
     */
    String execute(String input);

    /**
     * 技能版本
     */
    default String getVersion() { return "1.0.0"; }

    /**
     * 技能作者
     */
    default String getAuthor() { return "unknown"; }

    /**
     * 技能分类标签，用于自动匹配
     */
    default List<String> getTags() { return java.util.Collections.emptyList(); }

    /**
     * 触发关键词，用于 IntentRuleEngine 的规则匹配
     */
    default List<String> getTriggerKeywords() { return java.util.Collections.emptyList(); }

    /**
     * 技能来源：builtin / marketplace / local
     */
    default String getSource() { return "local"; }
}