# SmartPai 项目优化文档

> 版本：2.0 | 日期：2026-05-16 | 作者：杨志博

---

## 一、优化概览

本次优化将 SmartPai 从"基础 RAG + 手动编排 Agent"升级为**企业级 Agentic RAG 系统**，
引入学术界最新 RAG 优化范式（CRAG、Self-RAG、ReAct），并补齐可观测性短板。

| 维度 | 优化前 | 优化后 |
|------|--------|--------|
| RAG 流水线 | 检索 → 生成（2步） | 查询改写 → 检索 → 评分过滤 → 生成 → 幻觉检测（5步） |
| Agent 编排 | 手动 Intent→Work→Check | LangGraph 状态机 + Tool-based ReAct 双模式 |
| 幻觉控制 | 规则质检（关键词匹配） | Self-RAG 幻觉检测 + CRAG 文档评分双重防护 |
| 可观测性 | 仅日志 | Micrometer + Prometheus 指标全覆盖 |
| 工具调用 | 无 | LangChain4j @Tool 注解，Agent 自主决策 |

---

## 二、Agentic RAG 架构

### 2.1 完整流水线（7节点状态图）

```
用户请求
    │
    ▼
┌─────────────┐
│  MemoryNode │  ← 检查 Redis Q&A 缓存，命中直接返回（零 LLM 成本）
└──────┬──────┘
       │ 未命中
       ▼
┌──────────────────┐
│ QueryAnalysisNode│  ← 查询改写：补全代词、展开缩写、消解指代
└──────┬───────────┘    输出：rewrittenQuery, needsWebSearch
       │
       ▼
┌─────────────┐
│  RouterNode │  ← 意图分类（SEARCH/QA/COMPARE/SUMMARIZE/CHAT）
└──────┬──────┘    输出：routeDecision
       │
       ▼
┌─────────────┐
│  ActionNode │  ← 混合检索（KNN向量 + BM25关键词），优先使用改写查询
└──────┬──────┘    输出：searchResults（8条原始候选）
       │
       ▼
┌─────────────┐
│  GradingNode│  ← 文档相关性评分（LLM打分0-10，≥6保留）
└──────┬──────┘    输出：gradedResults, sufficientContext
       │ sufficientContext=false → 回到 ActionNode 重检索（最多1次）
       │
       ▼
┌──────────────────────┐
│ HallucinationCheckNode│ ← Self-RAG 幻觉检测（回复与文档对比）
└──────┬───────────────┘   输出：hallucinationPassed, hallucinationScore
       │ 检测失败 → 回到 ActionNode 重生成（最多2次）
       │
       ▼
┌─────────────┐
│  CheckNode  │  ← 规则质检（长度/关键词/来源标注）
└──────┬──────┘    输出：checkPassed
       │
       ▼
    最终回复
```

### 2.2 设计依据

| 节点 | 对应论文/方法 | 解决的问题 |
|------|--------------|-----------|
| QueryAnalysisNode | HyDE（Hypothetical Document Embeddings）思路 | 模糊查询检索精度低 |
| GradingNode | CRAG（Corrective RAG，2024） | 无关文档污染上下文导致幻觉 |
| HallucinationCheckNode | Self-RAG（Asai et al., 2023） | LLM 生成内容与检索文档不一致 |
| 重试路由 | Reflexion 框架思路 | 单次生成质量不稳定 |

---

## 三、双模式 Agent 系统

### 3.1 模式对比

| 特性 | AgentOrchestrator（编排模式） | ToolBasedAgentService（ReAct模式） |
|------|------------------------------|-----------------------------------|
| 流程控制 | 开发者手动定义 | LLM 自主决策 |
| 工具调用 | 固定顺序 | 按需调用，可多次 |
| 适用场景 | 流程固定的标准问答 | 复杂多步骤、工具选择不确定 |
| 可解释性 | 高（每步有日志） | 中（依赖 LLM 推理） |
| 成本 | 低（可跳过 LLM） | 较高（多轮 LLM 调用） |

