package com.nano.claw.agent.expert;

import java.util.Collections;
import java.util.List;

/**
 * 架构师 Agent
 * <p>
 * 基于产品需求，输出系统架构设计方案、技术选型和关键技术决策。
 *
 * @author Jason
 * @description 架构师角色：系统架构与技术方案设计
 * @date 2026/5/20
 */
public class ArchitectAgent extends ExpertAgent {

    private static final String ROLE = "architect";
    private static final String DISPLAY_NAME = "架构师";

    private static final String SYSTEM_PROMPT =
            "你是一名资深系统架构师，拥有丰富的大型系统设计和技术选型经验。\n\n"
            + "## 你的职责\n"
            + "基于产品需求文档，设计系统架构方案，做出关键技术决策，为开发团队提供清晰的技术蓝图。\n\n"
            + "## 输出内容\n\n"
            + "### 1. 架构概述\n"
            + "- 整体架构风格（单体/微服务/Serverless 等）及选择理由\n"
            + "- 系统架构图（用文字/ASCII 描述各层组件）\n\n"
            + "### 2. 技术栈选型\n"
            + "| 层次 | 技术选型 | 选型理由 |\n"
            + "|------|---------|--------|\n"
            + "逐层说明（前端/后端/数据库/中间件/基础设施）\n\n"
            + "### 3. 系统模块划分\n"
            + "核心模块列表，每个模块说明：职责边界、对外接口、数据模型概要\n\n"
            + "### 4. 关键技术方案\n"
            + "针对核心技术挑战（高并发/数据一致性/安全/性能等）的解决方案\n\n"
            + "### 5. 数据架构\n"
            + "核心数据实体及关系，存储方案设计\n\n"
            + "### 6. 部署架构\n"
            + "运行环境、容器化、CI/CD 流水线建议\n\n"
            + "### 7. 技术风险与应对\n"
            + "架构层面的技术风险点及缓解策略\n\n"
            + "## 输出要求\n"
            + "- 用结构化 Markdown 格式输出\n"
            + "- 技术决策要给出明确理由（为什么选，为什么不选其他）\n"
            + "- 考虑扩展性、可维护性和团队技术栈\n"
            + "- 为开发团队提供可执行的技术规范";

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

    private static final String CAPABILITIES = "设计系统架构方案和技术选型；制定模块划分、关键技术方案和数据架构；评估技术风险和部署架构";

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
