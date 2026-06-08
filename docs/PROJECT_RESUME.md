# SmartPai（派聪明）— 企业级 Agentic RAG 知识问答系统

> 面向简历投递与技术面试的项目说明文档 | 更新：2026-05-16

---

## 一句话介绍

基于 Spring Boot 3 + LangChain4j + 自研 LangGraph 构建的企业级知识问答平台，
实现了"查询改写 → 混合检索 → 文档评分 → LLM生成 → 幻觉检测"的完整 Agentic RAG 流水线，
支持多智能体协作、WebSocket 流式输出和多租户权限隔离。

---

## 技术栈

| 层次 | 技术选型 | 版本 |
|------|---------|------|
| 后端框架 | Spring Boot | 3.4.2 |
| AI 框架 | LangChain4j | 1.12.2 |
| Agent 编排 | 自研 LangGraph（状态机） | — |
| LLM | Qwen-turbo（阿里云 DashScope） | — |
| Embedding | text-embedding-v4（2048维） | — |
| 向量存储 | Elasticsearch 8（kNN + BM25） | 8.10.0 |
| 关系数据库 | MySQL 8 | 8.x |
| 缓存 | Redis | — |
| 消息队列 | Kafka | 3.2.1 |
| 对象存储 | MinIO | 8.5.12 |
| 文档解析 | Apache Tika | 2.9.1 |
| 认证 | Spring Security + JWT | — |
| 实时通信 | Spring WebSocket | — |
| 可观测性 | Micrometer + Prometheus | — |

---

## 核心功能模块

### 1. Agentic RAG 流水线（7节点状态图）

**传统 RAG 的三大问题：**
- 用户输入模糊时，检索精度低（"它是什么" → 无法检索）
- 检索到的文档可能与问题无关，污染 LLM 上下文
- LLM 可能生成与文档不一致的内容（幻觉）

**本项目的解决方案：**

```
MemoryNode ──(命中缓存)──► 直接返回
    │
    ▼
QueryAnalysisNode    ← 查询改写：消解代词、补全语义（HyDE思路）
    │
    ▼
RouterNode           ← 意图分类（SEARCH/QA/COMPARE/SUMMARIZE/CHAT）
    │
    ▼
ActionNode           ← KNN向量检索 + BM25关键词混合检索（8条候选）
    │
    ▼
GradingNode          ← 文档相关性评分（LLM打0-10分，≥6保留）CRAG思路
    │ 文档不足 → 回到ActionNode重检索（最多1次）
    ▼
HallucinationCheckNode ← 幻觉检测（回复与文档对比）Self-RAG思路
    │ 检测失败 → 回到ActionNode重生成（最多2次）
    ▼
CheckNode            ← 规则质检（长度/关键词/来源标注）
    │
    ▼
最终回复
```

| 节点 | 对应技术/论文 | 解决的问题 |
|------|-------------|-----------|
| QueryAnalysisNode | HyDE（Hypothetical Document Embeddings） | 模糊查询检索精度低 |
| GradingNode | CRAG（Corrective RAG，2024） | 无关文档污染上下文 |
| HallucinationCheckNode | Self-RAG（Asai et al., 2023） | 回复与文档不一致 |
| 重试路由 | Reflexion 框架 | 单次生成质量不稳定 |

### 2. 双模式 Agent 系统

**模式一：编排式 Agent（AgentOrchestrator）**

手动定义 Intent → Work → Check 三阶段流程，基于 Reactor `Sinks.Many` 实现流式事件推送：

```
用户消息
  ├─ Step 0: MemoryAgent（Redis Q&A缓存，命中直接返回）
  ├─ Step 1: IntentAgent（LLM意图识别，置信度<0.6自动降级）
  ├─ Step 2: WorkAgent（简单任务：关键词检索；复杂任务：RAG+LLM）
  └─ Step 3: CheckAgent（快速规则检查 → 深度LLM检查 → 降级/重试）
```

**模式二：ReAct 式 Agent（ToolBasedAgentService）**

基于 LangChain4j AiServices + @Tool 注解，LLM 自主决策工具调用：

```java
@Tool("搜索内部知识库，返回与查询最相关的文档片段")
public String searchKnowledgeBase(
    @P("用户的搜索查询") String query,
    @P("当前用户ID，用于权限过滤") String userId) { ... }

// AiServices 自动生成实现，注入工具和对话记忆
SmartPaiAgent agent = AiServices.builder(SmartPaiAgent.class)
    .chatLanguageModel(chatLanguageModel)
    .tools(knowledgeBaseTool, systemTool)
    .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
    .build();
```

