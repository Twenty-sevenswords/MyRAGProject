import { useWebSocket } from '@vueuse/core';
import { buildWebSocketURL } from '@/utils/service';

// 聊天模式类型
export type ChatMode = 'normal' | 'agent';

export const useChatStore = defineStore(SetupStoreId.Chat, () => {
  const conversationId = ref<string>('');
  const input = ref<Api.Chat.Input>({ message: '' });

  const list = ref<Api.Chat.Message[]>([]);

  const store = useAuthStore();

  // 聊天模式：normal=普通问答，agent=Agent协作
  const chatMode = ref<ChatMode>('normal');

  // Agent模式相关状态
  const agentEvents = ref<any[]>([]);
  const agentStatus = ref<
    Record<
      string,
      {
        state: 'idle' | 'running' | 'success' | 'error';
        message: string;
        startTime?: number;
        endTime?: number;
      }
    >
  >({});

  // 普通模式WebSocket
  const {
    status: wsStatus,
    data: wsData,
    send: wsSend,
    open: wsOpen,
    close: wsClose
  } = useWebSocket(`/proxy-ws/chat/${store.token}`, {
    autoReconnect: true
  });

  // Agent模式WebSocket连接（手动管理）
  let agentWs: WebSocket | null = null;
  const agentWsStatus = ref<'CONNECTING' | 'OPEN' | 'CLOSED'>('CLOSED');

  const resetAgentTrace = () => {
    agentEvents.value = [];
    agentStatus.value = {};
  };

  const resetConversationState = () => {
    input.value.message = '';
    conversationId.value = '';
    list.value = [];
    resetAgentTrace();
  };

  // 连接Agent WebSocket
  const connectAgentWs = (sessionId: string) => {
    if (agentWs) {
      agentWs.close();
    }
    // 使用动态构建的 WebSocket URL
    const wsUrl = buildWebSocketURL('/ws/agent-chat', {
      sessionId,
      userId: store.userInfo?.username || 'anonymous'
    });
    console.log('正在连接 Agent WebSocket:', wsUrl);
    agentWsStatus.value = 'CONNECTING';
    agentWs = new WebSocket(wsUrl);

    agentWs.onopen = () => {
      agentWsStatus.value = 'OPEN';
      console.log('Agent WebSocket连接成功');
    };

    agentWs.onmessage = event => {
      try {
        const data = JSON.parse(event.data);
        console.log('收到Agent事件:', data.type, data.agent, data.message?.substring(0, 50));
        agentEvents.value.push(data);

        // 更新Agent状态
        // 注意：后端事件类型是小写的 start, complete, final, error, fallback, stream
        if (data.type === 'start') {
          agentStatus.value[data.agent] = {
            state: 'running',
            message: data.message,
            startTime: data.timestamp
          };
        } else if (data.type === 'complete') {
          const agent = agentStatus.value[data.agent];
          if (agent) {
            agent.state = 'success';
            agent.message = data.message;
            agent.endTime = data.timestamp;
          }
        } else if (data.type === 'stream') {
          // 流式数据块 - 追加到当前助手消息
          console.log('收到流式数据块:', data.message?.substring(0, 50));
          const assistant = list.value[list.value.length - 1];
          if (assistant?.role === 'assistant') {
            // 如果是第一条流式数据，清空初始提示
            if (!assistant.content || assistant.content.includes('🔄 Agent协作处理中')) {
              assistant.content = data.message;
            } else {
              assistant.content += data.message;
            }
            assistant.status = 'loading';
          }
        } else if (data.type === 'final') {
          // 最终回复
          console.log('收到最终回复:', data.message?.substring(0, 100));
          const assistant = list.value[list.value.length - 1];
          if (assistant?.role === 'assistant') {
            assistant.content = data.message;
            assistant.status = 'finished';
          }
        } else if (data.type === 'error') {
          agentStatus.value[data.agent] = {
            state: 'error',
            message: data.message
          };
          const assistant = list.value[list.value.length - 1];
          if (assistant?.role === 'assistant') {
            assistant.status = 'error';
            assistant.content = `错误: ${data.message}`;
          }
        } else if (data.type === 'fallback') {
          // 降级处理
          console.log('降级事件:', data.message);
        }
      } catch (e) {
        console.error('解析Agent事件失败:', e);
      }
    };

    agentWs.onerror = error => {
      console.error('Agent WebSocket错误:', error);
      agentWsStatus.value = 'CLOSED';
    };

    agentWs.onclose = () => {
      agentWsStatus.value = 'CLOSED';
      console.log('Agent WebSocket关闭');
    };
  };

  // 发送消息到Agent WebSocket
  const agentWsSend = (message: string) => {
    if (agentWs?.readyState === WebSocket.OPEN) {
      agentWs.send(JSON.stringify({ message }));
    }
  };

  // 断开Agent WebSocket
  const disconnectAgentWs = () => {
    if (agentWs) {
      agentWs.close();
      agentWs = null;
    }
    agentWsStatus.value = 'CLOSED';
    resetAgentTrace();
  };

  // 切换聊天模式
  const setChatMode = (mode: ChatMode, sessionId?: string) => {
    if (mode === 'agent' && sessionId) {
      connectAgentWs(sessionId);
    } else if (mode === 'normal') {
      disconnectAgentWs();
    }
    chatMode.value = mode;
  };

  // 根据当前模式发送消息
  const sendMessage = (message: string) => {
    const normalizedMessage = message.trim();
    if (!normalizedMessage) return;

    if (chatMode.value === 'agent') {
      resetAgentTrace();
      agentWsSend(normalizedMessage);
    } else {
      wsSend(normalizedMessage);
    }
  };

  const scrollToBottom = ref<null | (() => void)>(null);

  return {
    input,
    conversationId,
    list,
    wsStatus,
    wsData,
    wsSend,
    wsOpen,
    wsClose,
    scrollToBottom,
    // Agent模式相关
    chatMode,
    agentEvents,
    agentStatus,
    agentWsStatus,
    setChatMode,
    sendMessage,
    connectAgentWs,
    disconnectAgentWs,
    resetAgentTrace,
    resetConversationState
  };
});
