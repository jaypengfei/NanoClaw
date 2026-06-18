# NanoClaw

一个基于 Spring Boot 的轻量级 AI Agent 运行时框架，提供对话编排、工具调用、长期记忆、定时任务和多专家协作等核心能力。

## 项目简介

NanoClaw 是一个面向自主智能体场景的轻量级框架，核心理念是 **"小而锋利"**。项目尽量用简洁的代码组织完整的 Agent 运行链路，覆盖入口接入、编排路由、执行、状态持久化等关键环节。它不依赖 LangChain 等重量级框架，LLM 调用与 Agent 推理流程采用手写实现，便于理解系统行为、排查问题和按需扩展。

### 项目定位

NanoClaw 更接近一个 **Agent Runtime 与参考实现**，而不是面向固定业务场景的成品系统。它主要关注以下问题：

- 如何把 Web、API、IM、定时任务等不同入口统一接入同一套 Agent 编排流程
- 如何在同一系统中组织 Chat、ReAct、Plan-and-Execute、Reflection、多专家协作等多种推理模式
- 如何把工具、技能、长期记忆、工作区产物和定时任务持久化等能力组合成完整闭环
- 如何在 Spring Boot + Java 生态中，以相对轻量的方式实现 Agent 基础设施

### 适用场景

- 作为 AI Agent 架构学习和源码阅读的参考项目
- 作为企业内部 Agent 原型、PoC 或实验项目的基础工程
- 作为二次开发底座，继续扩展更多模型、工具、通道和业务技能
- 作为多角色协作式任务编排的实验环境

### 核心特性

- **多模式 Agent 引擎** — 支持 ReAct、Plan-and-Execute、Reflection 等推理模式，并可按需自动路由
- **智能意图路由** — 规则引擎（关键词 + 正则）优先匹配，LLM 负责兜底，减少不必要的模型开销
- **工具调用系统** — 内置 HTTP 请求和计算器工具，支持自定义扩展
- **记忆系统** — 支持对话记忆持久化、自动压缩和用户画像提取
- **专家协作工作区** — 支持销售、产品、架构、开发、测试、项目经理等多角色协作，并将产出物写入工作区
- **IM 通道接入** — 内置飞书通道，并可扩展钉钉、企微等渠道
- **定时任务** — 支持通过自然语言创建定时任务，由 LLM 解析 Cron 表达式，并在重启后自动恢复
- **暗色主题 Web UI** — 内置 Markdown 渲染 + 代码高亮 + 思考过程可视化

### 典型请求处理流程

无论请求来自 Web UI、REST API、飞书 Webhook 还是定时任务，核心处理流程基本一致：

1. **接入** — 请求进入 Controller、Webhook 或 Cron 入口
2. **编排** — `ChatFlow` 统一处理会话、模式、记忆和渠道信息
3. **路由** — `ModeRouter` 与 `IntentRuleEngine` 决定采用哪种 Agent 模式执行
4. **执行** — 目标 Agent 调用 LLM、工具和技能，必要时触发专家协作或工作区写入
5. **持久化** — 记忆、工作区产物和定时任务状态分别写入 `data/` 目录
6. **返回** — 结果通过普通响应、SSE 流式输出或 IM 回调返回给调用方

## 系统架构

```text
┌─────────────────────────────────────────────────────────────────────┐
│                              接入层                                │
│ Web UI(index.html) / REST API / SSE / Feishu Webhook / CronJob API │
└─────────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────┐
│                         ChatFlow 编排层                            │
│   会话管理(Session) / 记忆注入(Memory) / 渠道分发(Channel)         │
└─────────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────┐
│                  ModeRouter + IntentRuleEngine                     │
│                     规则优先，LLM 兜底路由                         │
└─────────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────┐
│                            执行层                                  │
│ ChatAgent / AgentLoop(ReAct) / PlanExecute / Reflection /          │
│ ExpertPanelAgent                                                   │
└─────────────────────────────────────────────────────────────────────┘
            │                     │                    │
            ▼                     ▼                    ▼
┌────────────────────┐  ┌────────────────────┐  ┌────────────────────┐
│ 工具与技能层        │  │ 模型调用层          │  │ 协作与调度层        │
│ ToolRegistry       │  │ ModelFacade        │  │ WorkspaceManager   │
│ SkillManager       │  │ ModelRequest/Resp  │  │ CronJobManager     │
│ HttpTool/CalcTool  │  │                    │  │ Expert Panel Flow  │
└────────────────────┘  └────────────────────┘  └────────────────────┘
            │                     │                    │
            └──────────────┬──────┴──────────────┬─────┘
                           ▼                     ▼
               ┌────────────────────┐  ┌────────────────────┐
               │ 记忆与状态持久化    │  │ 文件产物持久化      │
               │ data/memory        │  │ data/workspaces    │
               │ data/cronjobs      │  │ data/skills        │
               └────────────────────┘  └────────────────────┘
```

