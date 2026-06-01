package com.nano.claw.agent.common;

/**
 * 思考步骤 - 记录 Agent 推理过程中的单个步骤
 *
 * @author Jason
 * @description 用于前端展示 Agent 思考过程
 * @date 2026/5/20
 */
public class ThinkStep {

    /**
     * 步骤类型枚举
     */
    public enum Type {
        /** 模式路由决策 */
        ROUTING,
        /** 推理/思考 */
        THOUGHT,
        /** 工具调用 */
        ACTION,
        /** 工具返回结果 */
        OBSERVATION,
        /** 规划阶段（Plan模式） */
        PLANNING,
        /** 执行步骤（Plan模式） */
        EXECUTING,
        /** 反思阶段（Reflection模式） */
        REFLECTING,
        /** 改进阶段（Reflection模式） */
        REFINING,
        /** 最终汇总 */
        SUMMARY,
        /** Chat 直接对话 */
        CHAT,
        /** 专家团：调度专家开始执行 */
        EXPERT_DISPATCH,
        /** 专家团：专家产出结果 */
        EXPERT_RESULT,
        /** 专家团：角色请求其他角色协作 */
        EXPERT_COLLABORATE,
        /** 专家团：角色拒绝参与 */
        EXPERT_DECLINE,
        /** 专家团：角色回退交互（提问/打回需求） */
        EXPERT_BOUNCE
    }

    /** 步骤类型 */
    private String type;

    /** 步骤标题 */
    private String title;

    /** 步骤详细内容 */
    private String content;

    /** 所属循环轮次（从1开始） */
    private int round;

    public ThinkStep() {
    }

    public ThinkStep(Type type, String title, String content, int round) {
        this.type = type.name().toLowerCase();
        this.title = title;
        this.content = content;
        this.round = round;
    }

    public static ThinkStep of(Type type, String title, String content, int round) {
        return new ThinkStep(type, title, content, round);
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public int getRound() { return round; }
    public void setRound(int round) { this.round = round; }
}
