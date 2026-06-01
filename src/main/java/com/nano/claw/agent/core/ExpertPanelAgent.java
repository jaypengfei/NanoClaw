package com.nano.claw.agent.core;

import com.nano.claw.agent.common.AgentRequest;
import com.nano.claw.agent.common.AgentResponse;
import com.nano.claw.agent.common.ThinkStep;
import com.nano.claw.agent.expert.ExpertAgent;
import com.nano.claw.agent.panel.AgentContext;
import com.nano.claw.agent.panel.CollaborationRequest;
import com.nano.claw.agent.panel.CollaborationResponse;
import com.nano.claw.agent.panel.ExpertPanel;
import com.nano.claw.agent.panel.PanelResult;
import com.nano.claw.agent.panel.WorkflowNode;
import com.nano.claw.agent.panel.WorkflowPlanner;
import com.nano.claw.llm.Model;
import com.nano.claw.llm.ModelFacade;
import com.nano.claw.llm.ModelRequest;
import com.nano.claw.llm.ModelResponse;
import com.nano.claw.messages.Message;
import com.nano.claw.workspace.ProjectWorkspace;
import com.nano.claw.workspace.RoleWorkspace;
import com.nano.claw.workspace.WorkspaceArtifact;
import com.nano.claw.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

/**
 * 专家团主控 Agent
 * <p>
 * 负责协调多个专家角色 Agent 完成复杂任务（以软件开发全流程为例）。
 * <p>
 * 执行流程：
 * <ol>
 *   <li>初始化 ExpertPanel（注册专家+工作流定义）</li>
 *   <li>WorkflowPlanner 进行 DAG 拓扑排序，生成执行批次</li>
 *   <li>按批次串行执行（批次内节点可扩展为并行）</li>
 *   <li>每个节点：从 AgentContext 读取前驱产出 → 调用 ExpertAgent → 写回黑板</li>
 *   <li>所有节点完成后，调用 LLM 聚合生成最终综合报告</li>
 * </ol>
 *
 * @author Jason
 * @description Expert Panel 主控 Agent，协调多专家协作
 * @date 2026/5/20
 */
public class ExpertPanelAgent extends Agent {

    private static final Logger log = LoggerFactory.getLogger(ExpertPanelAgent.class);

    /** Spring ApplicationContext，用于获取 WorkspaceManager Bean */
    private static ApplicationContext applicationContext;

    /** 动态协作模式最大迭代轮次 */
    private static final int MAX_DYNAMIC_ITERATIONS = 10;

    /** 同一角色最大回退交互次数 */
    private static final int MAX_BOUNCE_PER_ROLE = 2;

    /**
     * 注入 Spring ApplicationContext
     */
    public static void setApplicationContext(ApplicationContext ctx) {
        applicationContext = ctx;
    }

    /**
     * 获取 WorkspaceManager Bean
     */
    private WorkspaceManager getWorkspaceManager() {
        if (applicationContext == null) return null;
        return applicationContext.getBean(WorkspaceManager.class);
    }

    /**
     * 主流程入口
     * <p>
     * 默认使用动态协作模式：角色自行决定协作关系，形成动态图。
     * 保留静态工作流模式作为向后兼容。
     */
    @Override
    public AgentResponse run(AgentRequest agentRequest) {
        // 使用动态协作模式
        return runDynamic(agentRequest);
    }