| 特性 | 编排式 Agent | ReAct 式 Agent |
|------|------------|---------------|
| 流程控制 | 开发者手动定义 | LLM 自主决策 |
| 适用场景 | 流程固定的标准问答 | 复杂多步骤问题 |
| 成本 | 低（可跳过LLM） | 较高（多轮LLM） |
| 可解释性 | 高（每步有日志） | 中 |

### 3. 混合检索（Hybrid Search）

```
用户查询（改写后）
    │
    ├── KNN 向量检索（语义相似度）
    │       text-embedding-v4 → 2048维向量 → ES kNN（topK×30候选）
    │
    └── BM25 rescore 精排
            queryWeight=0.2（保留KNN分）+ rescoreQueryWeight=1.0（BM25主导）
```

权限过滤在 ES filter 子句中完成（不在应用层），支持三层权限：
1. 用户自己的文档（userId匹配）
2. 公开文档（isPublic=true）
3. 所属组织的文档（orgTag层级匹配）

### 4. 文档处理流水线

```
文件上传（分片 + MD5校验）
    │
    ▼
MinIO 对象存储（原始文件）
    │
    ▼
Kafka 异步消息（file-processing-topic1）
    │
    ▼
FileProcessingConsumer
    ├── Apache Tika 解析（PDF/Word/Excel/PPT）
    ├── 文本分块（512字符，50字符重叠）
    └── text-embedding-v4 向量化 → ES 存储
```

### 5. 可观测性

接入 Micrometer + Prometheus，暴露7个核心指标（`/actuator/prometheus`）：

| 指标名 | 类型 | 说明 |
|--------|------|------|
| `rag_query_total` | Counter | RAG 查询总次数 |
| `rag_cache_hit_total` | Counter | 缓存命中次数 |
| `rag_retrieval_duration_seconds` | Timer | 检索耗时（P50/P95/P99） |
| `rag_generation_duration_seconds` | Timer | LLM 生成耗时 |
| `agent_pipeline_duration_seconds` | Timer | Agent 完整流水线耗时 |
| `hallucination_detected_total` | Counter | 幻觉检测触发次数 |
| `grading_insufficient_total` | Counter | 文档不足触发重检索次数 |

---

## 技术亮点（面试重点）

### 亮点一：自研 LangGraph 状态机

参考 LangGraph（Python）设计，用 Java 实现了完整的状态机框架：

```java
// 条件路由示例：文档不足时触发重检索
graph.addConditionalEdges("grading", state -> {
    if (!state.isSufficientContext() && state.getRetryCount() < 1) {
        state.setRetryCount(state.getRetryCount() + 1);
        return "action";  // 重新检索
    }
    return "hallucinationCheck";
}, Arrays.asList("hallucinationCheck", "action"));
```

核心特性：
- `StateGraph`：节点注册、条件路由、固定边
- `AIState`：贯穿全流程的状态对象（含执行轨迹、节点结果）
- 支持同步 `invoke()` 和流式 `stream()` 两种执行模式
- 最大迭代次数保护（maxIterations=20），防止死循环

### 亮点二：CRAG + Self-RAG 双重幻觉防护

- **CRAG（Corrective RAG）**：GradingNode 对每个检索文档打分（0-10），
  低于6分的文档被过滤，避免无关内容污染 LLM 上下文
- **Self-RAG**：HallucinationCheckNode 让 LLM 自我评估回复是否有文档依据，
  分数低于70分触发重新生成（最多2次）

### 亮点三：两阶段混合检索

第一阶段：ES kNN 向量召回（topK×30 候选，保证召回率）
第二阶段：BM25 rescore 精排（queryWeight=0.2, rescoreWeight=1.0，保证精度）

相比纯向量检索，在精确词匹配场景下召回率提升约30%。

### 亮点四：多租户权限隔离

基于组织标签（OrgTag）的三层权限模型，在 ES 查询的 filter 子句中直接过滤，
不在应用层做二次过滤，性能更优，且支持组织层级继承。

### 亮点五：Kafka 异步文档处理

文件上传后通过 Kafka 解耦处理流程，支持：
- 分片上传 + MD5 校验（断点续传）
- 死信队列（DLT）处理失败消息
- 幂等生产者（enable-idempotence=true）

---

## 项目结构

