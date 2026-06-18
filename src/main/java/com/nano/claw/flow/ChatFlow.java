package com.nano.claw.flow;

import com.nano.claw.agent.common.AgentRequest;
import com.nano.claw.agent.common.AgentResponse;
import com.nano.claw.agent.common.ChatRequest;
import com.nano.claw.agent.common.ChatResponse;
import com.nano.claw.agent.common.ThinkStep;
import com.nano.claw.agent.core.Agent;
import com.nano.claw.agent.core.AgentFactory;
import com.nano.claw.agent.core.ExpertPanelAgent;
import com.nano.claw.agent.core.ModeRouter;
import com.nano.claw.agent.mcp.CalculatorTool;
import com.nano.claw.agent.mcp.HttpTool;
import com.nano.claw.agent.mcp.SkillManager;
import com.nano.claw.agent.mcp.ToolRegistry;
import com.nano.claw.channels.Channel;
import com.nano.claw.channels.ChannelMessage;
import com.nano.claw.channels.FeishuChannel;
import com.nano.claw.cronjob.CronJob;
import com.nano.claw.cronjob.CronJobManager;
import com.nano.claw.cronjob.CronJobParser;
import com.nano.claw.llm.Model;
import com.nano.claw.llm.ModelFacade;
import com.nano.claw.llm.ModelRequest;
import com.nano.claw.llm.ModelResponse;
import com.nano.claw.memory.ConversationMemory;
import com.nano.claw.memory.MemoryService;
import com.nano.claw.messages.Message;
import com.nano.claw.sessions.SessionManager;
import com.nano.claw.workspace.ProjectWorkspace;
import com.nano.claw.workspace.WorkspaceManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 聊天编排层 - 串联会话管理、Agent调用、通道分发
 *
 * @author Jason
 * @description 组装工作流程
 * @date 2026/5/18
 */
@Service
public class ChatFlow {