    /**
     * 动态协作模式执行
     * <p>
     * 核心流程：
     * 1. 初始化专家团（只注册角色，不预定义工作流）
     * 2. 通过 LLM 路由选择初始角色
     * 3. 初始角色执行后声明需要哪些角色协作
     * 4. 目标角色根据上下文判断是否参与（接受/拒绝）
     * 5. 已完成角色可被其他角色回退交互（提问/打回需求）
     * 6. 循环直到无新的协作请求
     * 7. 聚合产出生成最终报告
     */
    private AgentResponse runDynamic(AgentRequest agentRequest) {
        String traceId = newTraceId();
        AgentResponse result = new AgentResponse();
        result.setTraceId(traceId);

        AgentResponse validation = validateRequest(agentRequest);
        if (validation != null) {
            return validation;
        }

        long startTime = System.currentTimeMillis();

        // ========== 1. 初始化动态专家团 & AgentContext ==========
        ExpertPanel panel = ExpertPanel.createDynamicPanel();

        WorkspaceManager workspaceManager = getWorkspaceManager();
        ProjectWorkspace projectWorkspace = null;
        String projectId = agentRequest.getSessionId();

        if (workspaceManager != null && projectId != null) {
            projectWorkspace = workspaceManager.getWorkspace(projectId);
            if (projectWorkspace == null) {
                String projectName = truncate(agentRequest.getQuery(), 30);
                projectWorkspace = workspaceManager.createWorkspace(projectName, agentRequest.getQuery());
                projectId = projectWorkspace.getId();
                log.info("[PANEL-DYNAMIC] 自动创建项目工作区: {} ({})", projectName, projectId);
            }
            projectWorkspace.setStatus(ProjectWorkspace.WorkspaceStatus.RUNNING.name().toLowerCase());
            workspaceManager.updateWorkspace(projectWorkspace);
        }

        AgentContext context = new AgentContext(
                agentRequest.getSessionId() != null ? agentRequest.getSessionId() : UUID.randomUUID().toString(),
                agentRequest.getQuery(),
                projectWorkspace != null ? projectWorkspace.getId() : null
        );

        // ========== 2. 通过 LLM 路由选择初始角色 ==========
        List<String> initialRoles = determineInitialRoles(agentRequest.getQuery(), panel, agentRequest.getModel());

        addThinkStep(agentRequest, result, ThinkStep.of(
                ThinkStep.Type.EXPERT_DISPATCH,
                "专家团动态协作启动",
                "初始角色: " + String.join(", ", initialRoles) + "\n"
                        + "可用角色池: " + String.join(", ", panel.getRegisteredRoles()) + "\n"
                        + "将由角色自行决定协作关系...",
                0
        ));

        log.info("[PANEL-DYNAMIC] 专家团启动，需求: {}，初始角色: {}",
                truncate(agentRequest.getQuery()), initialRoles);

        // ========== 3. 动态协作执行循环 ==========
        Queue<CollaborationRequest> pendingRequests = new LinkedList<>();
        Map<String, Integer> bounceCount = new HashMap<>();  // 角色回退次数统计

        // 将初始角色作为系统发起的协作请求
        for (String role : initialRoles) {
            CollaborationRequest initialReq = CollaborationRequest.collaborate(
                    "system", role, "初始任务分配", agentRequest.getQuery());
            pendingRequests.add(initialReq);
            context.addCollaborationRequest(initialReq);
        }

        int iteration = 0;
        while (!pendingRequests.isEmpty() && iteration < MAX_DYNAMIC_ITERATIONS) {
            iteration++;
            CollaborationRequest request = pendingRequests.poll();
            String targetRole = request.getToRole();

            log.info("[PANEL-DYNAMIC] 第 {} 轮，处理协作请求: {} --{}-> {}",
                    iteration, request.getFromRole(), request.getRequestType(), targetRole);

            if (request.getRequestType() == CollaborationRequest.RequestType.COLLABORATE) {
                // ===== 正向协作请求 =====
                if (context.isRoleActivated(targetRole)) {
                    log.info("[PANEL-DYNAMIC] 角色 {} 已激活，跳过", targetRole);
                    continue;
                }

                // 目标角色评估是否参与
                ExpertAgent targetAgent = panel.getAgent(targetRole);
                if (targetAgent == null) {
                    log.warn("[PANEL-DYNAMIC] 找不到角色: {}", targetRole);
                    continue;
                }

                CollaborationResponse response;
                if ("system".equals(request.getFromRole())) {
                    // 系统分配的初始角色默认接受
                    response = CollaborationResponse.accept(targetRole, "system",
                            "系统初始分配", request);
                } else {
                    // 其他角色请求协作，由目标角色自行判断
                    response = targetAgent.evaluateRelevance(
                            context.getOriginalRequest(),
                            request.getFromRole(),
                            request.getReason(),
                            context,
                            agentRequest.getModel()
                    );
                }

                context.addCollaborationResponse(response);

                if (response.getDecision() == CollaborationResponse.Decision.DECLINE) {
                    // 角色拒绝参与
                    addThinkStep(agentRequest, result, ThinkStep.of(
                            ThinkStep.Type.EXPERT_DECLINE,
                            "【" + targetAgent.getRoleDisplayName() + "】拒绝参与",
                            "理由: " + response.getReason(),
                            0
                    ));
                    log.info("[PANEL-DYNAMIC] 角色 {} 拒绝参与: {}", targetRole, response.getReason());
                    continue;
                }

                // 角色接受，执行并收集协作需求
                addThinkStep(agentRequest, result, ThinkStep.of(
                        ThinkStep.Type.EXPERT_COLLABORATE,
                        "【" + targetAgent.getRoleDisplayName() + "】接受协作",
                        "来自: " + request.getFromRole() + "\n理由: " + response.getReason(),
                        0
                ));

                String output = executeDynamicNode(targetRole, targetAgent, context,
                        agentRequest, result, projectWorkspace, workspaceManager);

                // 角色执行后声明进一步协作需求
                Map<String, String> availableRoles = panel.getRoleCapabilities();
                List<CollaborationRequest> newCollabRequests =
                        targetAgent.declareCollaborationNeeds(output, context, availableRoles, agentRequest.getModel());
                result.addTokens(0); // 额外 LLM 调用的 token 已在内部累加

                for (CollaborationRequest newReq : newCollabRequests) {
                    context.addCollaborationRequest(newReq);
                    pendingRequests.add(newReq);
                    addThinkStep(agentRequest, result, ThinkStep.of(
                            ThinkStep.Type.EXPERT_COLLABORATE,
                            "【" + targetAgent.getRoleDisplayName() + "】请求协作",
                            "目标: " + newReq.getToRole() + "\n理由: " + newReq.getReason(),
                            0
                    ));
                }

                // 角色执行后声明回退交互需求（提问/打回）
                List<CollaborationRequest> bounceRequests =
                        targetAgent.declareBounceNeeds(output, context, agentRequest.getModel());

                for (CollaborationRequest bounceReq : bounceRequests) {
                    int count = bounceCount.getOrDefault(bounceReq.getToRole(), 0);
                    if (count < MAX_BOUNCE_PER_ROLE) {
                        context.addCollaborationRequest(bounceReq);
                        pendingRequests.add(bounceReq);
                        bounceCount.put(bounceReq.getToRole(), count + 1);
                        addThinkStep(agentRequest, result, ThinkStep.of(
                                ThinkStep.Type.EXPERT_BOUNCE,
                                "【" + targetAgent.getRoleDisplayName() + "】" + (bounceReq.getRequestType() == CollaborationRequest.RequestType.QUESTION ? "提出疑问" : "打回需求"),
                                "目标: " + bounceReq.getToRole() + "\n内容: " + bounceReq.getReason(),
                                0
                        ));
                    }
                }

            } else {
                // ===== 回退交互请求（QUESTION / FEEDBACK） =====
                ExpertAgent targetAgent = panel.getAgent(targetRole);
                if (targetAgent == null || !context.isRoleCompleted(targetRole)) {
                    log.warn("[PANEL-DYNAMIC] 回退目标角色 {} 未完成或不存在，跳过", targetRole);
                    continue;
                }

                // 收集对同一角色的所有回退请求
                List<CollaborationRequest> bounceForRole = new ArrayList<>();
                bounceForRole.add(request);

                // 重新执行该角色（带回退上下文）
                addThinkStep(agentRequest, result, ThinkStep.of(
                        ThinkStep.Type.EXPERT_BOUNCE,
                        "【" + targetAgent.getRoleDisplayName() + "】处理反馈",
                        "来自: " + request.getFromRole() + "\n类型: " + request.getRequestType()
                                + "\n内容: " + request.getReason(),
                        0
                ));

                String updatedOutput = targetAgent.executeWithBounceContext(
                        context, agentRequest, result, bounceForRole);
                context.publish(targetRole, updatedOutput);
                result.addTokens(0);

                // 更新工作区产出物
                if (projectWorkspace != null && workspaceManager != null) {
                    String artifactId = targetRole + "_bounce_" + System.currentTimeMillis();
                    WorkspaceArtifact artifact = WorkspaceArtifact.of(
                            artifactId, getArtifactName(targetRole), getArtifactType(targetRole),
                            "markdown", updatedOutput, targetRole
                    );
                    workspaceManager.addArtifact(context.getProjectId(), targetRole, artifact);
                }

                log.info("[PANEL-DYNAMIC] 角色 {} 已根据反馈更新产出", targetRole);
            }
        }

        if (iteration >= MAX_DYNAMIC_ITERATIONS) {
            log.warn("[PANEL-DYNAMIC] 达到最大迭代轮次 {}，终止协作循环", MAX_DYNAMIC_ITERATIONS);
        }

        // ========== 4. 聚合所有产出，生成最终报告 ==========
        Map<String, String> artifacts = context.getAllArtifacts();

        addThinkStep(agentRequest, result, ThinkStep.of(
                ThinkStep.Type.SUMMARY,
                "专家团动态协作完成",
                "参与角色: " + String.join(", ", context.getActivatedRoles()) + "\n"
                        + (context.getDeclinedRoles().isEmpty() ? "" : "拒绝角色: " + String.join(", ", context.getDeclinedRoles()) + "\n")
                        + "协作图:\n" + context.buildCollaborationGraphSummary(),
                0
        ));

        String finalReport = generateDynamicFinalReport(context, agentRequest);

        // 持久化综合报告到工作区
        if (projectWorkspace != null && workspaceManager != null) {
            workspaceManager.saveFinalReport(projectWorkspace.getId(), finalReport);
            projectWorkspace.setFinalReport(finalReport);
        }

        long totalDuration = System.currentTimeMillis() - startTime;

        addThinkStep(agentRequest, result, ThinkStep.of(
                ThinkStep.Type.EXPERT_RESULT,
                "综合交付报告",
                finalReport,
                0
        ));

        result.setAnswer(finalReport);
        result.setSuccess(true);
        result.setLoopCount(context.getActivatedRoles().size());

        // 更新项目工作区状态
        if (projectWorkspace != null && workspaceManager != null) {
            projectWorkspace.setStatus(ProjectWorkspace.WorkspaceStatus.COMPLETED.name().toLowerCase());
            projectWorkspace.setTotalDurationMs(System.currentTimeMillis() - startTime);
            projectWorkspace.setTotalTokens(result.getTotalTokens());
            projectWorkspace.setCompletedAt(System.currentTimeMillis());
            workspaceManager.updateWorkspace(projectWorkspace);
            result.setTraceId(result.getTraceId() + ":" + projectWorkspace.getId());
        }

        log.info("[PANEL-DYNAMIC] 专家团动态协作完成，耗时: {}ms，参与角色: {}，拒绝角色: {}",
                totalDuration, context.getActivatedRoles().size(), context.getDeclinedRoles().size());
        return result;
    }

