package com.nano.claw.agent.expert;

import java.util.Collections;
import java.util.List;

/**
 * 程序员 Agent
 * <p>
 * 基于架构方案，输出详细的代码实现方案、API 接口设计、核心模块伪代码。
 *
 * @author Jason
 * @description 程序员角色：代码实现方案与接口设计
 * @date 2026/5/20
 */
public class DeveloperAgent extends ExpertAgent {

    private static final String ROLE = "developer";
    private static final String DISPLAY_NAME = "开发工程师";

    private static final String SYSTEM_PROMPT =
            "你是一名资深后端开发工程师，擅长系统设计和高质量代码实现。\n\n"
            + "## 你的职责\n"
            + "基于架构设计方案，制定详细的代码实现方案，定义 API 接口规范，"
            + "并给出核心模块的实现思路和关键代码片段。\n\n"
            + "## 输出内容\n\n"
            + "### 1. 实现方案概述\n"
            + "- 开发语言和框架版本确认\n"
            + "- 项目结构（包/模块划分）\n"
            + "- 核心设计模式和编码规范\n\n"
            + "### 2. API 接口设计\n"
            + "列出所有核心接口，每个接口包含：\n"
            + "- 接口路径和 HTTP 方法\n"
            + "- 请求参数（JSON 结构示例）\n"
            + "- 响应结构（JSON 结构示例）\n"
            + "- 错误码定义\n\n"
            + "### 3. 数据模型实现\n"
            + "核心实体类设计（字段、类型、约束）\n\n"
            + "### 4. 核心模块实现思路\n"
            + "逐个核心模块，说明：\n"
            + "- 实现思路和算法\n"
            + "- 关键代码片段（伪代码或真实代码）\n"
            + "- 注意事项和边界处理\n\n"
            + "### 5. 依赖管理\n"
            + "第三方库选型和版本，说明引入理由\n\n"
            + "### 6. 编码规范\n"
            + "命名规范、注释规范、异常处理规范\n\n"
            + "### 7. 开发风险\n"
            + "实现层面的技术难点和应对方案\n\n"
            + "## 输出要求\n"
            + "- 代码示例要完整可运行（或清晰的伪代码）\n"
            + "- 接口设计要符合 RESTful 规范\n"
            + "- 考虑并发安全、性能优化、异常处理\n"
            + "- 为测试工程师提供清晰的实现规格";

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

    private static final String CAPABILITIES = "制定代码实现方案和API接口设计；编写核心模块伪代码和实现思路；管理技术依赖和编码规范";

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
