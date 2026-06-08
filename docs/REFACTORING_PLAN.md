# SmartPai 重构方案：修改与删除清单

## 核心问题总结

项目当前存在 **两套并行的Agent编排架构**，导致约40-50%的代码冗余：
- **LangGraph Pipeline**（推荐保留）：7节点状态机，模块化、支持流式、可扩展
- **Service Agent Layer**（建议删除）：IntentAgent/WorkAgent/CheckAgent，与LangGraph功能完全重复

## 一、删除清单（按优先级排序）

### Phase 1：删除冗余Agent服务层（🔴 关键）

| 文件 | 原因 | 替代方案 |
|------|------|----------|
| `service/agent/IntentAgent.java` | 与 `RouterNode` 功能100%重复 | 使用 RouterNode |
| `service/agent/WorkAgent.java` | 与 `ActionNode` 功能95%重复 | 使用 ActionNode |
| `service/agent/CheckAgent.java` | 与 `CheckNode`+`HallucinationCheckNode` 重复 | 使用 LangGraph节点 |
| `service/agent/AgentOrchestrator.java` | 编排逻辑已由 `RagGraphBuilder` 实现 | 使用 LangGraphRagService |
| `service/MultiAgentChatService.java` | 仅是 AgentOrchestrator 的薄包装 | 直接调用 LangGraphRagService |

**重复代码对比：**

```java
// IntentAgent.analyze() — 与 RouterNode.apply() 完全相同的逻辑
AgentIntent intent = agentService.recognizeIntentWithPrompt(message, prompt);
// 相同的JSON解析、相同的fallback逻辑、相同的返回结构

// WorkAgent.execute() — 与 ActionNode.apply() 相同
// 相同的简单搜索判断条件
// 相同的hybridSearchService.searchWithPermission()调用
// 相同的buildContext()和模板替换

// CheckAgent.quickCheck() — 与 CheckNode.quickCheck() 逐行相同
// 空回复检查、来源标记验证、关键词匹配
```

### Phase 2：合并RAG服务冗余（🟠 高优先级）

| 文件 | 问题 | 处理方式 |
|------|------|----------|
| `service/LangChain4jRagService.java` | `buildContext()`与ActionNode重复 | **保留**，ActionNode应委托给它 |
| `langgraph/node/ActionNode.java` | 自行实现了检索+生成 | **修改**：删除内部buildContext()，调用LangChain4jRagService |
| `service/LangChain4jAgentService.java` | 职责过多(工具+意图+质量检查) | **拆分**为3个服务 |

**LangChain4jAgentService 拆分方案：**

```
当前: LangChain4jAgentService (544行，3种职责)
  ↓ 拆分为:
├── service/tool/ToolExecutionService.java    — 工具注册与执行
├── service/intent/IntentRecognitionService.java — 意图识别
└── service/quality/QualityCheckService.java  — 质量验证
```

### Phase 3：提取公共工具类（🟡 中优先级）

| 问题 | 涉及文件数 | 解决方案 |
|------|-----------|----------|
| JSON提取逻辑重复6处 | 6个文件 | 新建 `util/JsonExtractor.java` |
| Prompt模板散落各处 | 7个文件 | 集中到 `AiProperties` 配置 |
| WebSocket处理器重复 | 2个Handler | 提取 `BaseWebSocketHandler` 基类 |

**JSON提取重复位置：**
- `LangChain4jAgentService.extractJson()` (line 408)
- `CheckAgent.extractJson()` (line 177)
- `CheckNode.extractJson()` (line 233)
- `RouterNode.extractJson()` (line 124)
- `QueryAnalysisNode.parseAndApply()` (line 90)
- `HallucinationCheckNode.parseAndApply()` (line 118)

→ 统一为：
```java
public class JsonExtractor {
    public static String extract(String llmResponse) { ... }
    public static <T> T parse(String llmResponse, Class<T> type) { ... }
}
```

## 二、修改清单

### 2.1 ActionNode 重构（委托给 RagService）

**当前问题：** ActionNode 内部重新实现了检索+上下文构建逻辑

**修改方案：**
```java
// ActionNode.java — 修改后
@Override
public AIState apply(AIState state) {
    if (isSimpleSearch(state)) {
        List<SearchResult> results = ragService.searchWithPermission(...);
        state.setFinalReply(formatResults(results));
    } else {
        // 委托给 RagService，不再自行 buildContext
        String reply = ragService.ask(state.getRewrittenQuery(), state.getUserId());
        state.setGeneratedReply(reply);
    }
    return state;
}
```

### 2.2 WebSocket Handler 提取基类

**当前问题：** ChatWebSocketHandler 和 MultiAgentWebSocketHandler 有相同的连接管理、会话提取、错误处理代码

**修改方案：**
```java
public abstract class BaseWebSocketHandler extends TextWebSocketHandler {
    // 公共：连接管理、session提取、safeSend、错误处理
    protected abstract void handleBusinessMessage(WebSocketSession session, String payload);
}

public class ChatWebSocketHandler extends BaseWebSocketHandler { ... }
public class MultiAgentWebSocketHandler extends BaseWebSocketHandler { ... }
```