### 分层说明

- **接入层**：负责接收用户输入，当前包括 Web UI、REST API、SSE 流式接口、飞书 Webhook 和定时任务接口。
- **编排层**：由 `ChatFlow` 统一处理 session、记忆注入、响应包装和渠道差异，是整个系统的主入口。
- **路由层**：由 `ModeRouter` 和 `IntentRuleEngine` 共同决定采用哪种 Agent 模式执行任务。
- **执行层**：负责具体推理与任务执行，包含直接聊天、ReAct 循环、先规划后执行、自我反思、多专家协作等模式。
- **能力层**：为执行层提供底层能力，包括模型调用、工具/技能调用、工作区管理和定时任务调度。
- **持久化层**：当前以文件系统为主，用于存储长期记忆、定时任务、用户技能和专家协作工作区产物。

### 核心调用链

系统的核心调用链如下：

`MainController / FeishuChannel / CronJobController`
→ `ChatFlow`
→ `ModeRouter`
→ `AgentFactory`
→ `具体 Agent`
→ `ModelFacade / ToolRegistry / SkillManager / WorkspaceManager / MemoryService`

其中：

- 定时任务由 `CronJobManager` 定期触发，并重新进入 `ChatFlow`
- 多专家协作由 `ExpertPanelAgent` 组织多个角色执行，并由 `WorkspaceManager` 保存产出
- 长期记忆由 `MemoryService` 与 `MemoryFileStore` 负责读写和压缩

## Agent 模式详解

### 1. ReAct 模式 (`react`)

基于 **Reasoning + Acting** 范式的循环推理模式，核心流程：

```
Thought → Action → Observation → Thought → ... → Final Answer
```

- 最大迭代轮数：5 轮
- 内置死循环防护：重复调用检测 + 最大轮数限制
- 适用于：需要调用工具获取信息的单步或少量步骤任务

### 2. Plan-and-Execute 模式 (`plan_and_execute`)

先规划后执行的两阶段模式：

1. **Planning** — LLM 分析任务，生成编号步骤列表
2. **Execution** — 逐步执行每个步骤（工具调用或 LLM 推理）
3. **Re-planning** — 根据执行结果动态调整后续计划（最多 3 次）
4. **Summary** — 汇总所有步骤结果输出最终答案

- 适用于：复杂多步骤任务，需要全局规划的场景

### 3. Reflection 模式 (`reflection`)

自我反思改进模式，类似人类的"打草稿→检查→修改"：

1. **Generate** — 生成初始回答
2. **Reflect** — 从准确性、完整性、清晰度、深度四个维度反思批评
3. **Refine** — 根据反思结果改进回答（可触发工具调用补充信息）
4. 循环最多 2 轮，回答满意则提前终止

- 适用于：需要高质量输出的场景（写作、代码生成、深度分析）

### 4. Chat 模式 (`chat`)

直接对话模式，不经过任何循环推理，模型直接回答。

- 适用于：日常对话、闲聊、常识问答

### 5. CronJob 模式 (`cronjob`)

定时任务模式，通过自然语言创建周期性执行的任务：

1. **解析** — LLM 将自然语言解析为 Cron 表达式 + 任务内容
2. **创建** — 注册到调度器，按 Cron 表达式周期执行
3. **执行** — 每次触发时通过 Agent 执行任务内容
4. **持久化** — 任务元数据保存到 `data/cronjobs`，服务重启后自动恢复

- 适用于："每天早上9点提醒我查看邮件"、"每隔30分钟检查服务器状态"

## 智能路由

NanoClaw 采用 **规则引擎优先 + LLM 兜底** 的双层路由策略：

| 层级 | 策略 | 特点 |
|------|------|------|
| 第一层 | IntentRuleEngine | 关键词 + 正则快速匹配，零 LLM 开销 |
| 第二层 | LLM Router | 规则未命中时降级到 LLM 路由判断 |

规则优先级（越具体越靠前）：`CronJob > ReAct > Plan-and-Execute > Reflection > Chat`

典型规则示例：
- 包含"计算"、"算一下" → `react`
- 包含"制定计划"、"从零开始" → `plan_and_execute`
- 包含"写文章"、"写代码" → `reflection`
- 包含"你好"、"是什么" → `chat`
- 包含"每天"、"定时" → `cronjob`

## 记忆系统

NanoClaw 拥有完整的记忆生命周期管理：

### 记忆层次

| 层次 | 存储方式 | 说明 |
|------|----------|------|
| 会话记忆 | 内存 (ConcurrentHashMap) | 当前会话的上下文，最大 50 轮 |
| 每日记忆 | 文件 `memory-yyyy-mm-dd.md` | 当天的对话记录 |
| 全局记忆 | 文件 `global-memory.md` | 压缩合并后的长期记忆 |
| 用户画像 | 文件 `user-profile.md` | 从记忆中提取的用户特征 |