    /**
     * 通过 LLM 确定初始参与角色
     *
     * @param query 用户需求
     * @param panel 专家组
     * @param model 使用的模型
     * @return 初始角色标识列表
     */
    private List<String> determineInitialRoles(String query, ExpertPanel panel, Model model) {
        Map<String, String> capabilities = panel.getRoleCapabilities();

        String systemPrompt = "你是一个项目协调者，需要根据项目需求选择最合适的起始角色。\n"
                + "请选择1-2个最适合首先开始工作的角色。\n"
                + "严格按照以下 JSON 数组格式回复（不要其他内容）：\n"
                + "[\"角色标识1\", \"角色标识2\"]";

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("## 项目需求\n\n").append(query).append("\n\n");
        userPrompt.append("## 可选角色\n\n");
        for (Map.Entry<String, String> entry : capabilities.entrySet()) {
            userPrompt.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        userPrompt.append("\n请选择最合适的起始角色。");

        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", systemPrompt));
        messages.add(new Message("user", userPrompt.toString()));

        ModelRequest modelRequest = new ModelRequest(model, newTraceId(), messages);
        ModelResponse modelResponse = ModelFacade.chatCompletion(modelRequest);

        if (!modelResponse.isSuccess()) {
            log.warn("[PANEL-DYNAMIC] 初始角色路由失败，默认使用产品经理");
            List<String> defaults = new ArrayList<>();
            defaults.add("pm");
            return defaults;
        }

        String content = modelResponse.getContent().trim();
        return parseRoleList(content, panel.getRegisteredRoles());
    }

    /**
     * 解析 LLM 返回的角色列表
     */
    private List<String> parseRoleList(String content, List<String> validRoles) {
        List<String> roles = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            String json = content.trim();
            if (json.startsWith("```")) {
                int start = json.indexOf('[');
                int end = json.lastIndexOf(']');
                if (start >= 0 && end > start) {
                    json = json.substring(start, end + 1);
                }
            }
            // 简单解析 JSON 字符串数组
            json = json.replace("[",  "").replace("]",  "").replace("\"", "").replace("'", "");
            String[] parts = json.split(",");
            for (String part : parts) {
                String role = part.trim();
                if (validRoles.contains(role) && !seen.contains(role)) {
                    roles.add(role);
                    seen.add(role);
                }
            }
        } catch (Exception e) {
            log.warn("[PANEL-DYNAMIC] 角色列表解析失败: {}", content);
        }
        if (roles.isEmpty()) {
            roles.add("pm"); // 降级默认
        }
        return roles;
    }

