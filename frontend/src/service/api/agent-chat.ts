// frontend/src/service/api/agent-chat.ts
import { useWebSocket } from '@vueuse/core'
import { ref, watch } from 'vue'
import { buildWebSocketURL } from '@/utils/service'

// Agent事件类型
export interface AgentEvent {
  type: 'start' | 'complete' | 'stream' | 'final' | 'error' | 'fallback'
  agent: string
  message: string
  data?: Record<string, any>
  sessionId: string
  timestamp: number
}

// WebSocket连接管理
export function useAgentChat() {
  const events = ref<AgentEvent[]>([])
  const currentStatus = ref<Record<string, {
    state: 'idle' | 'running' | 'success' | 'error'
    message: string
    startTime?: number
    endTime?: number
  }>>({})

  let ws: WebSocket | null = null

  const connect = (sessionId: string, userId: string) => {
    const wsUrl = buildWebSocketURL('/ws/agent-chat', { sessionId, userId })

    ws = new WebSocket(wsUrl)

    ws.onmessage = (event) => {
      const data: AgentEvent = JSON.parse(event.data)
      events.value.push(data)

      // 更新Agent状态
      if (data.type === 'start') {
        currentStatus.value[data.agent] = {
          state: 'running',
          message: data.message,
          startTime: data.timestamp
        }
      } else if (data.type === 'complete') {
        const agent = currentStatus.value[data.agent]
        if (agent) {
          agent.state = 'success'
          agent.message = data.message
          agent.endTime = data.timestamp
        }
      } else if (data.type === 'error') {
        currentStatus.value[data.agent] = {
          state: 'error',
          message: data.message
        }
      }
    }

    ws.onerror = (error) => {
      console.error('Agent WebSocket错误:', error)
    }

    ws.onclose = () => {
      console.log('Agent WebSocket关闭')
    }
  }

  const sendMessage = (message: string) => {
    if (ws?.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ message }))
    }
  }

  const disconnect = () => {
    ws?.close()
    ws = null
    events.value = []
    currentStatus.value = {}
  }

  return {
    events,
    currentStatus,
    connect,
    sendMessage,
    disconnect
  }
}

// HTTP API（获取历史会话等）
export const agentApi = {
  // 获取Agent调试信息
  getDebugInfo: (sessionId: string) =>
    fetch(`/api/v1/agent/debug?sessionId=${sessionId}`).then(r => r.json()),

  // 获取会话记忆
  getMemory: (sessionId: string) =>
    fetch(`/api/v1/agent/memory?sessionId=${sessionId}`).then(r => r.json())
}
