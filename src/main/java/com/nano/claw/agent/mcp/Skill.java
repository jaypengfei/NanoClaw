package com.nano.claw.agent.mcp;

/**
 * 技能接口 - 比工具更高层次的抽象
 * <p>
 * Skill 是一组预定义的 prompt + tool 组合，用于完成特定领域的任务。
 * 例如：股票分析技能 = 股票查询工具 + 分析 prompt
 *
 * @author Jason
 * @description 技能定义接口
 * @date 2026/5/19
 */
public interface Skill {

    /**
     * 技能名称
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
}