    /**
     * 动态模式下执行单个角色节点
     */
    private String executeDynamicNode(String role, ExpertAgent expertAgent, AgentContext context,
                                        AgentRequest agentRequest, AgentResponse result,
                                        ProjectWorkspace projectWorkspace, WorkspaceManager workspaceManager) {
        context.activateRole(role);
        long nodeStart = System.currentTimeMillis();

        // 更新角色工作区状态
        if (projectWorkspace != null && workspaceManager != null) {
            RoleWorkspace rw = projectWorkspace.getOrCreateRoleWorkspace(role,
                    expertAgent.getRoleDisplayName(), getRoleEmoji(role));
            rw.setStatus(RoleWorkspace.RoleStatus.WORKING.name().toLowerCase());
            rw.setStartedAt(nodeStart);
            workspaceManager.updateWorkspace(projectWorkspace);
        }

        log.info("[PANEL-DYNAMIC] >>> 开始执行角色: {} ({})", expertAgent.getRoleDisplayName(), role);

        try {
            String output = expertAgent.executeInContext(context, agentRequest, result);
            context.publish(role, output);
            context.completeRole(role);

            // 持久化产出物
            if (projectWorkspace != null && workspaceManager != null) {
                String artifactId = role + "_" + System.currentTimeMillis();
                WorkspaceArtifact artifact = WorkspaceArtifact.of(
                        artifactId, getArtifactName(role), getArtifactType(role),
                        "markdown", output, role
                );
                workspaceManager.addArtifact(context.getProjectId(), role, artifact);

                RoleWorkspace rw = projectWorkspace.getRoleWorkspace(role);
                if (rw != null) {
                    rw.setStatus(RoleWorkspace.RoleStatus.DONE.name().toLowerCase());
                    rw.setCompletedAt(System.currentTimeMillis());
                    rw.setDurationMs(System.currentTimeMillis() - nodeStart);
                }
                workspaceManager.updateWorkspace(projectWorkspace);
            }

            log.info("[PANEL-DYNAMIC] <<< 角色完成: {} ({}), 耗时: {}ms, 产出长度: {}字",
                    expertAgent.getRoleDisplayName(), role,
                    System.currentTimeMillis() - nodeStart, output.length());
            return output;

        } catch (Exception e) {
            context.completeRole(role);

            if (projectWorkspace != null && workspaceManager != null) {
                RoleWorkspace rw = projectWorkspace.getRoleWorkspace(role);
                if (rw != null) {
                    rw.setStatus(RoleWorkspace.RoleStatus.FAILED.name().toLowerCase());
                    rw.setError(e.getMessage());
                    rw.setDurationMs(System.currentTimeMillis() - nodeStart);
                }
                workspaceManager.updateWorkspace(projectWorkspace);
            }

            log.error("[PANEL-DYNAMIC] 角色执行失败: {} ({}), 错误: {}",
                    expertAgent.getRoleDisplayName(), role, e.getMessage());

            String errorOutput = "【" + expertAgent.getRoleDisplayName() + "】执行异常: " + e.getMessage();
            context.publish(role, errorOutput);

            addThinkStep(agentRequest, result, ThinkStep.of(
                    ThinkStep.Type.EXPERT_RESULT,
                    "【" + expertAgent.getRoleDisplayName() + "】执行异常",
                    "错误: " + e.getMessage(),
                    0
            ));
            return errorOutput;
        }
    }