### 3.2 AgentOrchestrator 流程

```
用户消息
    │
    ├─ Step 0: MemoryAgent（检查历史缓存）
    │       命中 → 直接返回
    │
    ├─ Step 1: IntentAgent（意图识别）
    │       置信度 < 0.6 → 降级
    │
    ├─ Step 2: WorkAgent（RAG检索+生成）
    │       简单任务：关键词检索，不调用 LLM
    │       复杂任务：混合检索 + LLM 生成
    │
    └─ Step 3: CheckAgent（质量检查）
            快速规则检查 → 深度 LLM 检查
            通过 → 保存记忆 → 返回
            不通过 → 降级回复
```

### 3.3 Tool-based Agent（ReAct 模式）

```java
// Agent 自主决策工具调用，无需手动编排
SmartPaiAgent agent = AiServices.builder(SmartPaiAgent.class)
    .chatLanguageModel(chatLanguageModel)
    .tools(knowledgeBaseTool, systemTool)   // 注册工具
    .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
    .build();
```

**可用工具：**

| 工具 | 触发条件 | 说明 |
|------|---------|------|
| `searchKnowledgeBase` | 查询知识库内容 | 混合检索，带权限过滤 |
| `listRelatedDocuments` | 询问有哪些文档 | 返回文档名称列表 |
| `getCurrentDateTime` | 询问当前时间 | 避免时间幻觉 |
| `generateFallbackReply` | 无法回答时 | 诚实兜底，引导用户 |

---

## 四、可观测性

### 4.1 Prometheus 指标

访问路径：`GET /actuator/prometheus`

| 指标名 | 类型 | 说明 |
|--------|------|------|
| `rag_query_total` | Counter | RAG 查询总次数 |
| `rag_cache_hit_total` | Counter | 缓存命中次数 |
| `rag_retrieval_duration_seconds` | Timer | 检索耗时（P50/P95/P99） |
| `rag_generation_duration_seconds` | Timer | LLM 生成耗时 |
| `agent_pipeline_duration_seconds` | Timer | Agent 完整流水线耗时 |
| `hallucination_detected_total` | Counter | 幻觉检测触发次数 |
| `grading_insufficient_total` | Counter | 文档不足触发重检索次数 |

### 4.2 健康检查

```bash
GET /actuator/health   # 服务健康状态
GET /actuator/metrics  # 所有指标列表
```

---

## 五、新增文件清单

```
src/main/java/com/yizhaoqi/smartpai/
├── langgraph/
│   ├── node/
│   │   ├── QueryAnalysisNode.java      ← 新增：查询改写节点
│   │   ├── GradingNode.java            ← 新增：文档评分节点（CRAG）
│   │   └── HallucinationCheckNode.java ← 新增：幻觉检测节点（Self-RAG）
│   ├── core/
│   │   └── RagGraphBuilder.java        ← 重构：7节点 Agentic RAG 图
│   └── state/
│       └── AIState.java                ← 扩展：新增评分/幻觉检测字段
├── service/agent/
│   ├── tools/
│   │   ├── KnowledgeBaseTool.java      ← 新增：@Tool 知识库工具
│   │   └── SystemTool.java             ← 新增：@Tool 系统工具
│   └── ToolBasedAgentService.java      ← 新增：ReAct 模式 Agent
└── config/
    └── ObservabilityConfig.java        ← 新增：Micrometer 指标配置
```

---

## 六、待完善事项

| 优先级 | 事项 | 说明 |
|--------|------|------|
| P0 | 单元测试 | 为 GradingNode、HallucinationCheckNode 补充测试 |
| P1 | 网络搜索兜底 | 接入 SerpAPI，当 needsWebSearch=true 时触发 |
| P1 | 合并 util/utils 包 | 消除重复工具包 |
| P2 | 流式幻觉检测 | 当前幻觉检测为同步，可改为异步后台校验 |
| P2 | Grafana 看板 | 基于 Prometheus 指标搭建监控大盘 |