    private static final Logger log = LoggerFactory.getLogger(ChatFlow.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${nanoclaw.llm.model:MINI_MAX}")
    private String defaultModelName;

    @Value("${nanoclaw.agent.mode:react}")
    private String defaultAgentMode;

    @Value("${nanoclaw.feishu.app-id:}")
    private String feishuAppId;

    @Value("${nanoclaw.feishu.app-secret:}")
    private String feishuAppSecret;

    @Value("${nanoclaw.feishu.verification-token:}")
    private String feishuVerificationToken;

    private final SessionManager sessionManager = new SessionManager();
    private final ToolRegistry toolRegistry = new ToolRegistry();
    private final Map<String, Channel> channels = new ConcurrentHashMap<>();

    @Resource
    private CronJobManager cronJobManager;

    @Resource
    private MemoryService memoryService;

    @Resource
    private WorkspaceManager workspaceManager;

    @Resource
    private SkillManager skillManager;

    @Resource
    private ApplicationContext applicationContext;

    /** SSE 异步线程池 */
    private final ExecutorService sseExecutor = Executors.newCachedThreadPool();

    /** 问句结果缓存，key=问句文本，value=缓存条目 */
    private final ConcurrentHashMap<String, CacheEntry> queryCache = new ConcurrentHashMap<>();

    /** 缓存有效期：10分钟 */
    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;

    /**
     * 缓存条目
     */
    private static class CacheEntry {
        final ChatResponse response;
        final long timestamp;

        CacheEntry(ChatResponse response) {
            this.response = response;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }

    @PostConstruct
    public void init() {
        // 注册内置工具
        toolRegistry.register(new HttpTool());
        toolRegistry.register(new CalculatorTool());

        // 初始化飞书通道
        if (feishuAppId != null && !feishuAppId.isEmpty()) {
            FeishuChannel feishuChannel = new FeishuChannel(feishuAppId, feishuAppSecret, feishuVerificationToken);
            channels.put("feishu", feishuChannel);
            log.info("飞书通道初始化完成");
        }

        // 设置 ChatFlow 引用到 CronJobManager
        cronJobManager.setChatFlow(this);

        // 注入 Spring ApplicationContext 到 ExpertPanelAgent
        ExpertPanelAgent.setApplicationContext(applicationContext);

        log.info("ChatFlow 初始化完成，工具数: {}，通道数: {}，技能数: {}", 
                toolRegistry.getToolDescriptions().split("\n").length, channels.size(),
                skillManager != null ? skillManager.getSkillRegistry().size() : 0);
    }

    /**
     * SSE 流式聊天接口（Web 前端使用）
     * <p>
     * 异步执行 Agent，实时推送：
     * - think_step 事件：每个 ThinkStep
     * - done 事件：ChatResponse（含最终答案）
     */
    public SseEmitter chatStream(ChatRequest request) {
        SseEmitter emitter = new SseEmitter(180_000L); // 3分钟超时

        // 跟踪 emitter 是否已完成（客户端断开 / 超时 / 已 complete 等场景），
        // 避免向已完成的 emitter 继续 send 触发 IllegalStateException
        final AtomicBoolean completed = new AtomicBoolean(false);
        emitter.onCompletion(() -> completed.set(true));
        emitter.onTimeout(() -> {
            completed.set(true);
            log.warn("[FLOW-SSE] SseEmitter 超时，自动完成");
            try { emitter.complete(); } catch (Exception ignored) {}
        });
        emitter.onError(t -> {
            completed.set(true);
            log.warn("[FLOW-SSE] SseEmitter 异常: {}", t.getMessage());
        });

        // 安全发送：emitter 已完成时直接跳过；发送失败则标记完成，后续调用自动变为 no-op
        final Consumer<SseEmitter.SseEventBuilder> safeSend = (event) -> {
            if (completed.get()) return;
            try {
                emitter.send(event);
            } catch (Exception e) {
                completed.set(true);
                log.warn("[FLOW-SSE] 发送 SSE 事件失败，可能客户端已断开: {}", e.getMessage());
            }
        };
        final Runnable safeComplete = () -> {
            if (completed.compareAndSet(false, true)) {
                try { emitter.complete(); } catch (Exception ignored) {}
            }
        };

        sseExecutor.submit(() -> {
            try {
                // 确定会话ID
                String sessionId = request.getSessionId();
                if (sessionId == null || sessionId.isEmpty()) {
                    sessionId = UUID.randomUUID().toString();
                }
                final String finalSessionId = sessionId;

                // 持久化用户问句到独立文件（用于上下文检索）
                memoryService.saveUserQuery(request.getMessage());

                // 选择模型和模式
                Model model = resolveModel(defaultModelName);
                String agentMode = request.getMode();
                List<ThinkStep> allSteps = new ArrayList<>();
                boolean ruleMatched = false;
                String ruleName = null;

                if (agentMode == null || agentMode.isEmpty()) {
                    ModeRouter.RouteResult routeResult = ModeRouter.route(request.getMessage(), model);
                    agentMode = routeResult.getMode();
                    ruleMatched = routeResult.isRuleMatched();
                    ruleName = routeResult.getRuleName();
                    String routingDesc = ruleMatched
                            ? "规则引擎快速匹配，命中规则: " + ruleName + "，选择模式: " + agentMode
                            : "规则未命中，LLM智能路由，选择模式: " + agentMode;
                    ThinkStep routingStep = ThinkStep.of(ThinkStep.Type.ROUTING, "智能路由决策", routingDesc, 0);
                    allSteps.add(routingStep);
                    // 实时推送路由步骤
                    safeSend.accept(SseEmitter.event()
                            .name("think_step")
                            .data(routingStep, MediaType.APPLICATION_JSON));
                }

                // cronjob 模式特殊处理
                if ("cronjob".equals(agentMode)) {
                    long start = System.currentTimeMillis();
                    ChatResponse resp = handleCronJobCreation(request, finalSessionId, allSteps, start);
                    safeSend.accept(SseEmitter.event()
                            .name("done")
                            .data(resp, MediaType.APPLICATION_JSON));
                    safeComplete.run();
                    return;
                }

                // expert_panel 模式特殊处理
                if ("expert_panel".equals(agentMode)) {
                    long start = System.currentTimeMillis();
                    ProjectWorkspace projectWorkspace;
                    String existingProjectId = request.getProjectId();
                    if (existingProjectId != null && !existingProjectId.isEmpty()) {
                        projectWorkspace = workspaceManager.getWorkspace(existingProjectId);
                    } else {
                        String projectName = request.getMessage().length() > 30
                                ? request.getMessage().substring(0, 30) + "..." : request.getMessage();
                        projectWorkspace = workspaceManager.createWorkspace(projectName, request.getMessage());
                    }
                    AgentRequest expertRequest = new AgentRequest();
                    expertRequest.setQuery(request.getMessage());
                    expertRequest.setModel(model);
                    expertRequest.setSessionId(projectWorkspace != null ? projectWorkspace.getId() : finalSessionId);
                    // 设置 think_step 实时推送回调
                    expertRequest.setThinkStepConsumer(step -> {
                        try {
                            allSteps.add(step);
                            safeSend.accept(SseEmitter.event()
                                    .name("think_step")
                                    .data(step, MediaType.APPLICATION_JSON));
                        } catch (Exception ignored) {}
                    });
                    Agent expertAgent = AgentFactory.create("expert_panel", toolRegistry);
                    AgentResponse expertResponse = expertAgent.run(expertRequest);
                    if (expertResponse.isSuccess()) {
                        ConversationMemory memory = sessionManager.getOrCreate(finalSessionId);
                        memory.addMessage(new com.nano.claw.messages.Message("user", request.getMessage()));
                        memory.addMessage(new com.nano.claw.messages.Message("assistant", expertResponse.getAnswer()));
                    }
                    ChatResponse chatResponse;
                    if (expertResponse.isSuccess()) {
                        chatResponse = ChatResponse.success(expertResponse.getAnswer(), finalSessionId, "expert_panel", allSteps);
                    } else {
                        chatResponse = ChatResponse.failure(expertResponse.getError(), finalSessionId);
                        chatResponse.setThinkSteps(allSteps);
                    }
                    chatResponse.setDurationMs(System.currentTimeMillis() - start);
                    chatResponse.setTotalTokens(expertResponse.getTotalTokens());
                    chatResponse.setProjectId(projectWorkspace != null ? projectWorkspace.getId() : null);
                    safeSend.accept(SseEmitter.event()
                            .name("done")
                            .data(chatResponse, MediaType.APPLICATION_JSON));
                    safeComplete.run();
                    return;
                }

                // 普通 Agent 模式
                long startTime = System.currentTimeMillis();
                ConversationMemory memory = sessionManager.getOrCreate(finalSessionId);

                final String finalAgentMode = agentMode;

                // ========== chat 模式：直接流式调用 LLM，逐 token 推送 ==========
                if ("chat".equals(agentMode)) {
                    // 记录思考步骤
                    ThinkStep chatStep = ThinkStep.of(ThinkStep.Type.CHAT, "直接对话", request.getMessage(), 1);
                    allSteps.add(chatStep);
                    safeSend.accept(SseEmitter.event()
                            .name("think_step")
                            .data(chatStep, MediaType.APPLICATION_JSON));

                    // 构建 system prompt
                    String memoryContext = memoryService.buildSystemPromptMemorySection();
                    String systemPrompt = (memoryContext != null && !memoryContext.isEmpty())
                            ? memoryContext + "\n\n你是一个智能助手。请直接回答用户的问题，给出清晰、准确、有用的回答。"
                            : "你是一个智能助手。请直接回答用户的问题，给出清晰、准确、有用的回答。";

                    List<Message> messages = new ArrayList<>();
                    messages.add(new Message("system", systemPrompt));
                    messages.add(new Message("user", request.getMessage()));

                    ModelRequest modelRequest = new ModelRequest(model, UUID.randomUUID().toString(), messages);

                    // 流式调用 LLM，逐 token 推送 answer_chunk
                    ModelResponse modelResponse = ModelFacade.chatCompletionStream(modelRequest, token -> {
                        try {
                            safeSend.accept(SseEmitter.event()
                                    .name("answer_chunk")
                                    .data(token, MediaType.TEXT_PLAIN));
                        } catch (Exception ignored) {}
                    });

                    // 保存对话记忆
                    String answer = modelResponse.isSuccess() ? modelResponse.getContent() : "";
                    memory.addMessage(new Message("user", request.getMessage()));
                    if (modelResponse.isSuccess()) {
                        memory.addMessage(new Message("assistant", answer));
                        try {
                            memoryService.saveConversation(request.getMessage(), answer);
                        } catch (Exception e) {
                            log.warn("[FLOW] 保存持久化记忆失败", e);
                        }
                    }

                    // 发送 done 事件（含元数据）
                    ChatResponse chatResponse;
                    if (modelResponse.isSuccess()) {
                        chatResponse = ChatResponse.success(answer, finalSessionId, "chat", allSteps);
                    } else {
                        chatResponse = ChatResponse.failure(modelResponse.getError(), finalSessionId);
                        chatResponse.setThinkSteps(allSteps);
                    }
                    chatResponse.setDurationMs(System.currentTimeMillis() - startTime);
                    chatResponse.setTotalTokens(modelResponse.getTotalTokens());

                    safeSend.accept(SseEmitter.event()
                            .name("done")
                            .data(chatResponse, MediaType.APPLICATION_JSON));
                    safeComplete.run();
                    return;
                }

                // ========== 其他 Agent 模式（react/plan/reflection）==========
                AgentRequest agentRequest = new AgentRequest();
                agentRequest.setQuery(request.getMessage());
                agentRequest.setModel(model);
                agentRequest.setSessionId(finalSessionId);

                String memoryContext = memoryService.buildSystemPromptMemorySection();
                if (memoryContext != null && !memoryContext.isEmpty()) {
                    agentRequest.setSystemPrompt(memoryContext);
                }

                // 设置 think_step 实时推送回调
                agentRequest.setThinkStepConsumer(step -> {
                    try {
                        allSteps.add(step);
                        safeSend.accept(SseEmitter.event()
                                .name("think_step")
                                .data(step, MediaType.APPLICATION_JSON));
                    } catch (Exception ignored) {}
                });

                Agent agent = AgentFactory.create(agentMode, toolRegistry, skillManager);
                AgentResponse agentResponse = agent.run(agentRequest);

                // Agent执行完毕，将最终答案流式推送
                if (agentResponse.isSuccess() && agentResponse.getAnswer() != null) {
                    // 将最终答案分块推送（模拟流式效果）
                    String fullAnswer = agentResponse.getAnswer();
                    int chunkSize = 20; // 每次推送约20个字符
                    for (int i = 0; i < fullAnswer.length(); i += chunkSize) {
                        String chunk = fullAnswer.substring(i, Math.min(i + chunkSize, fullAnswer.length()));
                        safeSend.accept(SseEmitter.event()
                                .name("answer_chunk")
                                .data(chunk, MediaType.TEXT_PLAIN));
                    }
                }

                memory.addMessage(new Message("user", request.getMessage()));
                if (agentResponse.isSuccess()) {
                    memory.addMessage(new Message("assistant", agentResponse.getAnswer()));
                    try {
                        memoryService.saveConversation(request.getMessage(), agentResponse.getAnswer());
                    } catch (Exception e) {
                        log.warn("[FLOW] 保存持久化记忆失败", e);
                    }
                }

                ChatResponse chatResponse;
                if (agentResponse.isSuccess()) {
                    chatResponse = ChatResponse.success(agentResponse.getAnswer(), finalSessionId, finalAgentMode, allSteps);
                } else {
                    chatResponse = ChatResponse.failure(agentResponse.getError(), finalSessionId);
                    chatResponse.setThinkSteps(allSteps);
                }
                chatResponse.setDurationMs(System.currentTimeMillis() - startTime);
                chatResponse.setTotalTokens(agentResponse.getTotalTokens());

                safeSend.accept(SseEmitter.event()
                        .name("done")
                        .data(chatResponse, MediaType.APPLICATION_JSON));
                safeComplete.run();

            } catch (Exception e) {
                if (completed.get()) {
                    log.warn("[FLOW-SSE] emitter 已完成（客户端可能已断开），跳过错误响应: {}", e.getMessage());
                    return;
                }
                log.error("[FLOW-SSE] 流式处理异常", e);
                try {
                    ChatResponse errResp = ChatResponse.failure(e.getMessage(), null);
                    safeSend.accept(SseEmitter.event()
                            .name("done")
                            .data(errResp, MediaType.APPLICATION_JSON));
                    safeComplete.run();
                } catch (Exception ex) {
                    if (!completed.get()) {
                        try { emitter.completeWithError(ex); } catch (Exception ignored) {}
                    }
                }
            }
        });

        return emitter;
    }

    /**
     * 处理聊天请求（HTTP API 入口）
     */
    public ChatResponse chat(ChatRequest request) {
        long startTime = System.currentTimeMillis();

        // 确定会话ID
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString();
            log.info("[FLOW] 新建会话: {}", sessionId);
        } else {
            log.info("[FLOW] 继续会话: {}", sessionId);
        }

        // 持久化用户问句到独立文件（用于上下文检索）
        memoryService.saveUserQuery(request.getMessage());

        // ========== 缓存检查：相同问句直接返回 ==========
        String cacheKey = request.getMessage().trim();
        CacheEntry cached = queryCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            log.info("[FLOW] 命中缓存，问句: {}", cacheKey);
            ChatResponse cachedResponse = cloneResponse(cached.response);
            cachedResponse.setSessionId(sessionId);
            cachedResponse.setCached(true);
            cachedResponse.setTotalTokens(0);
            cachedResponse.setDurationMs(System.currentTimeMillis() - startTime);
            return cachedResponse;
        }
        // 清理过期缓存条目（简单策略：每次查询时概率清理）
        if (queryCache.size() > 100 && Math.random() < 0.1) {
            queryCache.entrySet().removeIf(e -> e.getValue().isExpired());
        }

        // 获取或创建会话记忆
        ConversationMemory memory = sessionManager.getOrCreate(sessionId);
        log.info("[FLOW] 会话 {} 当前记忆轮数: {}", sessionId, memory.size());

        // 构建 Agent 请求
        Model model = resolveModel(defaultModelName);

        // 自动路由 Agent 模式
        String agentMode = request.getMode();
        List<ThinkStep> allSteps = new ArrayList<>();
        boolean ruleMatched = false;
        String ruleName = null;
        if (agentMode == null || agentMode.isEmpty()) {
            log.info("[FLOW] >>> 开始模式路由, 用户输入: {}", request.getMessage());
            ModeRouter.RouteResult routeResult = ModeRouter.route(request.getMessage(), model);
            agentMode = routeResult.getMode();
            ruleMatched = routeResult.isRuleMatched();
            ruleName = routeResult.getRuleName();
            log.info("[FLOW] <<< 模式路由结果: {} (来源: {})", agentMode, ruleMatched ? "规则:" + ruleName : "LLM");
            // 记录模式路由步骤
            String routingDesc = ruleMatched
                    ? "规则引擎快速匹配，命中规则: " + ruleName + "，选择模式: " + agentMode
                    : "规则未命中，LLM智能路由，选择模式: " + agentMode;
            allSteps.add(ThinkStep.of(ThinkStep.Type.ROUTING, "智能路由决策", routingDesc, 0));
        }
        log.info("[FLOW] Agent 模式: {}, 模型: {}", agentMode, model);

        // ========== cronjob 模式特殊处理：创建定时任务而非执行Agent ==========
        if ("cronjob".equals(agentMode)) {
            return handleCronJobCreation(request, sessionId, allSteps, startTime);
        }

        // ========== expert_panel 模式特殊处理：专家团协作 ==========
        if ("expert_panel".equals(agentMode)) {
            // 创建或获取项目工作区
            ProjectWorkspace projectWorkspace;
            String existingProjectId = request.getProjectId();
            if (existingProjectId != null && !existingProjectId.isEmpty()) {
                projectWorkspace = workspaceManager.getWorkspace(existingProjectId);
            } else {
                String projectName = request.getMessage().length() > 30
                        ? request.getMessage().substring(0, 30) + "..." : request.getMessage();
                projectWorkspace = workspaceManager.createWorkspace(projectName, request.getMessage());
            }

            AgentRequest expertRequest = new AgentRequest();
            expertRequest.setQuery(request.getMessage());
            expertRequest.setModel(resolveModel(defaultModelName));
            expertRequest.setSessionId(projectWorkspace != null ? projectWorkspace.getId() : sessionId);
            Agent expertAgent = AgentFactory.create("expert_panel", toolRegistry);
            AgentResponse expertResponse = expertAgent.run(expertRequest);
            allSteps.addAll(expertResponse.getThinkSteps());
            if (expertResponse.isSuccess()) {
                memory.addMessage(new com.nano.claw.messages.Message("user", request.getMessage()));
                memory.addMessage(new com.nano.claw.messages.Message("assistant", expertResponse.getAnswer()));
            }
            ChatResponse chatResponse;
            if (expertResponse.isSuccess()) {
                chatResponse = ChatResponse.success(expertResponse.getAnswer(), sessionId, "expert_panel", allSteps);
            } else {
                chatResponse = ChatResponse.failure(expertResponse.getError(), sessionId);
                chatResponse.setThinkSteps(allSteps);
            }
            chatResponse.setDurationMs(System.currentTimeMillis() - startTime);
            chatResponse.setTotalTokens(expertResponse.getTotalTokens());
            chatResponse.setProjectId(projectWorkspace != null ? projectWorkspace.getId() : null);
            return chatResponse;
        }

        AgentRequest agentRequest = new AgentRequest();
        agentRequest.setQuery(request.getMessage());
        agentRequest.setModel(model);
        agentRequest.setSessionId(sessionId);

        // ========== 注入记忆上下文到 system prompt ==========
        String memoryContext = memoryService.buildSystemPromptMemorySection();
        if (memoryContext != null && !memoryContext.isEmpty()) {
            agentRequest.setSystemPrompt(memoryContext);
            log.info("[FLOW] 注入记忆上下文, 长度: {} 字符", memoryContext.length());
        }

        // 创建并运行 Agent
        log.info("[FLOW] >>> 开始执行 Agent, 用户输入: {}", request.getMessage());
        Agent agent = AgentFactory.create(agentMode, toolRegistry, skillManager);
        AgentResponse agentResponse = agent.run(agentRequest);
        log.info("[FLOW] <<< Agent 执行完毕, 成功: {}, 迭代轮次: {}", agentResponse.isSuccess(), agentResponse.getLoopCount());

        if (agentResponse.isSuccess()) {
            log.info("[FLOW] Agent 回答: {}", agentResponse.getAnswer());
        } else {
            log.warn("[FLOW] Agent 失败: {}", agentResponse.getError());
        }

        // 合并路由步骤 + Agent思考步骤
        allSteps.addAll(agentResponse.getThinkSteps());

        // 保存对话记忆
        memory.addMessage(new com.nano.claw.messages.Message("user", request.getMessage()));
        if (agentResponse.isSuccess()) {
            memory.addMessage(new com.nano.claw.messages.Message("assistant", agentResponse.getAnswer()));
        }

        // ========== 持久化记忆：保存对话到文件 ==========
        if (agentResponse.isSuccess()) {
            try {
                memoryService.saveConversation(request.getMessage(), agentResponse.getAnswer());
            } catch (Exception e) {
                log.warn("[FLOW] 保存持久化记忆失败", e);
            }
        }

        // 构建响应
        ChatResponse chatResponse;
        if (agentResponse.isSuccess()) {
            chatResponse = ChatResponse.success(agentResponse.getAnswer(), sessionId, agentMode, allSteps);
        } else {
            chatResponse = ChatResponse.failure(agentResponse.getError(), sessionId);
            chatResponse.setThinkSteps(allSteps);
        }

        // 设置用时和 token 用量
        long elapsed = System.currentTimeMillis() - startTime;
        chatResponse.setDurationMs(elapsed);
        chatResponse.setTotalTokens(agentResponse.getTotalTokens());

        // 如果请求来自 IM 通道，自动回复
        if (request.getChannel() != null && channels.containsKey(request.getChannel())) {
            Channel channel = channels.get(request.getChannel());
            if (agentResponse.isSuccess()) {
                channel.sendText(sessionId, agentResponse.getAnswer());
            }
        }

        // ========== 写入缓存（仅成功的响应缓存） ==========
        if (agentResponse.isSuccess()) {
            queryCache.put(cacheKey, new CacheEntry(chatResponse));
            log.info("[FLOW] 已缓存问句: {}", cacheKey);
        }

        log.info("[FLOW] 会话 {} 处理耗时: {}ms, tokens: {}", sessionId, elapsed, agentResponse.getTotalTokens());

        return chatResponse;
    }

