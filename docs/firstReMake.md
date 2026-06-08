# firstReMake

## 1. 文档说明
- 文档名称：`firstReMake`
- 文件路径：`docs/firstReMake.md`
- 记录时间：2026-05-28
- 目的：记录本轮依据 `docs/REFACTORING_PLAN.md` 和 `docs/ARCHITECTURE.md` 执行的后端重构结果。

## 2. 本轮重构目标
- 移除与 LangGraph 重复的 Service Agent 编排层。
- 统一 RAG 执行路径，降低节点内重复实现。
- 提取公共工具，减少 JSON 解析和 WebSocket 处理重复代码。
- 推进包结构规范化（`config` 分包、`entity` 迁移为 `dto`）。
- 保持行为兼容并确保编译通过。

## 3. 已完成的重构项

### 3.1 删除冗余 Agent 服务层
已删除以下冗余文件：
- `src/main/java/com/yizhaoqi/smartpai/config/AgentConfig.java`
- `src/main/java/com/yizhaoqi/smartpai/service/MultiAgentChatService.java`
- `src/main/java/com/yizhaoqi/smartpai/service/agent/IntentAgent.java`
- `src/main/java/com/yizhaoqi/smartpai/service/agent/WorkAgent.java`
- `src/main/java/com/yizhaoqi/smartpai/service/agent/CheckAgent.java`
- `src/main/java/com/yizhaoqi/smartpai/service/agent/AgentOrchestrator.java`

### 3.2 ActionNode 委托 RagService
- 将 `ActionNode` 的核心执行改为委托 `LangChain4jRagService`。
- `ActionNode` 保留流程编排职责（简单检索分支 / RAG分支），去除内部重复构建逻辑。

关键文件：
- `src/main/java/com/yizhaoqi/smartpai/langgraph/node/ActionNode.java`
- `src/main/java/com/yizhaoqi/smartpai/service/LangChain4jRagService.java`

### 3.3 JSON 提取公共化
新增：
- `src/main/java/com/yizhaoqi/smartpai/util/JsonExtractor.java`

已接入位置（示例）：
- `langgraph/node/RouterNode`
- `langgraph/node/QueryAnalysisNode`
- `langgraph/node/HallucinationCheckNode`
- `langgraph/node/CheckNode`
- `service/LangChain4jAgentService`

### 3.4 WebSocket 基类提取
新增：
- `src/main/java/com/yizhaoqi/smartpai/handler/BaseWebSocketHandler.java`

改造：
- `ChatWebSocketHandler` 继承基类并复用安全发送/错误处理能力。
- `MultiAgentWebSocketHandler` 继承基类并复用会话管理辅助方法。

关键文件：
- `src/main/java/com/yizhaoqi/smartpai/handler/ChatWebSocketHandler.java`
- `src/main/java/com/yizhaoqi/smartpai/handler/MultiAgentWebSocketHandler.java`

### 3.5 LangChain4jAgentService 职责拆分
新增服务：
- `src/main/java/com/yizhaoqi/smartpai/service/tool/ToolExecutionService.java`
- `src/main/java/com/yizhaoqi/smartpai/service/intent/IntentRecognitionService.java`
- `src/main/java/com/yizhaoqi/smartpai/service/quality/QualityCheckService.java`

调整：
- `LangChain4jAgentService` 变为兼容性门面，保留原有对外方法签名并委托新服务执行。

关键文件：
- `src/main/java/com/yizhaoqi/smartpai/service/LangChain4jAgentService.java`

### 3.6 config 包结构清理
已完成分包迁移：
- `config/properties`：`AiProperties`, `QaMemoryProperties`, `RouterProperties`
- `config/filter`：`JwtAuthenticationFilter`, `OrgTagAuthorizationFilter`
- `config/initializer`：`AdminUserInitializer`, `EsIndexInitializer`
- `config/interceptor`：`LoggingInterceptor`

并同步修复引用：
- `SecurityConfig`
- `WebConfig`
- `DateTimeRouter`
- `WebSearchRouter`
- `QaMemoryService`
- `LangGraph` 相关节点

### 3.7 entity -> dto 迁移
已执行：
- `src/main/java/com/yizhaoqi/smartpai/entity` -> `src/main/java/com/yizhaoqi/smartpai/dto`

并完成全量 import/包名替换，确保调用链可编译。

## 4. 影响范围
- 后端核心：`langgraph`, `service`, `handler`, `config`, `mcp`, `repository`, `controller`
- 迁移类目：DTO/配置类/Agent执行链
- 前端逻辑未在本轮作为重构主目标（工作区已有其他前端改动，未在本次重构文档中展开）

## 5. 验证结果
已执行：
```bash
mvn -q -DskipTests compile
```
结果：通过。

## 6. 注意事项
- 本轮以“结构重构 + 编译通过”为主，未覆盖全量业务回归测试。
- 建议后续补充以下回归验证：
  - WebSocket 聊天主链路（`/chat/{token}`）
  - 多 Agent 会话链路（`/ws/agent-chat`）
  - RAG 检索与回答链路
  - MCP 路由与技能执行链路

## 7. 后续建议（第二阶段）
- 继续推进 `service` 领域分包（chat/rag/search/document/tool/intent/quality）。
- 对关键模块补充单元测试与集成测试。
- 评估 `LangChain4j` 进一步标准化（`AiServices`、`ContentRetriever`、`@Tool`）。