    /**
     * 动态模式下的最终报告生成
     */
    private String generateDynamicFinalReport(AgentContext context, AgentRequest agentRequest) {
        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("## 原始项目需求\n\n").append(context.getOriginalRequest()).append("\n\n");
        userPrompt.append("---\n\n");
        userPrompt.append("## 协作过程\n\n");
        userPrompt.append(context.buildCollaborationGraphSummary()).append("\n\n");
        userPrompt.append("---\n\n");
        userPrompt.append("## 各专家分析结果\n\n");

        Map<String, String> artifacts = context.getAllArtifacts();
        for (Map.Entry<String, String> entry : artifacts.entrySet()) {
            String role = entry.getKey();
            String artifact = entry.getValue();
            if (artifact != null && !artifact.trim().isEmpty()) {
                userPrompt.append("### ").append(getRoleDisplayName(role)).append("\n\n");
                userPrompt.append(artifact).append("\n\n---\n\n");
            }
        }

        userPrompt.append("请基于以上所有专家的分析，生成一份完整的项目综合交付报告。");
        userPrompt.append("报告需要包含：执行摘要、关键决策要点、整体方案概览、实施建议和下一步行动计划。");
        userPrompt.append("格式要清晰，使用 Markdown。");

        String systemPrompt = "你是一位资深的项目总监，负责汇总专家团的意见，生成精炼的项目综合报告。\n"
                + "报告要体现各专家观点的整合与统一，突出关键决策和行动项，语言简洁有力，便于高层决策者阅读。";

        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", systemPrompt));
        messages.add(new Message("user", userPrompt.toString()));

        ModelRequest modelRequest = new ModelRequest(agentRequest.getModel(), newTraceId(), messages);
        ModelResponse modelResponse = ModelFacade.chatCompletion(modelRequest);

        if (!modelResponse.isSuccess()) {
            return buildFallbackReport(context);
        }

        return modelResponse.getContent();
    }