### 2.3 Config包清理

**当前问题：** config/ 下有23个类，混合了配置、过滤器、初始化器

**修改方案：**
```
config/                          → 纯@Configuration类
config/filter/                   → JwtAuthenticationFilter, OrgTagAuthorizationFilter
config/initializer/              → AdminUserInitializer, EsIndexInitializer
config/properties/               → AiProperties, QaMemoryProperties, RouterProperties
config/interceptor/              → LoggingInterceptor
```

### 2.4 entity vs model 包合并

**当前问题：** `model/` 放JPA实体，`entity/` 放DTO，命名容易混淆

**修改方案：**
```
model/           → 保持不变（JPA实体）
dto/             → 原entity/重命名（DTO更符合行业惯例）
```

## 三、重构后目标架构

```
com.yizhaoqi.smartpai/
├── config/                    # 纯配置
│   ├── properties/            # @ConfigurationProperties
│   ├── filter/                # 安全过滤器
│   ├── initializer/           # 启动初始化
│   └── interceptor/           # HTTP拦截器
├── controller/                # REST + WebSocket入口
├── handler/                   # WebSocket处理器（含基类）
├── langgraph/                 # ★ 核心：LangGraph状态机
│   ├── core/                  # 图引擎
│   ├── node/                  # 节点实现
│   ├── state/                 # 状态定义
│   ├── event/                 # 流式事件
│   └── service/               # 图执行服务
├── service/                   # 业务服务
│   ├── chat/                  # 聊天相关
│   ├── rag/                   # RAG检索生成
│   ├── embedding/             # 向量化
│   ├── search/                # 搜索服务
│   ├── document/              # 文档管理
│   ├── tool/                  # 工具执行（从LangChain4jAgentService拆出）
│   ├── intent/                # 意图识别（从LangChain4jAgentService拆出）
│   ├── quality/               # 质量检查（从LangChain4jAgentService拆出）
│   ├── agent/                 # Agent编排
│   │   ├── multiagent/        # Kafka多Agent（保留）
│   │   └── tools/             # Agent工具定义（保留）
│   ├── auth/                  # 认证授权
│   └── cache/                 # 缓存服务
├── model/                     # JPA实体
├── dto/                       # 数据传输对象（原entity/）
├── repository/                # 数据访问
├── mcp/                       # MCP协议（保留）
├── consumer/                  # Kafka消费者
├── util/                      # 工具类
│   └── JsonExtractor.java     # JSON提取（新建）
└── exception/                 # 异常
```

## 四、删除文件汇总

| # | 文件路径 | 行数 | 删除原因 |
|---|----------|------|----------|
| 1 | `service/agent/IntentAgent.java` | ~95 | 与RouterNode 100%重复 |
| 2 | `service/agent/WorkAgent.java` | ~145 | 与ActionNode 95%重复 |
| 3 | `service/agent/CheckAgent.java` | ~210 | 与CheckNode+HallucinationCheckNode重复 |
| 4 | `service/agent/AgentOrchestrator.java` | ~180 | 被LangGraphRagService替代 |
| 5 | `service/MultiAgentChatService.java` | ~17 | 无意义的薄包装层 |

**预计删除代码量：~650行**
**预计减少重复率：40-50%**

## 五、保留并强化的模块

| 模块 | 原因 |
|------|------|
| `langgraph/` 全部 | 工业级状态机，Self-RAG+CRAG模式 |
| `service/agent/multiagent/` | Kafka并行编排，性能提升19% |
| `service/agent/tools/` | ReAct工具定义，可扩展 |
| `service/agent/ToolBasedAgentService.java` | ReAct框架，与LangGraph互补 |
| `mcp/` 全部 | MCP协议支持，行业标准 |
| `LangChain4jChatService` | 基础聊天能力 |
| `LangChain4jRagService` | RAG核心（ActionNode应委托给它） |
| `LangChain4jEmbeddingService` | 向量化服务 |

## 六、执行建议

### 执行顺序（低风险渐进式）

1. **先建后拆**：先创建 `JsonExtractor`、`BaseWebSocketHandler` 等公共类
2. **修改引用**：让 ActionNode 委托 RagService，验证功能不变
3. **删除冗余**：确认LangGraph路径完全覆盖后，删除Service Agent层
4. **包重组**：移动文件到新包结构，更新import
5. **回归测试**：全链路验证

### 风险点

- `AgentOrchestrator` 可能被 `MultiAgentWebSocketHandler` 直接引用，需检查调用链
- `AgentConfig.java` 中注册了 IntentAgent/WorkAgent/CheckAgent 的Bean，删除后需同步清理
- Kafka多Agent系统可能依赖 `AgentOrchestrator` 的某些方法，需确认隔离性

### LangChain4j 最佳实践对齐

当前项目已较好地使用了 LangChain4j，但可进一步：
- 使用 `AiServices` 接口代替手动prompt构建（减少模板代码）
- 使用 `ContentRetriever` 接口标准化检索逻辑
- 使用 `@Tool` 注解替代手动工具注册（ToolBasedAgentService中）
