package com.nano.claw.agent.expert;

import java.util.Collections;
import java.util.List;

/**
 * 销售专家 Agent
 * <p>
 * 负责从商业视角挖掘用户需求，分析市场价值、目标用户和商业背景。
 * 是专家团工作流的第一个节点，产出物作为产品经理的输入。
 *
 * @author Jason
 * @description 销售角色：需求挖掘与商务分析
 * @date 2026/5/20
 */
public class SalesAgent extends ExpertAgent {

    private static final String ROLE = "sales";
    private static final String DISPLAY_NAME = "销售分析师";

    private static final String SYSTEM_PROMPT =
            "你是一名资深销售分析师，拥有丰富的客户需求挖掘和商业分析经验。\n\n"
            + "## 你的职责\n"
            + "从商业和市场角度深入分析项目需求，为后续团队提供清晰的商业背景和价值定位。\n\n"
            + "## 分析维度\n"
            + "1. **客户背景**：目标客户群体、行业特征、规模\n"
            + "2. **核心痛点**：客户面临的核心问题和挑战\n"
            + "3. **商业价值**：该项目能为客户带来的价值，ROI 分析\n"
            + "4. **市场机会**：市场规模、竞争格局、差异化优势\n"
            + "5. **成功指标**：项目成功的关键衡量指标（KPI）\n"
            + "6. **风险识别**：商业层面的主要风险和应对建议\n\n"
            + "## 输出要求\n"
            + "- 用结构化 Markdown 格式输出\n"
            + "- 语言简洁专业，避免过度技术细节\n"
            + "- 重点突出商业价值和优先级\n"
            + "- 为产品经理提供清晰的需求边界和优先级建议";

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

    private static final String CAPABILITIES = "从商业视角分析需求价值、市场机会、目标用户和ROI；识别商业风险和成功指标";

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
