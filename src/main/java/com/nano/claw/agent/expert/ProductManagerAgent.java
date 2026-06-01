package com.nano.claw.agent.expert;

import java.util.Collections;
import java.util.List;

/**
 * 产品经理 Agent
 * <p>
 * 基于销售分析结果，输出产品需求文档（PRD）、功能列表和优先级规划。
 *
 * @author Jason
 * @description 产品经理角色：需求分析与PRD生成
 * @date 2026/5/20
 */
public class ProductManagerAgent extends ExpertAgent {

    private static final String ROLE = "pm";
    private static final String DISPLAY_NAME = "产品经理";

    private static final String SYSTEM_PROMPT =
            "你是一名资深产品经理，擅长将商业需求转化为清晰的产品需求文档。\n\n"
            + "## 你的职责\n"
            + "基于商业分析结果，制定完整的产品需求规格，为技术团队提供可执行的开发指引。\n\n"
            + "## 输出内容\n"
            + "### 1. 产品概述\n"
            + "- 产品定位（一句话描述）\n"
            + "- 核心用户画像\n"
            + "- 产品目标（可量化）\n\n"
            + "### 2. 功能需求清单\n"
            + "按优先级（P0/P1/P2）列出功能模块，每个功能包含：\n"
            + "- 功能名称\n"
            + "- 用户故事（作为 [角色]，我想要 [功能]，以便 [价值]）\n"
            + "- 验收标准\n\n"
            + "### 3. 非功能性需求\n"
            + "性能、安全、兼容性、可用性等要求\n\n"
            + "### 4. 约束条件与边界\n"
            + "明确本次不做什么，范围边界\n\n"
            + "### 5. 关键业务流程\n"
            + "核心业务流程的文字描述（主流程 + 异常流程）\n\n"
            + "## 输出要求\n"
            + "- 用结构化 Markdown 格式输出\n"
            + "- 需求描述要具体可测量，避免模糊表达\n"
            + "- 区分 MVP 必要功能和增强功能\n"
            + "- 为架构师和开发团队提供足够的技术输入";

    @Override
    public String getRole() {
        return ROLE;
    }

    @Override
    public String getRoleDisplayName() {
        return DISPLAY_NAME;
    }

    @Override
    public String getRoleSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    private static final String CAPABILITIES = "将商业需求转化为产品需求文档(PRD)；定义功能清单、优先级、用户故事和验收标准；明确产品边界和非功能性需求";

    @Override
    public String getCapabilities() {
        return CAPABILITIES;
    }

    @Override
    public List<String> getRequiredContextKeys() {
        // 动态模式：返回空列表，自动读取黑板中全部已完成产出
        return Collections.emptyList();
    }
}
