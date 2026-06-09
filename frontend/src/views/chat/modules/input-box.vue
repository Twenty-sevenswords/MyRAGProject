<script setup lang="ts">
import { fetchClearChatMemory } from '@/service/api';
import type { ChatMode } from '@/store/modules/chat';

const chatStore = useChatStore();
const { input, list, wsStatus, wsData, chatMode, agentWsStatus, agentEvents } = storeToRefs(chatStore);

// 当前会话ID（用于Agent模式）
const createSessionId = () => `sess_${Date.now()}_${Math.random().toString(36).slice(2, 11)}`;
const currentSessionId = ref(createSessionId());
const newConversationLoading = ref(false);

const latestMessage = computed(() => {
  return list.value[list.value.length - 1] ?? {};
});

const isSending = computed(() => {
  return (
    latestMessage.value?.role === 'assistant' && ['loading', 'pending'].includes(latestMessage.value?.status || '')
  );
});

// 当前WebSocket状态（根据模式显示不同状态）
const currentWsStatus = computed(() => {
  return chatMode.value === 'agent' ? agentWsStatus.value : wsStatus.value;
});

const normalizedMessage = computed(() => input.value.message.trim());

const sendDisabled = computed(() => {
  if (isSending.value) return false;

  return !normalizedMessage.value || ['CLOSED', 'CONNECTING'].includes(currentWsStatus.value);
});

// 普通模式WebSocket消息处理
watch(wsData, val => {
  if (chatMode.value !== 'normal') return;

  const data = JSON.parse(val);
  const assistant = list.value[list.value.length - 1];

  if (data.type === 'completion' && data.status === 'finished' && assistant.status !== 'error')
    assistant.status = 'finished';
  if (data.error) assistant.status = 'error';
  else if (data.chunk) {
    assistant.status = 'loading';
    assistant.content += data.chunk;
  }
});

// Agent模式消息处理（已在store中处理）
watch(
  agentEvents,
  events => {
    if (chatMode.value !== 'agent' || events.length === 0) return;

    const lastEvent = events[events.length - 1];
    const assistant = list.value[list.value.length - 1];

    // 注意：后端事件类型是小写的 start, complete, stream, final, error
    if (lastEvent.type === 'start') {
      if (assistant?.role === 'assistant') {
        assistant.status = 'loading';
      }
    } else if (lastEvent.type === 'stream') {
      // 流式数据已经在 store 中处理，这里不需要重复处理
    }
  },
  { deep: true }
);

// 切换聊天模式
const handleModeChange = (mode: ChatMode) => {
  if (mode === 'agent') {
    // 生成新的会话ID并连接Agent WebSocket
    currentSessionId.value = createSessionId();
    chatStore.setChatMode('agent', currentSessionId.value);
  } else {
    chatStore.setChatMode('normal');
  }
};

const handleNewConversation = async () => {
  if (isSending.value) {
    window.$message?.warning('正在回复中，请先停止当前回复');
    return;
  }

  newConversationLoading.value = true;
  try {
    const { error } = await fetchClearChatMemory(currentSessionId.value);
    if (error) {
      window.$message?.error('新对话创建失败，请稍后重试');
      return;
    }

    chatStore.resetConversationState();
    currentSessionId.value = createSessionId();
    if (chatMode.value === 'agent') {
      chatStore.setChatMode('agent', currentSessionId.value);
    }
    window.$message?.success('已开启新对话');
  } finally {
    newConversationLoading.value = false;
  }
};

const handleSend = async () => {
  // 判断是否正在发送, 如果发送中，则停止ai继续响应
  if (isSending.value) {
    if (chatMode.value === 'normal') {
      const { error, data } = await request<Api.Chat.Token>({ url: 'chat/websocket-token', baseURL: 'proxy-api' });
      if (error) return;
      chatStore.wsSend(JSON.stringify({ type: 'stop', _internal_cmd_token: data.cmdToken }));
    }

    list.value[list.value.length - 1].status = 'finished';
    if (!latestMessage.value.content) list.value.pop();
    return;
  }

  const message = normalizedMessage.value;
  if (!message) return;

  list.value.push({
    content: message,
    role: 'user'
  });

  list.value.push({
    content: chatMode.value === 'agent' ? '🔄 Agent协作处理中...' : '',
    role: 'assistant',
    status: 'pending'
  });

  // 使用统一的sendMessage方法
  chatStore.sendMessage(message);

  input.value.message = '';
};