    /**
     * 获取角色展示名称（动态模式用，不再硬编码顺序）
     */
    private String getRoleDisplayName(String role) {
        switch (role) {
            case "sales":      return "销售分析师";
            case "pm":         return "产品经理";
            case "architect":  return "架构师";
            case "developer":  return "开发工程师";
            case "tester":     return "测试工程师";
            case "pm_project": return "项目经理";
            default:           return role;
        }
    }

    /**
     * 执行单个工作流节点
     *
     * @param node         工作流节点
     * @param panel        专家组
     * @param context      共享黑板
     * @param agentRequest 原始请求
     * @param result       用于收集 ThinkStep
     */
    private void executeNode(WorkflowNode node, ExpertPanel panel, AgentContext context,
                              AgentRequest agentRequest, AgentResponse result) {
        String role = node.getRole();
        ExpertAgent expertAgent = panel.getAgent(role);

        if (expertAgent == null) {
            log.warn("[PANEL] 找不到角色对应的 Expert Agent: {}", role);
            node.setStatus(WorkflowNode.NodeStatus.FAILED);
            node.setErrorMsg("找不到角色: " + role);
            return;
        }

        node.setStatus(WorkflowNode.NodeStatus.RUNNING);
        long nodeStart = System.currentTimeMillis();

        // 更新角色工作区状态为 working
        WorkspaceManager workspaceManager = getWorkspaceManager();
        ProjectWorkspace projectWorkspace = context.getProjectId() != null && workspaceManager != null
                ? workspaceManager.getWorkspace(context.getProjectId()) : null;
        if (projectWorkspace != null) {
            RoleWorkspace rw = projectWorkspace.getOrCreateRoleWorkspace(role,
                    expertAgent.getRoleDisplayName(), getRoleEmoji(role));
            rw.setStatus(RoleWorkspace.RoleStatus.WORKING.name().toLowerCase());
            rw.setStartedAt(nodeStart);
            workspaceManager.updateWorkspace(projectWorkspace);
        }

        log.info("[PANEL] >>> 开始执行专家节点: {} ({})", node.getDisplayName(), role);

        try {
            String output = expertAgent.executeInContext(context, agentRequest, result);
            context.publish(role, output);

            node.setStatus(WorkflowNode.NodeStatus.DONE);
            node.setCompletedAt(System.currentTimeMillis());
            node.setDurationMs(System.currentTimeMillis() - nodeStart);

            // 持久化产出物到角色工作区
            if (projectWorkspace != null && workspaceManager != null) {
                String artifactId = role + "_" + System.currentTimeMillis();
                WorkspaceArtifact artifact = WorkspaceArtifact.of(
                        artifactId,
                        getArtifactName(role),
                        getArtifactType(role),
                        "markdown",
                        output,
                        role
                );
                workspaceManager.addArtifact(context.getProjectId(), role, artifact);

                // 更新角色工作区状态
                RoleWorkspace rw = projectWorkspace.getRoleWorkspace(role);
                if (rw != null) {
                    rw.setStatus(RoleWorkspace.RoleStatus.DONE.name().toLowerCase());
                    rw.setCompletedAt(System.currentTimeMillis());
                    rw.setDurationMs(System.currentTimeMillis() - nodeStart);
                }
                workspaceManager.updateWorkspace(projectWorkspace);
            }

            log.info("[PANEL] <<< 专家节点完成: {} ({}), 耗时: {}ms, 产出长度: {}字",
                    node.getDisplayName(), role, node.getDurationMs(), output.length());

        } catch (Exception e) {
            node.setStatus(WorkflowNode.NodeStatus.FAILED);
            node.setErrorMsg(e.getMessage());
            node.setDurationMs(System.currentTimeMillis() - nodeStart);

            // 更新角色工作区失败状态
            if (projectWorkspace != null && workspaceManager != null) {
                RoleWorkspace rw = projectWorkspace.getRoleWorkspace(role);
                if (rw != null) {
                    rw.setStatus(RoleWorkspace.RoleStatus.FAILED.name().toLowerCase());
                    rw.setError(e.getMessage());
                    rw.setDurationMs(System.currentTimeMillis() - nodeStart);
                }
                workspaceManager.updateWorkspace(projectWorkspace);
            }

            log.error("[PANEL] 专家节点执行失败: {} ({}), 错误: {}", node.getDisplayName(), role, e.getMessage());

            // 写入失败占位，保证后续节点可继续执行
            context.publish(role, "【" + expertAgent.getRoleDisplayName() + "】执行异常: " + e.getMessage());

            addThinkStep(agentRequest, result, ThinkStep.of(
                    ThinkStep.Type.EXPERT_RESULT,
                    "【" + expertAgent.getRoleDisplayName() + "】执行异常",
                    "错误: " + e.getMessage(),
                    0
            ));
        }
    }

