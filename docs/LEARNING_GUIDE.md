# SmartRAG 项目学习文档

## 目录

1. [项目概述](#1-项目概述)
2. [核心架构](#2-核心架构)
3. [MCP 控制平面](#3-mcp-控制平面)
4. [Skill 技能系统](#4-skill-技能系统)
5. [Function Calling 机制](#5-function-calling-机制)
6. [PreRouter 前置路由](#6-prerouter-前置路由)
7. [Agent 协作模式](#7-agent-协作模式)
8. [RAG 检索增强生成](#8-rag-检索增强生成)
9. [记忆系统](#9-记忆系统)
10. [实战示例](#10-实战示例)

---

## 1. 项目概述

### 1.1 项目定位

SmartRAG 是一个企业级智能问答系统，融合了以下核心技术：

- **RAG（检索增强生成）**：结合知识库检索与大模型生成
- **MCP（Model Context Protocol）**：统一的模型上下文协议
- **Function Calling**：LLM 函数调用能力
- **Multi-Agent**：多智能体协作架构
- **可插拔技能系统**：灵活扩展的工具调用机制

### 1.2 技术栈

| 层级 | 技术选型 |
|------|----------|
| 后端框架 | Spring Boot 3.x |
| 大模型 | DeepSeek API |
| 向量数据库 | Elasticsearch |
| 缓存 | Redis |
| 前端 | Vue 3 + Naive UI |
| 流式响应 | Reactor + WebSocket |

### 1.3 项目结构

```
src/main/java/com/yizhaoqi/smartpai/
├── mcp/                          # MCP 控制平面
│   ├── core/                     # 核心引擎
│   │   └── McpEngine.java        # MCP 主引擎
│   ├── context/                  # 上下文管理
│   │   └── McpContext.java       # 执行上下文
│   ├── skill/                    # 技能系统
│   │   ├── Skill.java            # 技能接口
│   │   ├── SkillResult.java      # 技能结果
│   │   └── impl/                 # 技能实现
│   ├── router/                   # 前置路由
│   │   ├── PreRouter.java        # 路由接口
│   │   └── impl/                 # 路由实现
│   ├── function/                 # 函数调用
│   │   └── FunctionRegistry.java # 函数注册中心
│   └── memory/                   # 记忆管理
│       └── MemoryManager.java    # 记忆管理器
├── service/                      # 业务服务
│   ├── agent/                    # Agent 服务
│   │   ├── AgentOrchestrator.java
│   │   ├── IntentAgent.java
│   │   ├── WorkAgent.java
│   │   └── CheckAgent.java
│   ├── HybridSearchService.java  # 混合检索
│   ├── QaMemoryService.java      # 问答记忆
│   └── ChatHandler.java          # 聊天处理
└── client/                       # 外部客户端
    ├── DeepSeekClient.java       # LLM 客户端
    └── EmbeddingClient.java      # 向量化客户端
```

---

## 2. 核心架构

### 2.1 整体架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                         用户请求                                 │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                      MCP 控制平面                                │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐             │
│  │ PreRouter   │→ │ MemoryMgr   │→ │ SkillSelect │             │
│  │ 前置路由    │  │ 记忆管理    │  │ 技能选择    │             │
│  └─────────────┘  └─────────────┘  └─────────────┘             │
│                                │                                 │
│                                ▼                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                    Skill Pipeline                        │   │
│  │  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐    │   │
│  │  │RagSkill │→ │QaSkill  │→ │CheckSkill│→ │SummarySkill│   │   │
│  │  └─────────┘  └─────────┘  └─────────┘  └─────────┘    │   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                      底层服务                                    │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐             │
│  │ DeepSeek    │  │ Elasticsearch│  │   Redis     │             │
│  │ LLM API     │  │ 向量检索     │  │   缓存      │             │
│  └─────────────┘  └─────────────┘  └─────────────┘             │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 请求处理流程

```
用户消息 → 前置路由检查 → 记忆检索 → 意图识别 → 技能选择 → 技能执行 → 质量检查 → 返回结果
              │              │           │           │           │           │
              ▼              ▼           ▼           ▼           ▼           ▼
         直接返回        缓存命中     意图分类    选择Skills   RAG/工具    通过/重试
         (如日期查询)    (相似问题)   (SEARCH等)  (RagSkill等)  执行       降级
```

---

## 3. MCP 控制平面

### 3.1 什么是 MCP

MCP（Model Context Protocol）是一个统一的模型上下文协议，负责：

1. **任务调度**：协调各个组件的执行顺序
2. **状态管理**：跟踪请求处理状态
3. **记忆管理**：管理对话历史和缓存
4. **技能协调**：选择和执行合适的技能

### 3.2 McpEngine 核心代码

```java
@Component
public class McpEngine {
    
    @Autowired(required = false)
    private List<Skill> skills = new ArrayList<>();

    @Autowired(required = false)
    private List<PreRouter> preRouters = new ArrayList<>();

    @PostConstruct
    public void init() {
        // 注册所有 Skill
        for (Skill skill : skills) {
            if (skill.isEnabled()) {
                functionRegistry.registerSkill(skill);
            }
        }
        // 排序前置路由器（按优先级）
        preRouters.sort(Comparator.comparingInt(PreRouter::getOrder));
    }

    public Flux<McpEvent> chat(String message, String sessionId, String userId) {
        // 创建上下文
        McpContext context = new McpContext(sessionId, userId, message);
        
        // 异步执行 MCP 流程
        taskExecutor.execute(() -> executeMcp(context, sink));
        
        return sink.asFlux();
    }
}
```

### 3.3 McpContext 上下文

`McpContext` 是整个执行过程中的上下文对象，携带所有必要信息：

```java
@Data
public class McpContext {
    // 会话信息
    private String sessionId;
    private String userId;
    private String userMessage;
    
    // 意图识别结果
    private String intent;
    private double confidence;
    private List<String> keywords;
    
    // 技能执行结果
    private List<String> calledSkills;
    private Map<String, Object> skillResults;
    private String finalReply;
    
    // 状态管理
    private McpState state;
    private boolean checkPassed;
    private int retryCount;
}
```

### 3.4 状态流转

```java
public enum McpState {
    INIT,               // 初始化
    MEMORY_RETRIEVAL,   // 记忆检索
    INTENT_RECOGNITION, // 意图识别
    SKILL_SELECTION,    // 技能选择
    RAG_RETRIEVAL,      // RAG 检索
    QUALITY_CHECK,      // 质量检查
    RETRY,              // 重试
    COMPLETED,          // 完成
    ERROR               // 错误
}
```

---

## 4. Skill 技能系统

### 4.1 Skill 接口定义

`Skill` 是所有技能的统一接口，采用**可插拔设计**：

```java
public interface Skill {
    
    /** 技能名称（唯一标识） */
    String getName();
    
    /** 技能描述 */
    String getDescription();
    
    /** 参数定义（JSON Schema 格式） */
    SkillParameterSchema getParameterSchema();
    
    /** 执行技能（同步） */
    SkillResult execute(McpContext context, SkillParams params);
    
    /** 执行技能（流式） */
    default Flux<SkillEvent> executeStream(McpContext context, SkillParams params) {
        return Flux.just(SkillEvent.result(execute(context, params)));
    }
    
    /** 是否支持流式执行 */
    default boolean supportsStreaming() {
        return false;
    }
    
    /** 技能优先级（数值越小优先级越高） */
    default int getPriority() {
        return 100;
    }
    
    /** 是否启用 */
    default boolean isEnabled() {
        return true;
    }
    
    /** 技能类别 */
    default SkillCategory getCategory() {
        return SkillCategory.TOOL;
    }
    
    /** 判断是否应该调用此技能 */
    default boolean shouldInvoke(McpContext context) {
        return true;
    }
}
```

### 4.2 技能类别

```java
public enum SkillCategory {
    /** 检索类：RAG、搜索 */
    RETRIEVAL,
    
    /** 生成类：摘要、问答 */
    GENERATION,
    
    /** 校验类：质检、安全检查 */
    VALIDATION,
    
    /** 工具类：计算、外部调用 */
    TOOL,
    
    /** 控制类：流程控制、路由 */
    CONTROL
}
```

### 4.3 内置技能

| 技能名称 | 类别 | 优先级 | 描述 |
|---------|------|--------|------|
| `rag_search` | RETRIEVAL | 10 | 本地知识库检索 + LLM 生成 |
| `qa_skill` | GENERATION | 20 | 问答生成 |
| `summary_skill` | GENERATION | 30 | 文档摘要 |
| `search_skill` | RETRIEVAL | 15 | 混合搜索 |
| `check_skill` | VALIDATION | 50 | 质量检查 |
| `WebSearchTool` | TOOL | 10 | 网络搜索 |

### 4.4 RagSkill 示例详解

```java
@Component
public class RagSkill implements Skill {

    @Autowired
    private HybridSearchService searchService;

    @Autowired
    private DeepSeekClient deepSeekClient;

    @Override
    public String getName() {
        return "rag_search";
    }

    @Override
    public String getDescription() {
        return "从本地知识库检索相关文档并生成回答";
    }

    @Override
    public SkillParameterSchema getParameterSchema() {
        return SkillParameterSchema.create()
            .property("query", PropertySchema.string("搜索查询语句").required(true))
            .property("topK", PropertySchema.integer("返回结果数量").defaultValue(5))
            .property("useLLM", PropertySchema.bool("是否使用LLM生成").defaultValue(true));
    }

    @Override
    public SkillCategory getCategory() {
        return SkillCategory.RETRIEVAL;
    }

    @Override
    public int getPriority() {
        return 10;  // 高优先级
    }

    @Override
    public boolean supportsStreaming() {
        return true;  // 支持流式输出
    }

    @Override
    public boolean shouldInvoke(McpContext context) {
        String intent = context.getIntent();
        return "SEARCH".equals(intent) || "QA".equals(intent) || 
               "COMPARE".equals(intent) || "SUMMARIZE".equals(intent);
    }

    @Override
    public SkillResult execute(McpContext context, SkillParams params) {
        String query = params.getString("query", context.getUserMessage());
        int topK = params.getInteger("topK", 5);
        
        // 1. 执行检索
        List<SearchResult> results = searchService.searchWithPermission(
            query, context.getUserId(), topK);
        
        if (results.isEmpty()) {
            return SkillResult.failure("未找到相关文档");
        }
        
        // 2. 构建 RAG 上下文
        String ragContext = buildContext(results);
        
        // 3. 调用 LLM 生成回答
        String answer = deepSeekClient.chat(query, ragContext);
        
        // 4. 返回结果
        return SkillResult.success(answer, buildSources(results));
    }

    @Override
    public Flux<SkillEvent> executeStream(McpContext context, SkillParams params) {
        // 流式执行实现
        return Flux.create(emitter -> {
            // 先发送检索结果
            emitter.next(SkillEvent.progress("检索完成，找到 5 条相关文档"));
            
            // 流式生成回答
            deepSeekClient.chatStream(query, ragContext, chunk -> {
                emitter.next(SkillEvent.chunk(chunk));
            });
            
            emitter.next(SkillEvent.complete());
            emitter.complete();
        });
    }
}
```

### 4.5 如何开发新技能

**步骤 1：创建技能类**

```java
@Component
public class CalculatorSkill implements Skill {
    
    @Override
    public String getName() {
        return "calculator";
    }
    
    @Override
    public String getDescription() {
        return "数学计算工具，支持加减乘除等运算";
    }
    
    @Override
    public SkillParameterSchema getParameterSchema() {
        return SkillParameterSchema.create()
            .property("expression", PropertySchema.string("数学表达式").required(true));
    }
    
    @Override
    public SkillCategory getCategory() {
        return SkillCategory.TOOL;
    }
    
    @Override
    public SkillResult execute(McpContext context, SkillParams params) {
        String expression = params.getString("expression");
        try {
            double result = evaluateExpression(expression);
            return SkillResult.success(String.valueOf(result));
        } catch (Exception e) {
            return SkillResult.failure("计算错误: " + e.getMessage());
        }
    }
}
```

**步骤 2：配置路由（可选）**

```java
@Component
public class CalculatorRouter implements PreRouter {
    
    @Override
    public boolean matches(McpContext context) {
        String msg = context.getUserMessage().toLowerCase();
        return msg.matches(".*[0-9]+\\s*[+\\-*/]\\s*[0-9]+.*");
    }
    
    @Override
    public RouteResult route(McpContext context) {
        String expression = extractExpression(context.getUserMessage());
        return RouteResult.callTool("calculator", 
            Map.of("expression", expression), "数学计算");
    }
}
```

---

## 5. Function Calling 机制

### 5.1 概念说明

Function Calling 是 LLM 的一种能力，允许模型调用预定义的函数。本项目将 Skill 注册为可调用的 Function。

### 5.2 FunctionRegistry 注册中心

```java
@Component
public class FunctionRegistry {

    /** 函数定义映射 */
    private final Map<String, FunctionDefinition> definitions = new ConcurrentHashMap<>();

    /** 函数与 Skill 的映射 */
    private final Map<String, Skill> skillMap = new ConcurrentHashMap<>();

    /**
     * 注册 Skill 为可调用的函数
     */
    public void registerSkill(Skill skill) {
        String name = skill.getName();
        
        // 创建函数定义
        FunctionDefinition definition = FunctionDefinition.create(
            name, skill.getDescription());
        
        // 设置参数 Schema
        SkillParameterSchema schema = skill.getParameterSchema();
        if (schema != null) {
            definition.setParameters(schema.toMap());
        }

        definitions.put(name, definition);
        skillMap.put(name, skill);
    }

    /**
     * 获取 OpenAI 格式的函数定义
     */
    public List<Map<String, Object>> getOpenAiFunctionDefinitions() {
        List<Map<String, Object>> functions = new ArrayList<>();
        for (FunctionDefinition def : definitions.values()) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", def.getName());
            function.put("description", def.getDescription());
            function.put("parameters", def.getParameters());
            functions.add(function);
        }
        return functions;
    }
}
```

### 5.3 LLM 调用流程

```
1. 用户发送消息
2. 系统将所有 Function 定义发送给 LLM
3. LLM 决定是否需要调用函数
4. 如果需要，LLM 返回函数名和参数
5. 系统执行函数并返回结果给 LLM
6. LLM 生成最终回答
```

### 5.4 示例：LLM Function Calling 请求

```json
{
  "model": "deepseek-chat",
  "messages": [
    {"role": "user", "content": "帮我搜索关于 AI 的最新新闻"}
  ],
  "functions": [
    {
      "name": "WebSearchTool",
      "description": "网络搜索工具，用于获取实时信息",
      "parameters": {
        "type": "object",
        "properties": {
          "query": {
            "type": "string",
            "description": "搜索关键词"
          },
          "limit": {
            "type": "integer",
            "description": "返回结果数量",
            "default": 5
          }
        },
        "required": ["query"]
      }
    }
  ]
}
```

**LLM 响应：**

```json
{
  "choices": [{
    "message": {
      "role": "assistant",
      "function_call": {
        "name": "WebSearchTool",
        "arguments": "{\"query\": \"AI 最新新闻\", \"limit\": 5}"
      }
    }
  }]
}
```

---

## 6. PreRouter 前置路由

### 6.1 设计理念

PreRouter 是在进入主流程之前的**快速路由判断**，用于：

1. **直接回答**：某些问题无需 RAG，可直接计算返回（如"今天是周几"）
2. **工具调用**：某些问题需要调用特定工具（如"搜索最新新闻"）
3. **流程跳过**：避免不必要的检索和 LLM 调用，提升响应速度

### 6.2 PreRouter 接口

```java
public interface PreRouter {

    /** 路由器名称 */
    String getName();

    /** 路由器描述 */
    String getDescription();

    /** 判断是否匹配此路由 */
    boolean matches(McpContext context);

    /** 执行路由逻辑 */
    RouteResult route(McpContext context);

    /** 优先级（数值越小优先级越高） */
    default int getOrder() {
        return 100;
    }

    /** 是否启用 */
    default boolean isEnabled() {
        return true;
    }
}
```

### 6.3 RouteResult 路由结果

```java
@Data
@Builder
public class RouteResult {

    public enum RouteType {
        /** 直接返回答案，跳过后续流程 */
        DIRECT_ANSWER,
        
        /** 调用指定工具后返回 */
        CALL_TOOL,
        
        /** 继续正常流程（RAG等） */
        CONTINUE,
        
        /** 需要更多信息 */
        NEED_MORE_INFO
    }

    private RouteType type;
    private String answer;           // 直接答案
    private String toolName;         // 工具名称
    private Map<String, Object> toolParams;  // 工具参数
    private String reason;           // 路由原因
    private double confidence;       // 置信度
}
```

### 6.4 DateTimeRouter 示例

```java
@Component
public class DateTimeRouter implements PreRouter {

    private static final List<Pattern> PATTERNS = Arrays.asList(
        Pattern.compile("(今天|今儿).*(周几|星期几|礼拜几)"),
        Pattern.compile("(现在|此时).*(几点|什么时间)"),
        Pattern.compile("距离?(春节|元旦|国庆).*(还有|还有多少).*(天|日)")
    );

    @Override
    public String getName() {
        return "DateTimeRouter";
    }

    @Override
    public int getOrder() {
        return 10;  // 高优先级
    }

    @Override
    public boolean matches(McpContext context) {
        String message = context.getUserMessage().toLowerCase().trim();
        for (Pattern pattern : PATTERNS) {
            if (pattern.matcher(message).find()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public RouteResult route(McpContext context) {
        String message = context.getUserMessage().toLowerCase().trim();
        String answer = generateDateTimeAnswer(message);
        return RouteResult.directAnswer(answer, "日期时间问题，直接计算返回");
    }

    private String generateDateTimeAnswer(String question) {
        LocalDateTime now = LocalDateTime.now();
        
        if (question.contains("周几") || question.contains("星期几")) {
            String[] weekDays = {"一", "二", "三", "四", "五", "六", "日"};
            return String.format("今天是星期%s", 
                weekDays[now.getDayOfWeek().getValue() - 1]);
        }
        
        if (question.contains("几点")) {
            return String.format("现在是%s", 
                now.format(DateTimeFormatter.ofPattern("HH时mm分")));
        }
        
        // ... 其他日期时间处理
        return "抱歉，无法识别日期时间问题";
    }
}
```

### 6.5 WebSearchRouter 示例

```java
@Component
public class WebSearchRouter implements PreRouter {

    private static final List<String> SEARCH_KEYWORDS = Arrays.asList(
        "搜索", "查找", "查询", "最新", "最近", "今天", "实时",
        "新闻", "消息", "天气", "股价", "汇率"
    );

    @Override
    public int getOrder() {
        return 20;  // 次高优先级
    }

    @Override
    public boolean matches(McpContext context) {
        String message = context.getUserMessage().toLowerCase();
        for (String keyword : SEARCH_KEYWORDS) {
            if (message.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public RouteResult route(McpContext context) {
        String searchQuery = extractSearchQuery(context.getUserMessage());
        return RouteResult.callTool("WebSearchTool", 
            Map.of("query", searchQuery, "limit", 5), 
            "需要实时网络信息，调用搜索工具");
    }
}
```

### 6.6 路由优先级

路由器按 `order` 值从小到大排序，先匹配到的先执行：

| 路由器 | Order | 说明 |
|--------|-------|------|
| DateTimeRouter | 10 | 日期时间问题 |
| WebSearchRouter | 20 | 网络搜索 |
| CalculatorRouter | 30 | 数学计算 |
| WeatherRouter | 40 | 天气查询 |

---

## 7. Agent 协作模式

### 7.1 Multi-Agent 架构

本项目实现了多智能体协作模式：

```
┌─────────────────────────────────────────────────────────────┐
│                    AgentOrchestrator                        │
│                      (协调器)                                │
└─────────────────────────────────────────────────────────────┘
         │                │                │
         ▼                ▼                ▼
┌─────────────┐  ┌─────────────┐  ┌─────────────┐
│ IntentAgent │  │  WorkAgent  │  │ CheckAgent  │
│  意图识别   │  │  任务执行   │  │  质量检查   │
└─────────────┘  └─────────────┘  └─────────────┘
```

### 7.2 AgentOrchestrator 协调器

```java
@Component
public class AgentOrchestrator {

    private final IntentAgent intentAgent;
    private final WorkAgent workAgent;
    private final CheckAgent checkAgent;

    public Flux<AgentEvent> orchestrate(String message, String sessionId, String userId) {
        // Step 0: 检查历史记忆
        Optional<QaMemoryEntry> cachedAnswer = qaMemoryService.findSimilarAnswer(message, userId, null);
        if (cachedAnswer.isPresent()) {
            return Flux.just(AgentEvent.finalReply(cachedAnswer.get().getAnswer()));
        }

        // Step 1: IntentAgent - 意图识别
        AgentIntent intent = intentAgent.analyze(message, sessionId);

        // Step 2: WorkAgent - 任务执行
        AgentResult work = workAgent.execute(intent, message, userId);

        // Step 3: CheckAgent - 质量检查
        boolean passed = checkAgent.quickCheck(work, intent);
        if (!passed) {
            passed = checkAgent.deepCheck(work, message);
        }

        if (passed) {
            return Flux.just(AgentEvent.finalReply(work.getReply()));
        } else {
            // 降级处理
            return Flux.just(AgentEvent.fallback("回复未通过质检"));
        }
    }
}
```

### 7.3 IntentAgent 意图识别

```java
@Component
public class IntentAgent {

    public AgentIntent analyze(String message, String sessionId) {
        // 使用 LLM 或规则进行意图识别
        String intent;
        List<String> keywords;
        double confidence;

        if (message.contains("搜索") || message.contains("查找")) {
            intent = "SEARCH";
            confidence = 0.9;
        } else if (message.contains("比较") || message.contains("对比")) {
            intent = "COMPARE";
            confidence = 0.85;
        } else if (message.contains("总结") || message.contains("摘要")) {
            intent = "SUMMARIZE";
            confidence = 0.85;
        } else {
            intent = "CHAT";
            confidence = 0.7;
        }

        // 提取关键词
        keywords = extractKeywords(message);

        return AgentIntent.builder()
            .intent(intent)
            .keywords(keywords)
            .confidence(confidence)
            .build();
    }
}
```

### 7.4 WorkAgent 任务执行

```java
@Component
public class WorkAgent {

    public AgentResult execute(AgentIntent intent, String message, String userId) {
        switch (intent.getIntent()) {
            case "SEARCH":
                return executeSearch(message, userId);
            case "COMPARE":
                return executeCompare(message, userId);
            case "SUMMARIZE":
                return executeSummary(message, userId);
            default:
                return executeChat(message, userId);
        }
    }

    private AgentResult executeSearch(String message, String userId) {
        // 1. 执行检索
        List<SearchResult> results = searchService.searchWithPermission(message, userId, 5);
        
        // 2. 构建 RAG 上下文
        String context = buildContext(results);
        
        // 3. 调用 LLM 生成
        String reply = deepSeekClient.chat(message, context);
        
        return AgentResult.builder()
            .reply(reply)
            .sources(results)
            .usedLLM(true)
            .build();
    }
}
```

### 7.5 CheckAgent 质量检查

```java
@Component
public class CheckAgent {

    public boolean quickCheck(AgentResult work, AgentIntent intent) {
        String reply = work.getReply();
        
        // 1. 长度检查
        if (reply == null || reply.length() < 10) {
            return false;
        }
        
        // 2. 相关性检查
        if (!isRelevant(reply, intent.getKeywords())) {
            return false;
        }
        
        return true;
    }

    public boolean deepCheck(AgentResult work, String originalQuestion) {
        // 使用 LLM 进行深度检查
        String checkPrompt = String.format(
            "请判断以下回答是否很好地回答了用户的问题：\n" +
            "问题：%s\n回答：%s\n请回答'是'或'否'",
            originalQuestion, work.getReply()
        );
        
        String result = deepSeekClient.chat(checkPrompt);
        return result.contains("是");
    }
}
```

---

## 8. RAG 检索增强生成

### 8.1 RAG 流程

```
用户问题 → 向量化 → 向量检索 → 混合排序 → 上下文构建 → LLM 生成 → 返回答案
              │          │           │            │           │
              ▼          ▼           ▼            ▼           ▼
          Embedding   ES检索      BM25+向量    Prompt组装   DeepSeek
```

### 8.2 HybridSearchService 混合检索

```java
@Service
public class HybridSearchService {

    @Autowired
    private ElasticsearchService esService;

    @Autowired
    private EmbeddingClient embeddingClient;

    /**
     * 带权限过滤的混合检索
     */
    public List<SearchResult> searchWithPermission(String query, String userId, int topK) {
        // 1. 获取用户权限标签
        Set<String> userTags = getUserTags(userId);
        
        // 2. 向量化查询
        float[] queryVector = embeddingClient.embed(query);
        
        // 3. 执行混合检索（向量 + BM25）
        List<SearchResult> results = esService.hybridSearch(
            query, queryVector, userTags, topK);
        
        return results;
    }

    /**
     * 混合检索算法
     */
    private List<SearchResult> hybridSearch(String query, float[] vector, 
                                            Set<String> tags, int topK) {
        // 向量检索结果
        List<SearchResult> vectorResults = esService.vectorSearch(vector, tags, topK * 2);
        
        // BM25 检索结果
        List<SearchResult> bm25Results = esService.textSearch(query, tags, topK * 2);
        
        // 融合排序（RRF）
        return reciprocalRankFusion(vectorResults, bm25Results, topK);
    }

    /**
     * 倒数排名融合（RRF）
     */
    private List<SearchResult> reciprocalRankFusion(
            List<SearchResult> list1, List<SearchResult> list2, int topK) {
        
        Map<String, Double> scores = new HashMap<>();
        double k = 60.0;
        
        for (int i = 0; i < list1.size(); i++) {
            String id = list1.get(i).getId();
            scores.merge(id, 1.0 / (k + i + 1), Double::sum);
        }
        
        for (int i = 0; i < list2.size(); i++) {
            String id = list2.get(i).getId();
            scores.merge(id, 1.0 / (k + i + 1), Double::sum);
        }
        
        // 按融合分数排序
        return scores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(topK)
            .map(e -> findResult(e.getKey(), list1, list2))
            .collect(Collectors.toList());
    }
}
```

### 8.3 Prompt 构建

```java
public class PromptBuilder {

    private static final String SYSTEM_PROMPT = """
        你是一个智能问答助手。请根据以下知识库内容回答用户问题。
        
        规则：
        1. 只使用提供的知识库内容回答
        2. 如果知识库中没有相关信息，请明确告知
        3. 回答要准确、简洁、专业
        4. 引用来源时标注文档名称
        """;

    public static String buildRagPrompt(String question, List<SearchResult> results) {
        StringBuilder context = new StringBuilder();
        context.append("【知识库内容】\n");
        
        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            context.append(String.format("[%d] %s\n来源：%s\n\n", 
                i + 1, r.getContent(), r.getFileName()));
        }
        
        return String.format("%s\n\n%s\n\n用户问题：%s", 
            SYSTEM_PROMPT, context, question);
    }
}
```

---

## 9. 记忆系统

### 9.1 记忆类型

| 类型 | 存储 | 用途 | 有效期 |
|------|------|------|--------|
| 会话记忆 | Redis | 多轮对话上下文 | 会话期间 |
| 问答记忆 | Redis | 相似问题缓存 | 7天 |
| 长期记忆 | ES | 用户偏好、历史 | 永久 |

### 9.2 QaMemoryService 问答记忆

```java
@Service
public class QaMemoryService {

    @Autowired
    private QaMemoryProperties properties;

    @Autowired
    private RedisRepository redisRepository;

    /**
     * 查找相似问题记忆
     */
    public Optional<QaMemoryEntry> findSimilarAnswer(String question, String userId, 
                                                     AgentIntent intent) {
        // 1. 精确匹配（哈希）
        String questionHash = hashQuestion(question);
        QaMemoryEntry exactMatch = redisRepository.findByQuestionHash(userId, questionHash);
        if (exactMatch != null) {
            return Optional.of(exactMatch);
        }

        // 2. 相似度匹配
        List<QaMemoryEntry> memories = redisRepository.getUserQaMemories(userId, properties.getKeyPrefix());
        
        return memories.stream()
            .filter(entry -> !entry.isExpired())
            .map(entry -> new MatchResult(entry, calculateSimilarity(question, entry, intent)))
            .filter(result -> result.similarity >= properties.getSimilarityThreshold())
            .max(Comparator.comparingDouble(r -> r.similarity))
            .map(result -> {
                result.entry.recordAccess();
                redisRepository.updateQaMemory(result.entry, properties.getKeyPrefix());
                return result.entry;
            });
    }

    /**
     * 计算相似度
     */
    private double calculateSimilarity(String question, QaMemoryEntry entry, 
                                       AgentIntent currentIntent) {
        // 文本相似度（Levenshtein 距离）
        double textSimilarity = calculateTextSimilarity(question, entry.getQuestion());
        
        // 关键词相似度
        double keywordSimilarity = calculateKeywordSimilarity(currentIntent, entry);
        
        // 综合相似度
        boolean hasKeywordInfo = currentIntent != null && 
                                 currentIntent.getKeywords() != null &&
                                 entry.getKeywords() != null;
        
        if (hasKeywordInfo) {
            return textSimilarity * 0.6 + keywordSimilarity * 0.4;
        } else {
            return textSimilarity;  // 没有关键词时完全依赖文本相似度
        }
    }

    /**
     * 保存问答记忆
     */
    public void saveMemory(String question, String userId, AgentIntent intent, 
                          AgentResult result) {
        String questionHash = hashQuestion(question);
        
        QaMemoryEntry entry = QaMemoryEntry.create(
            userId,
            question,
            questionHash,
            intent != null ? intent.getKeywords() : null,
            result.getReply(),
            result.getSources(),
            result.isUsedLLM(),
            properties.getExpireDays()
        );

        redisRepository.saveQaMemory(entry, properties.getKeyPrefix(), properties.getExpireDays());
    }
}
```

### 9.3 记忆配置

```yaml
qa-memory:
  enabled: true
  expire-days: 7
  similarity-threshold: 0.85
  key-prefix: "qa:memory"
  max-memory-per-user: 100
```

---

## 10. 实战示例

### 10.1 完整请求流程示例

**用户问题：** "这个文档讲了什么？"

```
1. 前置路由检查
   - DateTimeRouter: 不匹配（不是日期时间问题）
   - WebSearchRouter: 不匹配（不需要网络搜索）
   - 结果：继续正常流程

2. 记忆检索
   - 查找相似问题缓存
   - 结果：未命中

3. 意图识别
   - IntentAgent 分析
   - 结果：intent=QA, confidence=0.85

4. 技能选择
   - 根据 intent 选择 RagSkill
   - 结果：[RagSkill, QaSkill]

5. 技能执行
   - RagSkill: 检索相关文档
   - 找到 3 条相关内容
   - 构建 RAG 上下文
   - 调用 DeepSeek 生成回答

6. 质量检查
   - CheckAgent.quickCheck()
   - 结果：通过

7. 返回结果
   - 保存问答记忆
   - 返回最终答案
```

### 10.2 前置路由命中示例

**用户问题：** "今天是周几"

```
1. 前置路由检查
   - DateTimeRouter.matches(): true
   - DateTimeRouter.route(): 
     - type: DIRECT_ANSWER
     - answer: "今天是星期三（2026年4月8日）"
   
2. 直接返回答案
   - 跳过后续所有流程
   - 返回答案给用户
```

### 10.3 工具调用示例

**用户问题：** "搜索最新的 AI 新闻"

```
1. 前置路由检查
   - WebSearchRouter.matches(): true
   - WebSearchRouter.route():
     - type: CALL_TOOL
     - toolName: "WebSearchTool"
     - toolParams: {"query": "AI 新闻", "limit": 5}

2. 调用工具
   - McpEngine.callTool()
   - 执行 WebSearchSkill
   - 返回搜索结果

3. 返回结果
   - 返回搜索结果给用户
```

### 10.4 多轮对话示例

```
用户: 这个文档讲了什么？
系统: [检索文档] 这个文档主要讲述了...

用户: 能详细说说第一点吗？
系统: [使用会话记忆] 关于第一点，文档中提到...

用户: 还有其他相关内容吗？
系统: [使用会话记忆] 除了上述内容，文档还提到了...
```

---

## 附录

### A. 配置文件示例

```yaml
# application.yml
spring:
  data:
    redis:
      host: localhost
      port: 6379
    elasticsearch:
      cluster-nodes: localhost:9200

# AI 配置
ai:
  deepseek:
    api-key: ${DEEPSEEK_API_KEY}
    base-url: https://api.deepseek.com
  embedding:
    model: text-embedding-ada-002
  generation:
    temperature: 0.3
    max-tokens: 2000

# MCP 配置
mcp:
  router:
    enabled: true
    routers:
      DateTimeRouter: true
      WebSearchRouter: true

# 问答记忆配置
qa-memory:
  enabled: true
  expire-days: 7
  similarity-threshold: 0.85
  max-memory-per-user: 100
```

### B. 扩展开发指南

1. **添加新技能**：实现 `Skill` 接口，添加 `@Component` 注解
2. **添加新路由**：实现 `PreRouter` 接口，添加 `@Component` 注解
3. **添加新 Agent**：参考现有 Agent 实现，注入到 Orchestrator
4. **自定义检索**：扩展 `HybridSearchService`，实现自定义检索逻辑

### C. 常见问题

**Q: 如何调试 Skill 执行？**
A: 查看 `[MCP]` 开头的日志，了解执行流程。

**Q: 如何调整相似度阈值？**
A: 修改 `qa-memory.similarity-threshold` 配置。

**Q: 如何禁用某个路由器？**
A: 在配置中设置 `mcp.router.routers.<RouterName>: false`。

---

**文档版本：** v1.0  
**最后更新：** 2026-04-09
