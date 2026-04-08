# Agent 模式问题排查清单

## ✅ 已修复的问题

### 1. WebSocket 连接路径错误
**问题**: 前端连接到 `/ws/agent-chat` (LangGraph)，应该连接到 `/ws/mcp` (MCP AI Agent)

**修复文件**: `frontend/src/store/modules/chat/index.ts`
```typescript
// 修改前
const wsUrl = `ws://localhost:8081/ws/agent-chat?sessionId=${sessionId}&userId=...`;

// 修改后
const wsUrl = `ws://localhost:8081/ws/mcp?sessionId=${sessionId}&userId=...`;
```

### 2. 增强日志输出
**修复文件**: 
- `src/main/java/com/yizhaoqi/smartpai/mcp/memory/MemoryManager.java`
- `src/main/java/com/yizhaoqi/smartpai/mcp/core/McpEngine.java`

添加了详细的阶段日志，可以清楚看到卡在哪个环节。

---

## 🔍 排查步骤

### 步骤 1: 重启应用
```bash
# 停止当前应用
# 重新启动
mvn spring-boot:run
```

### 步骤 2: 检查后端日志
启动后应该看到：
```
[MCP] 注册技能: rag_search - ...
[MCP] 注册技能: web_search - ...
[MCP] 控制平面初始化完成，已注册 X 个技能
```

### 步骤 3: 前端切换到 Agent 模式
1. 打开浏览器开发者工具 (F12)
2. 切换到 Console 标签
3. 在聊天界面点击 "🤖 Agent协作" 按钮
4. 观察控制台输出：
   ```
   正在连接 Agent WebSocket: ws://localhost:8081/ws/mcp?sessionId=...&userId=...
   Agent WebSocket连接成功
   ```

### 步骤 4: 发送测试消息
输入一个问题，例如："你好"

观察后端日志应该依次出现：
```
[MCP WebSocket] 连接建立: sessionId=..., userId=...
[MCP WebSocket] 收到消息: sessionId=..., payload={"message":"你好"}

############################## MCP 控制平面启动 ##############################
[MCP] 会话ID: ..., 用户ID: ...
[MCP] 用户消息: 你好

[Memory] 开始加载会话记忆, sessionId=...
[Memory] 正在加载对话历史...
[Memory] 加载对话历史: X 条
[Memory] 正在加载上下文变量...
[Memory] 加载上下文变量: X 个
[Memory] ✅ 会话记忆加载完成

[MCP] ========== 阶段1: 记忆检索 ==========
[MCP] ❌ 未命中历史记忆，继续后续流程

[MCP] 选择的技能: [rag_search, ...]
[RagSkill] 检索结果: X 条
...
[MCP] ############################## MCP 执行完成 (耗时: XXXms) ##############################
```

### 步骤 5: 如果仍然卡住
检查日志卡在哪个阶段：

#### 情况 A: 卡在 "[Memory] 正在加载对话历史..."
**可能原因**: Redis 连接问题或数据序列化问题

**解决方法**:
1. 检查 Redis 是否正常运行
   ```bash
   redis-cli ping
   # 应该返回 PONG
   ```

2. 清除该会话的 Redis 数据
   ```bash
   redis-cli
   > KEYS mcp:history:*
   > DEL mcp:history:your_session_id
   ```

#### 情况 B: 卡在 "[MCP] ========== 阶段1: 记忆检索 =========="
**可能原因**: QaMemoryService 查询过慢

**解决方法**:
1. 检查用户历史记忆数量
   ```bash
   redis-cli
   > KEYS mcp:qa_cache:user_id:*
   > DBSIZE
   ```

2. 如果太多，清理缓存
   ```bash
   > FLUSHDB  # 谨慎使用！会清空所有数据
   ```

#### 情况 C: 卡在 "[RagSkill] 检索知识库..."
**可能原因**: Elasticsearch 连接问题或查询超时

**解决方法**:
1. 检查 ES 是否正常运行
   ```bash
   curl http://localhost:9200/_cluster/health?pretty
   ```

2. 检查索引是否存在
   ```bash
   curl http://localhost:9200/knowledge_base/_count
   ```

3. 运行诊断工具
   ```bash
   # 在 application.yml 中添加
   spring.main.lazy-initialization=false
   
   # 然后启动时传入参数
   java -jar app.jar diagnose-es
   ```

---

## 📊 常见问题速查

| 现象 | 可能原因 | 解决方法 |
|------|---------|---------|
| WebSocket 连接失败 | 路径错误或端口不对 | 检查 URL 是否为 `ws://localhost:8081/ws/mcp` |
| 卡在"检索历史记忆" | Redis 问题或数据过多 | 检查 Redis 连接，清理旧数据 |
| 提示"未找到相关文档" | ES 索引为空或权限问题 | 上传文档，检查用户权限 |
| Agent 无响应 | 技能未正确注册 | 检查启动日志中的技能注册信息 |
| 流式输出不显示 | 前端事件类型不匹配 | 已修复，确保事件类型为小写 |

---

## 🎯 验证清单

- [ ] 后端启动无错误
- [ ] MCP 技能注册成功（至少包含 rag_search）
- [ ] 前端能成功连接到 `ws://localhost:8081/ws/mcp`
- [ ] 发送消息后能看到完整的 MCP 执行日志
- [ ] 能看到 RAG 检索结果
- [ ] 能收到流式回复并最终显示完整答案
- [ ] Agent 调试面板显示各个 Agent 的状态变化

---

## 📝 下一步

如果按照以上步骤仍然无法解决，请提供：
1. 后端完整日志（从启动到卡住的整个过程）
2. 前端控制台输出
3. Network 标签中的 WebSocket 连接状态
4. Redis 和 Elasticsearch 的运行状态