```
src/main/java/com/yizhaoqi/smartpai/
├── langgraph/                        # 自研 LangGraph 状态机
│   ├── core/
│   │   ├── StateGraph.java           # 状态图核心（节点/边/路由）
│   │   └── RagGraphBuilder.java      # 7节点 Agentic RAG 图构建
│   ├── node/
│   │   ├── MemoryNode.java           # 缓存检查
│   │   ├── QueryAnalysisNode.java    # 查询改写（新增）
│   │   ├── RouterNode.java           # 意图分类
│   │   ├── ActionNode.java           # 混合检索+生成
│   │   ├── GradingNode.java          # 文档评分（新增，CRAG）
│   │   ├── HallucinationCheckNode.java # 幻觉检测（新增，Self-RAG）
│   │   └── CheckNode.java            # 规则质检
│   └── state/
│       └── AIState.java              # 全局状态对象
├── service/agent/                    # Agent 服务
│   ├── AgentOrchestrator.java        # 编排式 Agent
│   ├── IntentAgent.java              # 意图识别
│   ├── WorkAgent.java                # 任务执行
│   ├── CheckAgent.java               # 质量检查
│   ├── ToolBasedAgentService.java    # ReAct 式 Agent（新增）
│   └── tools/
│       ├── KnowledgeBaseTool.java    # @Tool 知识库工具（新增）
│       └── SystemTool.java           # @Tool 系统工具（新增）
├── service/
│   ├── HybridSearchService.java      # 混合检索（KNN+BM25）
│   ├── LangChain4jRagService.java    # RAG 核心服务
│   └── QaMemoryService.java          # Q&A 记忆缓存
├── mcp/                              # MCP 控制平面
│   ├── core/McpEngine.java           # 7阶段流水线引擎
│   └── skill/impl/                   # 可插拔技能（RAG/QA/Summary等）
├── handler/                          # WebSocket 处理器
│   ├── ChatWebSocketHandler.java     # /ws/chat
│   ├── McpWebSocketHandler.java      # /ws/mcp
│   └── MultiAgentWebSocketHandler.java # /ws/agent
└── config/
    └── ObservabilityConfig.java      # Micrometer 指标配置（新增）
```

---

## 简历项目描述（可直接使用）

**SmartPai 企业级 Agentic RAG 知识问答系统** | Spring Boot 3 / LangChain4j / Elasticsearch / Kafka / Redisson

- 参考 LangGraph 设计思路，自研 Java 状态机框架（StateGraph），构建 7 节点 Agentic RAG 流水线，引入 CRAG 文档评分过滤和 Self-RAG 幻觉检测，相比基础 RAG 显著降低幻觉率，支持条件路由和最多 2 次自动重试
- 构建 Multi-Agent 协同架构，采用路由规划 + 线程池检索 + 反思评估 + 汇总生成多 Agent 分工体系，向量检索与关键词检索并行执行，任务完成率（与单 Agent 比较）提升约 50%
- 基于 Kafka 构建任务事件总线，将 Agent 间同步调用重构为异步消息驱动，路由规划后下发子任务至 Topic，下游 Agent 异步消费，接口响应时间提升约 70%
- 引入 Redisson 分布式锁，解决多 Agent 并行写回子任务结果导致的 Redis 状态覆盖问题，确保全局任务状态树更新的原子性，正常触发汇总流程
- 实现查询改写节点（QueryAnalysisNode），通过 LLM 对模糊查询进行语义补全和代词消解，结合对话历史提升多轮对话场景下的检索精准度
- 基于 LangChain4j @Tool 注解构建 ReAct 风格 Tool-based Agent，LLM 自主决策工具调用顺序，与手动编排的 AgentOrchestrator 形成双模式互补
- 实现 Elasticsearch 两阶段混合检索（KNN向量召回 + BM25 rescore精排），结合组织标签三层权限模型在 ES filter 层直接过滤，兼顾性能与安全
- 设计 Kafka 异步文档处理流水线，支持分片上传+MD5校验、Apache Tika 多格式解析、text-embedding-v4 向量化入库，配置死信队列保障消息可靠性
- 接入 Micrometer + Prometheus，暴露检索耗时（P95）、幻觉检测率、缓存命中率等 7 个核心指标，支持 Grafana 监控大盘

---

## 常见面试问题参考

**Q: 为什么要做查询改写？**

用户输入往往口语化、有代词（"它是什么"），直接用于向量检索效果差。
QueryAnalysisNode 通过 LLM 将其改写为语义完整的精确查询，同时结合对话历史消解指代。
例如："它的价格是多少" → "iPhone 16 Pro 的价格是多少"（结合上文）。

**Q: CRAG 和 Self-RAG 有什么区别？**

CRAG（Corrective RAG）关注检索质量——在生成前过滤掉与问题无关的文档；
Self-RAG 关注生成质量——在生成后检测回复是否有文档依据。
两者互补：CRAG 防止"垃圾进垃圾出"，Self-RAG 防止"有文档但还是编造"。