    /**
     * 调用 LLM 聚合所有专家产出，生成综合交付报告
     * <p>
     * 向后兼容静态工作流模式
     */
    private String generateFinalReport(AgentContext context, AgentRequest agentRequest) {
        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("## 原始项目需求\n\n").append(context.getOriginalRequest()).append("\n\n");
        userPrompt.append("---\n\n");
        userPrompt.append("## 各专家分析结果\n\n");

        Map<String, String> artifacts = context.getAllArtifacts();
        // 动态模式：按实际产出顺序遍历，不再硬编码
        for (Map.Entry<String, String> entry : artifacts.entrySet()) {
            String role = entry.getKey();
            String artifact = entry.getValue();
            if (artifact != null && !artifact.trim().isEmpty()) {
                userPrompt.append("### ").append(getRoleDisplayName(role)).append("\n\n");
                userPrompt.append(artifact).append("\n\n---\n\n");
            }
        }

        userPrompt.append("请基于以上所有专家的分析，生成一份完整的项目综合交付报告。");
        userPrompt.append("报告需要包含：执行摘要、关键决策要点、整体方案概览、实施建议和下一步行动计划。");
        userPrompt.append("格式要清晰，使用 Markdown。");

        String systemPrompt = "你是一位资深的项目总监，负责汇总专家团的意见，生成精炼的项目综合报告。\n"
                + "报告要体现各专家观点的整合与统一，突出关键决策和行动项，语言简洁有力，便于高层决策者阅读。";

        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", systemPrompt));
        messages.add(new Message("user", userPrompt.toString()));

        ModelRequest modelRequest = new ModelRequest(agentRequest.getModel(), newTraceId(), messages);
        ModelResponse modelResponse = ModelFacade.chatCompletion(modelRequest);

        if (!modelResponse.isSuccess()) {
            return buildFallbackReport(context);
        }

        return modelResponse.getContent();
    }

