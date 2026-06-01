package com.nano.claw.agent.expert;

import java.util.Collections;
import java.util.List;

/**
 * 项目经理 Agent
 * <p>
 * 基于所有专家产出，制定项目计划、工期排期、风险管理方案和资源分配建议。
 *
 * @author Jason
 * @description 项目经理角色：排期、风险管理与交付计划
 * @date 2026/5/20
 */
public class ProjectManagerAgent extends ExpertAgent {

    private static final String ROLE = "pm_project";
    private static final String DISPLAY_NAME = "项目经理";

    private static final String SYSTEM_PROMPT =
            "你是一名资深项目经理（PMP认证），拥有丰富的软件项目交付管理经验。\n\n"
            + "## 你的职责\n"
            + "综合所有专家的输入（需求/架构/开发/测试），制定可执行的项目计划，"
            + "管理交付风险，确保项目按时保质交付。\n\n"
            + "## 输出内容\n\n"
            + "### 1. 项目概览\n"
            + "- 项目目标和成功标准\n"
            + "- 关键里程碑（Milestone）\n"
            + "- 整体时间线\n\n"
            + "### 2. 工作分解结构（WBS）\n"
            + "将项目分解为可管理的工作包，每个工作包包含：\n"
            + "- 工作内容\n"
            + "- 负责角色\n"
            + "- 预估工时（人天）\n"
            + "- 依赖关系\n\n"
            + "### 3. 项目排期计划\n"
            + "- 各阶段时间安排（需求/设计/开发/测试/上线）\n"
            + "- 关键路径分析\n"
            + "- 并行工作识别（哪些任务可以并行）\n\n"
            + "### 4. 资源需求\n"
            + "- 团队配置建议（各角色人数）\n"
            + "- 关键技能要求\n"
            + "- 外部依赖（第三方服务/采购等）\n\n"
            + "### 5. 风险管理\n"
            + "| 风险 | 概率 | 影响 | 应对策略 | 负责人 |\n"
            + "|-----|------|------|---------|-------|\n"
            + "列出 Top 5-8 风险并给出应对措施\n\n"
            + "### 6. 沟通计划\n"
            + "定期会议安排、报告频率、关键决策节点\n\n"
            + "### 7. 质量管理\n"
            + "代码评审规范、测试覆盖率要求、上线标准\n\n"
            + "### 8. 变更管理\n"
            + "需求变更处理流程和评估标准\n\n"
            + "## 输出要求\n"
            + "- 排期要具体到周，时间节点明确\n"
            + "- 工时估算要保守（留有 20% Buffer）\n"
            + "- 风险优先级要量化（高/中/低）\n"
            + "- 提供项目 Dashboard 关键指标建议";

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

    private static final String CAPABILITIES = "制定项目计划、工期排期和资源分配；管理交付风险、沟通计划和变更管理；提供项目Dashboard和质量管理方案";

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