    /**
     * 处理飞书事件回调
     */
    public ChatResponse handleFeishuEvent(String eventPayload) {
        FeishuChannel feishuChannel = (FeishuChannel) channels.get("feishu");
        if (feishuChannel == null) {
            log.warn("飞书通道未配置");
            return ChatResponse.failure("飞书通道未配置", null);
        }

        ChannelMessage channelMsg = feishuChannel.handleEvent(eventPayload);
        if (channelMsg == null) {
            return ChatResponse.failure("解析飞书事件失败", null);
        }

        // 处理 challenge 验证
        if ("challenge".equals(channelMsg.getMsgType())) {
            // challenge 由 Controller 层直接处理
            return null;
        }

        // 构建聊天请求
        ChatRequest chatRequest = new ChatRequest();
        chatRequest.setMessage(channelMsg.getContent());
        chatRequest.setSessionId(channelMsg.getChatId());
        chatRequest.setChannel("feishu");

        // 异步处理，先回复收到
        ChatResponse response = chat(chatRequest);

        // 通过 ChatFlow.chat() 内部已自动通过 channel 回复，此处不再重复调用
        // chat() 方法会检查 request.getChannel() 并自动调用 channel.sendText()
        return response;
    }

    /**
     * 获取飞书通道（供 Controller 使用）
     */
    public FeishuChannel getFeishuChannel() {
        return (FeishuChannel) channels.get("feishu");
    }

