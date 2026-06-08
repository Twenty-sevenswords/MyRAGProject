# SmartPai 项目架构文档

## 一、系统概述

SmartPai 是一个企业级 Agentic RAG（检索增强生成）系统，基于 Spring Boot 3.4.2 + Vue 3 构建，核心使用 LangChain4j 1.0.0-beta2 框架实现 LLM 编排。

## 二、技术栈

| 层级 | 技术 | 版本 |
|------|------|------|
| 后端框架 | Spring Boot | 3.4.2 |
| LLM编排 | LangChain4j | 1.0.0-beta2 |
| 向量存储 | Elasticsearch | 8.10.0 |
| 消息队列 | Kafka | 3.2.1 |
| 缓存 | Redis + Redisson | 3.27.2 |
| 数据库 | MySQL | 8.0 |
| 对象存储 | MinIO | 8.5.12 |
| 前端 | Vue 3 + TypeScript + Naive UI | - |

## 三、核心架构图

```
┌─────────────────────────────────────────────────────────────┐
│                      Frontend (Vue 3)                         │
│  WebSocket ──────────────────────────── REST API             │
└──────────┬──────────────────────────────────┬───────────────┘
           │                                  │
┌──────────▼──────────────────────────────────▼───────────────┐
│                   Controller Layer                            │
│  ChatController | DocumentController | SearchController      │
└──────────┬──────────────────────────────────────────────────┘
           │
┌──────────▼──────────────────────────────────────────────────┐
│                   Service Layer (当前存在冗余)                 │
│                                                              │
│  ┌─────────────────────┐  ┌────────────────────────────┐    │
│  │ LangGraph Pipeline  │  │ Agent Service Layer (冗余)  │    │
│  │ (7-Node State Machine)│  │ IntentAgent/WorkAgent/     │    │
│  │                     │  │ CheckAgent/Orchestrator    │    │
│  └─────────────────────┘  └────────────────────────────┘    │
│                                                              │
│  ┌─────────────────────┐  ┌────────────────────────────┐    │
│  │ LangChain4j Services│  │ Multi-Agent Kafka System   │    │
│  │ Chat/RAG/Embedding  │  │ (并行检索编排)              │    │
│  └─────────────────────┘  └────────────────────────────┘    │
└──────────────────────────────────────────────────────────────┘

## 四、LangGraph Pipeline（核心推荐架构）

这是系统最成熟的部分，实现了 Self-RAG + CRAG 模式的 7 节点状态机：

```
START → MemoryNode → QueryAnalysisNode → RouterNode → ActionNode
                                                          │
                         END ← CheckNode ← HallucinationCheckNode ← GradingNode
```

| 节点 | 职责 | 位置 |
|------|------|------|
| MemoryNode | Redis缓存命中检查 | langgraph/node/ |
| QueryAnalysisNode | 查询改写+意图检测 | langgraph/node/ |
| RouterNode | 意图分类路由 | langgraph/node/ |
| ActionNode | 混合检索+LLM生成 | langgraph/node/ |
| GradingNode | 文档相关性评分(CRAG) | langgraph/node/ |
| HallucinationCheckNode | 幻觉检测(Self-RAG) | langgraph/node/ |
| CheckNode | 质量门控 | langgraph/node/ |

## 五、包结构说明

```
com.yizhaoqi.smartpai/
├── config/          # 配置类（23个，含过滤器、初始化器）
├── controller/      # REST控制器（10个）
├── service/         # 业务服务（50+，存在大量冗余）
│   ├── agent/       # Agent编排服务
│   │   ├── multiagent/  # Kafka多Agent并行
│   │   └── tools/       # Agent工具定义
├── langgraph/       # LangGraph状态机（推荐核心）
│   ├── core/        # 图引擎
│   ├── node/        # 节点实现
│   ├── state/       # 状态定义
│   ├── event/       # 流式事件
│   └── service/     # 图执行服务
├── handler/         # WebSocket处理器
├── model/           # JPA实体
├── entity/          # DTO
├── repository/      # 数据访问
├── mcp/             # Model Context Protocol
├── consumer/        # Kafka消费者
└── exception/       # 异常定义
```

## 六、数据流

### 6.1 用户提问流程
```
用户消息 → WebSocket → ChatWebSocketHandler
  → LangGraphRagService.execute(state)
    → MemoryNode: 检查Redis缓存
    → QueryAnalysisNode: 改写查询、扩展缩写、解析代词
    → RouterNode: 分类意图(SEARCH/COMPARE/SUMMARIZE/QA/CHAT)
    → ActionNode: 混合检索(KNN+BM25) + LLM生成
    → GradingNode: 文档相关性评分(≥6分通过)
    → HallucinationCheckNode: 幻觉检测(≥70分通过)
    → CheckNode: 质量门控(长度/关键词/来源引用)
  → WebSocket推送结果
```

### 6.2 文档上传流程
```
文件上传 → UploadController → UploadService
  → MinIO存储原文件
  → Tika解析文本
  → 文本分块(ChunkInfo)
  → LangChain4jEmbeddingService向量化(2048维)
  → Elasticsearch索引存储
```

### 6.3 Kafka多Agent并行检索
```
请求 → MultiAgentKafkaOrchestrator
  ├→ agent.vector.retrieval (语义检索)
  ├→ agent.keyword.retrieval (关键词检索)
  → agent.retrieval.results (Redisson锁合并)
  → agent.reflection.tasks (文档评分过滤)
  → agent.summary.tasks (最终生成)
  → WebSocket推送
```

## 七、关键设计模式

1. **状态机模式** - LangGraph实现节点间状态传递
2. **CRAG模式** - 检索后评分，低分文档被过滤
3. **Self-RAG模式** - 生成后幻觉检测，不合格则重试
4. **混合检索** - KNN语义搜索 + BM25全文搜索融合
5. **分布式锁** - Redisson保证并行结果原子合并
6. **权限过滤** - userId + orgTags 实现文档级权限控制