### 记忆生命周期

1. **写入** — 每轮对话追加到当天的每日记忆文件
2. **注入** — 每次对话前将全局记忆 + 用户画像 + 近 3 天每日记忆注入 Agent 的 system prompt
3. **压缩** — 全局记忆超过阈值时自动压缩；7 天前的每日记忆合并到全局记忆
4. **清理** — 超过保留天数（默认 30 天）的每日记忆自动清理
5. **画像更新** — 每 20 次对话自动从记忆中提取用户画像

### 记忆注入示例

```
# 关于用户的历史记忆

以下是你对用户的了解，请参考这些信息来提供更个性化的回答：

## 用户画像
- 职业：Java 开发工程师
- 兴趣：AI Agent、系统架构

## 长期记忆
- 用户正在开发一个 Spring Boot 项目
- 偏好使用 OkHttp 而非 RestTemplate

## 近期对话记忆
- 2026-05-19 讨论了 Agent 推理模式的设计
```

## 工具系统

### 内置工具

| 工具名 | 说明 | 输入格式 |
|--------|------|----------|
| `http_request` | 发起 HTTP GET/POST 请求 | `{"url": "...", "method": "GET", "body": "..."}` |
| `calculator` | 数学表达式计算（支持四则运算和括号） | `{"expression": "2+3*4"}` |

### 自定义工具

实现 `Tool` 接口并注册即可：

```java
public class MyTool implements Tool {

    @Override
    public String getName() {
        return "my_tool";
    }

    @Override
    public String getDescription() {
        return "我的自定义工具描述";
    }

    @Override
    public ToolResult execute(String input) {
        // 执行工具逻辑
        return ToolResult.success("结果");
    }
}
```

注册到 ChatFlow：

```java
toolRegistry.register(new MyTool());
```

### 技能（Skill）

Skill 是比 Tool 更高层次的抽象，是 prompt + tool 的组合，用于完成特定领域的任务。实现 `Skill` 接口即可扩展。

## API 接口

### 聊天接口

```bash
# 发送聊天消息
POST /api/chat
Content-Type: application/json

{
  "message": "帮我计算 (12 + 8) * 3 等于多少",
  "sessionId": "可选-会话ID",
  "mode": "可选-指定模式: react/plan_and_execute/reflection/chat/cronjob"
}
```

响应示例：

```json
{
  "success": true,
  "answer": "(12 + 8) * 3 = 60",
  "sessionId": "abc123",
  "mode": "react",
  "thinkSteps": [
    { "type": "ROUTING", "title": "智能路由决策", "content": "规则引擎快速匹配，命中规则: 计算类(关键词:计算)，选择模式: react" },
    { "type": "THOUGHT", "title": "Thought #1", "content": "需要计算表达式" },
    { "type": "ACTION", "title": "调用工具: calculator", "content": "工具: calculator\n参数: {\"expression\": \"(12+8)*3\"}" },
    { "type": "OBSERVATION", "title": "Observation #1", "content": "60.0" }
  ],
  "totalTokens": 850,
  "durationMs": 1200,
  "cached": false
}
```

### 定时任务接口

```bash
# 查看所有定时任务
GET /api/cron-jobs

# 通过自然语言创建定时任务
POST /api/cron-jobs
{ "message": "每天早上9点提醒我查看邮件" }

# 直接创建定时任务
POST /api/cron-jobs/direct
{ "name": "邮件提醒", "cronExpression": "0 0 9 * * ?", "query": "提醒我查看邮件", "scheduleDesc": "每天上午9:00" }

# 暂停/恢复/删除定时任务
PUT /api/cron-jobs/{id}/pause
PUT /api/cron-jobs/{id}/resume
DELETE /api/cron-jobs/{id}
```

### 记忆接口

```bash
# 记忆概览
GET /api/memory/overview

# 全局记忆
GET /api/memory/global

# 用户画像
GET /api/memory/profile

# 今日记忆
GET /api/memory/daily/today

# 指定日期记忆
GET /api/memory/daily/{date}

# 手动写入全局记忆
POST /api/memory/global
{ "content": "..." }

# 手动触发压缩
POST /api/memory/compress

# 手动触发画像更新
POST /api/memory/profile/update
```

### 飞书 Webhook

```bash
# 飞书事件回调（配置在飞书开放平台）
POST /api/feishu/webhook
```

### 健康检查

```bash
GET /api/health
# 返回: NanoClaw is running!
```

## 快速开始

### 环境要求

- JDK 8+
- Maven 3.6+

### 配置

1. 设置环境变量：

```bash
export MINIMAX_API_KEY=your_minimax_api_key
```

2. 可选配置飞书通道：

