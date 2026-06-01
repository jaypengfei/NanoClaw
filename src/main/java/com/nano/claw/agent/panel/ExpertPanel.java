package com.nano.claw.agent.panel;

import com.nano.claw.agent.expert.ArchitectAgent;
import com.nano.claw.agent.expert.DeveloperAgent;
import com.nano.claw.agent.expert.ExpertAgent;
import com.nano.claw.agent.expert.ProductManagerAgent;
import com.nano.claw.agent.expert.ProjectManagerAgent;
import com.nano.claw.agent.expert.SalesAgent;
import com.nano.claw.agent.expert.TesterAgent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 专家组注册与管理
 * <p>
 * 维护所有 Expert Agent 的注册表，支持两种模式：
 * 1. 静态工作流模式（softwareDevelopmentPanel）：预定义 DAG 工作流
 * 2. 动态协作模式（createDynamicPanel）：角色自行决定协作关系，形成动态图
 *
 * @author Jason
 * @description Expert Panel 专家组注册与工作流定义
 * @date 2026/5/20
 */
public class ExpertPanel {

    /** role -> ExpertAgent 注册表 */
    private final Map<String, ExpertAgent> agents = new LinkedHashMap<>();

    /** 工作流节点列表 */
    private final List<WorkflowNode> workflowNodes = new ArrayList<>();

    /** 是否为动态协作模式 */
    private boolean dynamicMode = false;

    public ExpertPanel() {
    }

    /**
     * 创建动态协作模式的 ExpertPanel
     */
    public ExpertPanel(boolean dynamicMode) {
        this.dynamicMode = dynamicMode;
    }

    /**
     * 注册一个专家 Agent
     *
     * @param agent 专家 Agent 实例
     * @return this（链式调用）
     */
    public ExpertPanel register(ExpertAgent agent) {
        agents.put(agent.getRole(), agent);
        return this;
    }

    /**
     * 添加工作流节点
     *
     * @param node 工作流节点
     * @return this（链式调用）
     */
    public ExpertPanel addNode(WorkflowNode node) {
        workflowNodes.add(node);
        return this;
    }

    /**
     * 根据角色获取专家 Agent
     *
     * @param role 角色标识
     * @return ExpertAgent，不存在返回 null
     */
    public ExpertAgent getAgent(String role) {
        return agents.get(role);
    }

    /**
     * 获取所有工作流节点
     *
     * @return 工作流节点列表（不可修改副本）
     */
    public List<WorkflowNode> getWorkflowNodes() {
        return new ArrayList<>(workflowNodes);
    }

    /**
     * 构建 WorkflowPlanner
     *
     * @return DAG 调度引擎
     */
    public WorkflowPlanner buildPlanner() {
        return new WorkflowPlanner(workflowNodes);
    }

    /**
     * 判断是否为动态协作模式
     */
    public boolean isDynamicMode() {
        return dynamicMode;
    }

    /**
     * 获取所有已注册的角色标识列表
     */
    public List<String> getRegisteredRoles() {
        return new ArrayList<>(agents.keySet());
    }

    /**
     * 获取所有已注册角色的能力描述
     * <p>
     * 返回 Map<角色标识, 能力描述>
     */
    public Map<String, String> getRoleCapabilities() {
        Map<String, String> capabilities = new LinkedHashMap<>();
        for (Map.Entry<String, ExpertAgent> entry : agents.entrySet()) {
            capabilities.put(entry.getKey(), entry.getValue().getCapabilities());
        }
        return capabilities;
    }

    // ==================== 工厂方法 ====================

    /**
     * 创建动态协作模式专家团
     * <p>
     * 只注册所有可用角色，不预定义工作流。
     * 执行时由角色自行决定需要哪些角色配合，形成动态协作图。
     * <p>
     * 协作流程：
     * 1. 路由器根据用户需求选择初始角色
     * 2. 初始角色执行后声明需要哪些角色协作
     * 3. 目标角色根据上下文判断是否参与
     * 4. 无需角色不出现，角色间可回退交互
     *
     * @return 动态协作模式 ExpertPanel
     */
    public static ExpertPanel createDynamicPanel() {
        ExpertPanel panel = new ExpertPanel(true);

        // 只注册角色，不定义工作流
        panel.register(new SalesAgent());
        panel.register(new ProductManagerAgent());
        panel.register(new ArchitectAgent());
        panel.register(new DeveloperAgent());
        panel.register(new TesterAgent());
        panel.register(new ProjectManagerAgent());

        return panel;
    }

    /**
     * 创建软件开发全流程专家团（静态工作流模式，向后兼容）
     * <p>
     * 包含：销售分析师 → 产品经理 → 架构师 → 开发工程师 → 测试工程师 → 项目经理
     * <p>
     * 工作流 DAG：
     * <pre>
     * sales ──> pm ──> architect ──> developer ──> tester
     *                                          ↘
     *           pm ──────────────────────────> pm_project
     *                  architect ─────────────>
     *                             developer ──>
     *                                  tester ─>
     * </pre>
     *
     * @return 已配置好的 ExpertPanel
     */
    public static ExpertPanel softwareDevelopmentPanel() {
        ExpertPanel panel = new ExpertPanel();

        // 1. 注册所有专家 Agent
        panel.register(new SalesAgent());
        panel.register(new ProductManagerAgent());
        panel.register(new ArchitectAgent());
        panel.register(new DeveloperAgent());
        panel.register(new TesterAgent());
        panel.register(new ProjectManagerAgent());

        // 2. 定义工作流 DAG 节点及依赖关系
        // 阶段一：销售分析（无依赖，首个节点）
        WorkflowNode salesNode = new WorkflowNode("sales", "sales", "销售分析师");

        // 阶段二：产品经理（依赖销售分析）
        WorkflowNode pmNode = new WorkflowNode("pm", "pm", "产品经理");
        pmNode.dependsOn("sales");

        // 阶段三：架构师（依赖产品经理）
        WorkflowNode archNode = new WorkflowNode("architect", "architect", "架构师");
        archNode.dependsOn("pm");

        // 阶段四：开发工程师（依赖架构师）
        WorkflowNode devNode = new WorkflowNode("developer", "developer", "开发工程师");
        devNode.dependsOn("architect");

        // 阶段五：测试工程师（依赖产品经理+开发工程师）
        WorkflowNode testerNode = new WorkflowNode("tester", "tester", "测试工程师");
        testerNode.dependsOn("developer");

        // 阶段六：项目经理（依赖产品经理+架构师+开发+测试，最后汇聚）
        WorkflowNode pmProjectNode = new WorkflowNode("pm_project", "pm_project", "项目经理");
        pmProjectNode.dependsOn("tester");

        panel.addNode(salesNode);
        panel.addNode(pmNode);
        panel.addNode(archNode);
        panel.addNode(devNode);
        panel.addNode(testerNode);
        panel.addNode(pmProjectNode);

        return panel;
    }
}