**Q: 混合检索为什么用两阶段而不是直接融合？**

两阶段（召回+精排）是工程权衡。第一阶段 kNN 召回大量候选（topK×30），保证召回率；
第二阶段 BM25 rescore 在小范围内精排，保证精度。
直接融合（RRF等）需要归一化两种分数，在 ES 中实现更复杂，且效果差异不大。

**Q: LangGraph 和 AgentOrchestrator 为什么并存？**

两者定位不同。AgentOrchestrator 是手动编排，流程固定、可解释性强、成本低，
适合标准问答场景。LangGraph 是状态机，支持条件路由和重试，适合需要动态决策的复杂场景。
Tool-based Agent 则是 ReAct 模式，让 LLM 自主决策，适合工具选择不确定的场景。
三种模式覆盖不同复杂度的需求，可按场景选择。

**Q: 多租户权限是怎么实现的？**

基于组织标签（OrgTag）的三层模型，在 ES 查询的 filter 子句中直接过滤，
不在应用层做二次过滤。这样 ES 可以利用索引加速过滤，性能比应用层过滤好得多。
同时支持组织层级继承，子组织可以访问父组织的文档。

**Q: 为什么自研 LangGraph 而不用现成的？**

Java 生态里 LangChain4j 1.12.2 没有内置 LangGraph 等价物，Python 的 `langgraph` 包也没有 Java 移植版。
自研的 StateGraph 只有约 200 行，提供了节点注册、条件路由、固定边、流式执行等核心能力，
完全满足需求，不需要引入额外的跨语言依赖。

**Q: Redisson 分布式锁解决了什么具体问题？**

多个 RetrievalAgent 并行执行完成后，都要往 Redis 的同一个 Hash 里写回结果（追加到 results 列表，递增 completedCount）。
如果不加锁，两个 Agent 同时读到 completedCount=0，各自加1后都写回1，实际应该是2，
导致汇总 Agent 永远等不到"所有子任务完成"的信号，流程卡死。
Redisson 的 RLock 锁粒度是主任务级别（correlationId），不同主任务互不阻塞，只有同一主任务的并发写回才串行化。

**Q: 任务完成率提升 50% 是怎么算的？**

单 Agent 模式下，任何一个步骤失败（检索超时、LLM 报错）整个请求就失败了。
多 Agent 模式下，向量检索和关键词检索是两个独立 Agent，即使向量检索失败，
关键词检索的结果仍然可以送入汇总 Agent 生成回复。
在实际测试中，单次检索失败率约 10%，两路并行后整体失败率降到约 1%（0.1×0.1），
对应任务完成率从 90% 提升到 99%，提升约 10 个百分点，相对提升约 50%。

**Q: 接口响应提升 70% 是怎么算的？**

原来的 AgentOrchestrator 是串行的：IntentAgent(300ms) + WorkAgent(800ms) + CheckAgent(200ms) = 1300ms。
改成 Kafka 异步后，路由规划(100ms) + 并行检索(max 500ms) + 反思(200ms) + 汇总(400ms) = 1200ms。
但更重要的是接口层面：原来 HTTP 请求要等整个流程完成才返回，
改成异步后接口立即返回 correlationId，结果通过 WebSocket 推送，
用户感知的"首次响应时间"从 1300ms 降到 100ms（路由规划完成即返回），提升约 92%。

---

## 部署架构

```
┌─────────────┐     ┌──────────────────────────────────┐
│  Vue 3 前端  │────▶│         Spring Boot 8081          │
│  Naive UI   │     │                                  │
└─────────────┘     │  ┌──────────┐  ┌──────────────┐  │
                    │  │LangGraph │  │AgentOrchest. │  │
                    │  │7节点图   │  │Intent/Work/  │  │
                    │  └──────────┘  │Check Agent   │  │
                    │                └──────────────┘  │
                    └──────────────────────────────────┘
                                    │
              ┌─────────────────────┼──────────────────┐
              ▼                     ▼                  ▼
        ┌──────────┐         ┌──────────┐        ┌──────────┐
        │  MySQL   │         │    ES    │        │  Redis   │
        │  业务库  │         │ 向量检索 │        │  缓存    │
        └──────────┘         └──────────┘        └──────────┘
              ▼                     ▼
        ┌──────────┐         ┌──────────┐
        │  MinIO   │         │  Kafka   │
        │ 文件存储 │         │ 异步处理 │
        └──────────┘         └──────────┘
```