    /**
     * 降级报告：直接拼接各专家产出
     */
    private String buildFallbackReport(AgentContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 项目综合交付报告\n\n");
        sb.append("> 以下为专家团各角色产出汇总\n\n");

        Map<String, String> artifacts = context.getAllArtifacts();
        // 动态模式：按实际产出顺序遍历
        for (Map.Entry<String, String> entry : artifacts.entrySet()) {
            sb.append("## ").append(getRoleDisplayName(entry.getKey())).append(" 产出\n\n");
            sb.append(entry.getValue()).append("\n\n---\n\n");
        }

        return sb.toString();
    }

    /** 截断日志输出 */
    private String truncate(String text) {
        if (text == null) return "";
        return text.length() > 80 ? text.substring(0, 80) + "..." : text;
    }

    /** 截断日志输出（指定长度） */
    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    /** 获取角色 emoji */
    private String getRoleEmoji(String role) {
        switch (role) {
            case "sales":      return "💼";
            case "pm":         return "📝";
            case "architect":  return "🏗️";
            case "developer":  return "💻";
            case "tester":     return "🔍";
            case "pm_project": return "📊";
            default:           return "👤";
        }
    }

    /** 获取角色产出物名称 */
    private String getArtifactName(String role) {
        switch (role) {
            case "sales":      return "商业分析报告";
            case "pm":         return "产品需求文档";
            case "architect":  return "系统架构设计";
            case "developer":  return "开发实施方案";
            case "tester":     return "测试策略与用例";
            case "pm_project": return "项目管理计划";
            default:           return role + "产出";
        }
    }

    /** 获取角色产出物类型 */
    private WorkspaceArtifact.ArtifactType getArtifactType(String role) {
        switch (role) {
            case "sales":      return WorkspaceArtifact.ArtifactType.ANALYSIS;
            case "pm":         return WorkspaceArtifact.ArtifactType.DOCUMENT;
            case "architect":  return WorkspaceArtifact.ArtifactType.DESIGN;
            case "developer":  return WorkspaceArtifact.ArtifactType.CODE;
            case "tester":     return WorkspaceArtifact.ArtifactType.TEST_CASE;
            case "pm_project": return WorkspaceArtifact.ArtifactType.PLAN;
            default:           return WorkspaceArtifact.ArtifactType.OTHER;
        }
    }
}
