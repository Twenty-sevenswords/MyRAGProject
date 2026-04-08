<template>
  <n-drawer v-model:show="visible" :width="400" placement="right">
    <n-drawer-content title="🤖 Agent协作监控" :native-scrollbar="false">

      <!-- Agent状态卡片 -->
      <n-space vertical class="agent-cards">
        <n-card
          v-for="(status, agentName) in agentStatus"
          :key="agentName"
          :title="getAgentDisplayName(agentName)"
          size="small"
          :class="['agent-card', status.state]"
        >
          <n-tag :type="getStateType(status.state)" size="small">
            {{ getStateLabel(status.state) }}
          </n-tag>

          <p class="agent-message">{{ status.message }}</p>

          <n-text v-if="status.startTime && status.endTime" depth="3" class="time-cost">
            耗时: {{ status.endTime - status.startTime }}ms
          </n-text>
        </n-card>
      </n-space>

      <n-divider />

      <!-- 事件时间线 -->
      <n-timeline>
        <n-timeline-item
          v-for="event in sortedEvents"
          :key="event.timestamp"
          :type="getEventType(event)"
          :title="`${event.agent} - ${event.type}`"
          :content="event.message"
          :time="formatTime(event.timestamp)"
        >
          <!-- 展开详细数据 -->
          <n-collapse v-if="event.data" arrow-placement="right">
            <n-collapse-item title="详细数据">
              <n-code :code="JSON.stringify(event.data, null, 2)" language="json" />
            </n-collapse-item>
          </n-collapse>
        </n-timeline-item>
      </n-timeline>

      <!-- 空状态 -->
      <n-empty v-if="agentEvents.length === 0" description="等待Agent协作开始..." />
    </n-drawer-content>
  </n-drawer>

  <!-- 触发按钮 -->
  <n-button
    class="debug-toggle"
    circle
    :type="chatMode === 'agent' ? 'success' : 'primary'"
    @click="visible = !visible"
  >
    <template #icon>
      <n-icon><svg-icon icon="carbon:debug" /></n-icon>
    </template>
  </n-button>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const chatStore = useChatStore();
const { agentEvents, agentStatus, chatMode } = storeToRefs(chatStore);

const visible = ref(false)

// 排序后的事件（最新的在前）
const sortedEvents = computed(() =>
  [...agentEvents.value].reverse()
)

// 辅助函数
const getAgentDisplayName = (name: string) => {
  const map: Record<string, string> = {
    'IntentAgent': '🎯 意图识别',
    'WorkAgent': '🔧 执行处理',
    'CheckAgent': '🔍 质检复核',
    'system': '⚙️ 系统'
  }
  return map[name] || name
}

const getStateType = (state: string) => {
  const map: Record<string, any> = {
    'idle': 'default',
    'running': 'warning',
    'success': 'success',
    'error': 'error'
  }
  return map[state] || 'default'
}

const getStateLabel = (state: string) => {
  const map: Record<string, string> = {
    'idle': '等待中',
    'running': '运行中',
    'success': '完成',
    'error': '失败'
  }
  return map[state] || state
}

const getEventType = (event: any) => {
  // 注意：后端事件类型是小写的 start, complete, stream, final, error, fallback
  const map: Record<string, any> = {
    'start': 'info',
    'complete': 'success',
    'stream': 'default',
    'final': 'success',
    'error': 'error',
    'fallback': 'warning'
  }
  return map[event.type] || 'default'
}

const formatTime = (timestamp: number) => {
  return new Date(timestamp).toLocaleTimeString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    fractionalSecondDigits: 3
  })
}
</script>

<style scoped>
.agent-cards {
  margin-bottom: 16px;
}

.agent-card {
  transition: all 0.3s;
}

.agent-card.running {
  border-left: 4px solid #f0a020;
  animation: pulse 2s infinite;
}

.agent-card.success {
  border-left: 4px solid #18a058;
}

.agent-card.error {
  border-left: 4px solid #d03050;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.7; }
}

.agent-message {
  margin: 8px 0;
  font-size: 12px;
  color: #666;
}

.time-cost {
  font-size: 11px;
}

.debug-toggle {
  position: fixed;
  right: 20px;
  bottom: 100px;
  z-index: 100;
}
</style>