```bash
export FEISHU_APP_ID=your_app_id
export FEISHU_APP_SECRET=your_app_secret
export FEISHU_VERIFICATION_TOKEN=your_verification_token
```

### 运行

```bash
# 克隆项目
git clone <repository-url>
cd NanoClaw

# 编译
./mvnw clean package -DskipTests

# 运行
java -jar target/NanoClaw-0.0.1-SNAPSHOT.jar
```

或直接使用 Maven：

```bash
./mvnw spring-boot:run
```

启动后访问：
- Web UI：http://localhost:8080
- API 基址：http://localhost:8080/api

### 配置项

在 `application.properties` 中可配置：

```properties
# 服务端口
server.port=8080

# LLM 模型配置
nanoclaw.llm.model=MINI_MAX
nanoclaw.llm.api-key=${MINIMAX_API_KEY:}

# Agent 默认模式
nanoclaw.agent.mode=react

# 飞书通道配置
nanoclaw.feishu.app-id=${FEISHU_APP_ID:}
nanoclaw.feishu.app-secret=${FEISHU_APP_SECRET:}
nanoclaw.feishu.verification-token=${FEISHU_VERIFICATION_TOKEN:}

# 记忆模块配置
nanoclaw.memory.base-dir=./data/memory
nanoclaw.memory.compress-threshold=4000
nanoclaw.memory.max-retention-days=30

# 定时任务持久化目录
nanoclaw.cronjob.base-dir=./data/cronjobs
```

## 项目结构

```text
NanoClaw
├── src/main/java/com/nano/claw/
│   ├── controller/                     # REST / SSE / Webhook 入口
│   ├── flow/                           # ChatFlow 编排主链路
│   ├── agent/
│   │   ├── common/                     # ChatRequest / ChatResponse / ThinkStep 等公共模型
│   │   ├── core/                       # Chat / ReAct / PlanExecute / Reflection / ExpertPanel 实现
│   │   ├── expert/                     # 销售、产品、架构、开发、测试、项目经理等专家角色
│   │   ├── panel/                      # 专家协作上下文、协作消息、工作流规划
│   │   └── mcp/                        # Tool / Skill / Registry 实现
│   ├── cronjob/                        # 自然语言定时任务、调度、持久化
│   ├── memory/                         # 长期记忆、压缩、画像与 API
│   ├── workspace/                      # 专家协作工作区、产物与报告管理
│   ├── channels/                       # 飞书等 IM 通道
│   ├── llm/                            # 模型枚举、请求响应、ModelFacade
│   ├── sessions/                       # 会话管理
│   ├── messages/                       # 底层消息模型
│   ├── utils/                          # 通用工具类
│   ├── heartbeat/                      # 独立的心跳/压测工具
│   └── NanoClawApplication.java        # Spring Boot 启动类
├── src/main/resources/
│   ├── static/index.html               # Web UI（暗色主题）
│   ├── static/skills/                  # 内置技能定义文件
│   └── application.properties          # 应用配置
├── data/
│   ├── memory/                         # 长期记忆文件
│   ├── cronjobs/                       # 定时任务持久化数据
│   ├── workspaces/                     # 专家协作工作区与产出物
│   └── skills/                         # 用户自定义技能
└── pom.xml                             # Maven 配置
```

如果你准备从源码入手，建议优先阅读以下文件：

1. `controller/MainController.java`
2. `flow/ChatFlow.java`
3. `agent/core/ModeRouter.java` 和 `AgentFactory.java`
4. 你关心的具体 Agent（如 `AgentLoop.java`、`PlanExecuteAgent.java`、`ExpertPanelAgent.java`）
5. `memory/`、`cronjob/`、`workspace/` 等横向能力模块

## 技术栈

| 组件 | 技术 | 版本 |
|------|------|------|
| 框架 | Spring Boot | 2.7.8 |
| 语言 | Java | 8 |
| HTTP 客户端 | OkHttp | 4.12.0 |
| JSON 处理 | Jackson | 2.15.2 |
| LLM | MiniMax M2.7 | - |
| 构建 | Maven | - |

## 扩展指南

### 新增 Agent 模式

1. 继承 `Agent` 抽象类，实现 `run()` 方法
2. 在 `AgentFactory` 中注册新模式
3. 在 `ModeRouter` 和 `IntentRuleEngine` 中添加路由规则

### 新增工具

1. 实现 `Tool` 接口
2. 在 `ChatFlow.init()` 中注册到 `ToolRegistry`

### 新增 IM 通道

1. 实现 `Channel` 接口
2. 在 `ChatFlow.init()` 中注册到 `channels` Map
3. 在 `MainController` 中添加 Webhook 回调接口

### 新增 LLM 模型

1. 在 `Model` 枚举中添加模型
2. 在 `ModelFacade.resolveModelName()` 中映射模型名称