    private Model resolveModel(String modelName) {
        try {
            return Model.valueOf(modelName);
        } catch (Exception e) {
            return Model.MINI_MAX;
        }
    }

    /**
     * 克隆 ChatResponse，避免缓存对象被并发修改
     */
    private ChatResponse cloneResponse(ChatResponse source) {
        ChatResponse clone = new ChatResponse();
        clone.setAnswer(source.getAnswer());
        clone.setSuccess(source.isSuccess());
        clone.setSessionId(source.getSessionId());
        clone.setError(source.getError());
        clone.setMode(source.getMode());
        clone.setThinkSteps(new ArrayList<>(source.getThinkSteps()));
        clone.setDurationMs(source.getDurationMs());
        clone.setTotalTokens(source.getTotalTokens());
        clone.setCached(source.isCached());
        return clone;
    }

    /**
     * 处理定时任务创建请求
     * <p>
     * 当 ModeRouter 将用户输入路由到 cronjob 模式时，
     * 解析自然语言为定时任务并创建，返回创建结果。
     */
    private ChatResponse handleCronJobCreation(ChatRequest request, String sessionId, List<ThinkStep> allSteps, long startTime) {
        String message = request.getMessage();
        log.info("[FLOW] 处理定时任务创建请求: {}", message);

        // 记录解析步骤
        allSteps.add(ThinkStep.of(ThinkStep.Type.ACTION, "解析定时任务",
                "正在将自然语言解析为定时任务: " + message, 1));

        // 解析自然语言
        Model model = resolveModel(defaultModelName);
        CronJobParser.ParseResult parseResult = CronJobParser.parse(message, model);

        if (!parseResult.isSuccess()) {
            // 解析失败，返回友好的错误提示
            allSteps.add(ThinkStep.of(ThinkStep.Type.OBSERVATION, "解析失败",
                    "无法解析为定时任务: " + parseResult.getError(), 1));

            ChatResponse chatResponse = ChatResponse.failure("无法理解您的定时需求，请尝试更明确的描述，例如：\n"
                    + "- 每天早上9点提醒我查看邮件\n"
                    + "- 每隔30分钟检查服务器状态\n"
                    + "- 每周一下午3点生成本周报告", sessionId);
            chatResponse.setMode("cronjob");
            chatResponse.setThinkSteps(allSteps);
            chatResponse.setDurationMs(System.currentTimeMillis() - startTime);
            return chatResponse;
        }

        // 解析成功，创建定时任务
        CronJob job = new CronJob();
        job.setName(parseResult.getName());
        job.setCronExpression(parseResult.getCronExpression());
        job.setScheduleDesc(parseResult.getScheduleDesc());
        job.setQuery(parseResult.getQuery());
        job.setDescription(message);

        CronJob created;
        try {
            created = cronJobManager.addJob(job);
        } catch (RuntimeException e) {
            log.error("[FLOW] 创建定时任务失败", e);
            allSteps.add(ThinkStep.of(ThinkStep.Type.OBSERVATION, "创建失败",
                    "定时任务创建失败: " + e.getMessage(), 1));

            ChatResponse chatResponse = ChatResponse.failure("创建定时任务失败：" + e.getMessage(), sessionId);
            chatResponse.setMode("cronjob");
            chatResponse.setThinkSteps(allSteps);
            chatResponse.setDurationMs(System.currentTimeMillis() - startTime);
            return chatResponse;
        }

        allSteps.add(ThinkStep.of(ThinkStep.Type.OBSERVATION, "定时任务已创建",
                "任务ID: " + created.getId() + ", cron: " + created.getCronExpression()
                        + ", 调度: " + created.getScheduleDesc(), 1));

        // 构建友好的成功响应
        String answer = "已为您创建定时任务！\n\n"
                + "**任务名称**: " + created.getName() + "\n"
                + "**调度规则**: " + created.getScheduleDesc() + "\n"
                + "**执行内容**: " + created.getQuery() + "\n"
                + "**Cron表达式**: `" + created.getCronExpression() + "`\n"
                + "**任务ID**: " + created.getId() + "\n\n"
                + "任务已开始调度，到时间会自动执行。";

        ChatResponse chatResponse = ChatResponse.success(answer, sessionId, "cronjob", allSteps);
        chatResponse.setDurationMs(System.currentTimeMillis() - startTime);

        // 保存对话记忆
        ConversationMemory memory = sessionManager.getOrCreate(sessionId);
        memory.addMessage(new com.nano.claw.messages.Message("user", message));
        memory.addMessage(new com.nano.claw.messages.Message("assistant", answer));

        return chatResponse;
    }
}