const inputRef = ref();
// 手动插入换行符（确保所有浏览器兼容）
const insertNewline = () => {
  const textarea = inputRef.value;
  const start = textarea.selectionStart;
  const end = textarea.selectionEnd;

  // 在光标位置插入换行符
  input.value.message = `${input.value.message.substring(0, start)}\n${input.value.message.substring(end)}`;

  // 更新光标位置（在插入的换行符之后）
  nextTick(() => {
    textarea.selectionStart = start + 1;
    textarea.selectionEnd = start + 1;
    textarea.focus(); // 确保保持焦点
  });
};

// ctrl + enter 换行
// enter 发送
const handShortcut = (e: KeyboardEvent) => {
  if (e.key === 'Enter') {
    e.preventDefault();

    if (!e.shiftKey && !e.ctrlKey) {
      handleSend();
    } else insertNewline();
  }
};
</script>

<template>
  <div class="chat-input-card relative w-full b-1 b-#1c1c1c20 bg-#fff p-4 card-wrapper dark:bg-#1c1c1c">
    <!-- 模式切换栏 -->
    <div class="chat-input-header mb-2 flex items-center justify-between">
      <NRadioGroup v-model:value="chatMode" size="small" class="chat-mode-group" @update:value="handleModeChange">
        <NRadioButton value="normal">💬 普通问答</NRadioButton>
        <NRadioButton value="agent">🤖 Agent协作</NRadioButton>
      </NRadioGroup>

      <!-- Agent模式提示 -->
      <NText v-if="chatMode === 'agent'" depth="3" class="session-hint text-12px">
        会话: {{ currentSessionId.slice(0, 12) }}...
      </NText>
    </div>

    <textarea
      ref="inputRef"
      v-model.trim="input.message"
      :placeholder="chatMode === 'agent' ? '给 Agent协作助手 发送消息' : '给 杨志博毕设 发送消息'"
      class="min-h-10 w-full cursor-text resize-none b-none bg-transparent color-#333 caret-[rgb(var(--primary-color))] outline-none dark:color-#f1f1f1"
      @keydown="handShortcut"
    />
    <div class="chat-input-footer flex items-center justify-between pt-2">
      <div class="chat-input-meta flex items-center gap-3 text-18px color-gray-500">
        <NButton
          size="small"
          tertiary
          type="primary"
          :loading="newConversationLoading"
          :disabled="isSending"
          @click="handleNewConversation"
        >
          <template #icon>
            <icon-material-symbols:add-comment-outline-rounded />
          </template>
          新对话
        </NButton>
        <NText class="text-14px">连接状态：</NText>
        <icon-eos-icons:loading v-if="currentWsStatus === 'CONNECTING'" class="color-yellow" />
        <icon-fluent:plug-connected-checkmark-20-filled v-else-if="currentWsStatus === 'OPEN'" class="color-green" />
        <icon-tabler:plug-connected-x v-else class="color-red" />
        <!-- 模式标签 -->
        <NTag v-if="chatMode === 'agent'" type="info" size="small" class="ml-2">Agent模式</NTag>
      </div>
      <NButton :disabled="sendDisabled" strong circle type="primary" @click="handleSend">
        <template #icon>
          <icon-material-symbols:stop-rounded v-if="isSending" />
          <icon-guidance:send v-else />
        </template>
      </NButton>
    </div>
  </div>
</template>

<style scoped>
.chat-input-card,
.chat-input-header,
.chat-input-footer,
.chat-input-meta {
  min-width: 0;
}

.chat-input-meta {
  flex-wrap: wrap;
}

@media (max-width: 639px) {
  .chat-input-card {
    padding: 12px;
    border-radius: 12px;
  }

  .chat-input-header {
    align-items: flex-start;
    flex-wrap: wrap;
    gap: 8px;
  }

  .chat-mode-group {
    max-width: 100%;
    overflow-x: auto;
  }

  .session-hint {
    display: none;
  }

  .chat-input-footer {
    align-items: flex-end;
    gap: 8px;
  }

  .chat-input-meta {
    flex: 1;
    gap: 8px;
    font-size: 16px;
  }

  :deep(.chat-mode-group .n-radio-button) {
    padding-inline: 10px;
  }
}
</style>
