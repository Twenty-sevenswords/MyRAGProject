// frontend/src/composables/useAgentWebSocket.ts
import { ref, onUnmounted } from 'vue'
import { buildWebSocketURL } from '@/utils/service'

export interface AgentEvent {
  type: 'start' | 'complete' | 'stream' | 'final' | 'error' | 'fallback'
  agent: string
  message: string
  data?: Record<string, any>
  sessionId: string
  timestamp: number
}

export function useAgentWebSocket() {
  const events = ref<AgentEvent[]>([])
  const currentStatus = ref<Record<string, {
    state: 'idle' | 'running' | 'success' | 'error'
    message: string
    startTime?: number
    endTime?: number
    data?: Record<string, any>
  }>>({})

  const isConnected = ref(false)
  let ws: WebSocket | null = null

  const connect = (sessionId: string, userId: string) => {
    // 清理旧连接
    disconnect()

    const wsUrl = buildWebSocketURL('/ws/agent-chat', { sessionId, userId })
    ws = new WebSocket(wsUrl)

    ws.onopen = () => {
      isConnected.value = true
      console.log('Agent WebSocket连接成功')
    }

    ws.onmessage = (event) => {
      try {
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
          const current = currentStatus.value[data.agent]
          if (current) {
            current.state = 'success'
            current.message = data.message
            current.endTime = data.timestamp
            current.data = data.data
          }
        } else if (data.type === 'error') {
          currentStatus.value[data.agent] = {
            state: 'error',
            message: data.message
          }
        }
      } catch (e) {
        console.error('解析Agent事件失败:', e)
      }
    }

    ws.onerror = (error) => {
      console.error('Agent WebSocket错误:', error)
      isConnected.value = false
    }

    ws.onclose = () => {
      isConnected.value = false
      console.log('Agent WebSocket关闭')
    }
  }

  const sendMessage = (message: string) => {
    if (ws?.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ message }))
    }
  }

  const disconnect = () => {
    if (ws) {
      ws.close()
      ws = null
    }
    isConnected.value = false
    events.value = []
    currentStatus.value = {}
  }

  // 组件卸载时自动断开
  onUnmounted(disconnect)

  return {
    events,
    currentStatus,
    isConnected,
    connect,
    sendMessage,
    disconnect
  }
}
