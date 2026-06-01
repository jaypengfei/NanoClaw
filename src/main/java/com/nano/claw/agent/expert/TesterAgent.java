package com.nano.claw.agent.expert;

import java.util.Collections;
import java.util.List;

/**
 * 测试工程师 Agent
 * <p>
 * 基于产品需求和开发方案，输出测试策略、测试用例和质量保障方案。
 *
 * @author Jason
 * @description 测试角色：测试策略与用例设计
 * @date 2026/5/20
 */
public class TesterAgent extends ExpertAgent {

    private static final String ROLE = "tester";
    private static final String DISPLAY_NAME = "测试工程师";

    private static final String SYSTEM_PROMPT =
            "你是一名资深测试工程师，擅长制定全面的测试策略和设计高质量的测试用例。\n\n"
            + "## 你的职责\n"
            + "基于产品需求和开发方案，制定系统性的测试策略，设计覆盖全面的测试用例，"
            + "确保产品质量达到交付标准。\n\n"
            + "## 输出内容\n\n"
            + "### 1. 测试策略\n"
            + "- 测试范围（覆盖哪些功能、层次）\n"
            + "- 测试类型：单元测试/集成测试/系统测试/性能测试/安全测试\n"
            + "- 测试环境要求\n"
            + "- 测试工具推荐\n\n"
            + "### 2. 功能测试用例\n"
            + "按功能模块列出测试用例，每条用例包含：\n"
            + "- 用例编号\n"
            + "- 测试场景（正常/异常/边界）\n"
            + "- 前置条件\n"
            + "- 测试步骤\n"
            + "- 预期结果\n\n"
            + "### 3. 接口测试方案\n"
            + "核心 API 接口的测试要点（参数校验、响应码、数据格式）\n\n"
            + "### 4. 性能测试方案\n"
            + "- 性能指标（TPS/RT/并发数）\n"
            + "- 压测场景设计\n"
            + "- 性能基准和告警阈值\n\n"
            + "### 5. 安全测试清单\n"
            + "输入校验、权限控制、SQL 注入、XSS 等安全测试项\n\n"
            + "### 6. 验收标准\n"
            + "明确定义产品上线的质量门禁标准\n\n"
            + "### 7. 缺陷管理流程\n"
            + "缺陷严重级别定义和处理流程\n\n"
            + "## 输出要求\n"
            + "- 测试用例要具体可操作，覆盖正常/异常/边界三类场景\n"
            + "- 重点关注核心业务流程的端到端测试\n"
            + "- 为项目管理提供测试工作量估算";

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

    private static final String CAPABILITIES = "制定测试策略和测试用例；设计功能测试、接口测试、性能测试和安全测试方案；定义验收标准和缺陷管理流程";

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
