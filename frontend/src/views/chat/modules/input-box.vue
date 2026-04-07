<script setup lang="ts">
import type { ChatMode } from '@/store/modules/chat';

const chatStore = useChatStore();
const { input, list, wsStatus, wsData, chatMode, agentWsStatus, agentEvents } = storeToRefs(chatStore);

// 当前会话ID（用于Agent模式）
const currentSessionId = ref('sess_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9));

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

const sendable = computed(
  () => (!input.value.message && !isSending) || ['CLOSED', 'CONNECTING'].includes(currentWsStatus.value)
);

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
watch(agentEvents, (events) => {
  if (chatMode.value !== 'agent' || events.length === 0) return;
  
  const lastEvent = events[events.length - 1];
  const assistant = list.value[list.value.length - 1];
  
  // 注意：后端事件类型是小写的
  if (lastEvent.type === 'start') {
    if (assistant?.role === 'assistant') {
      assistant.status = 'loading';
      assistant.content = `🔄 ${lastEvent.agent}: ${lastEvent.message}...`;
    }
  }
}, { deep: true });

// 切换聊天模式
const handleModeChange = (mode: ChatMode) => {
  if (mode === 'agent') {
    // 生成新的会话ID并连接Agent WebSocket
    currentSessionId.value = 'sess_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
    chatStore.setChatMode('agent', currentSessionId.value);
  } else {
    chatStore.setChatMode('normal');
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

  list.value.push({
    content: input.value.message,
    role: 'user'
  });
  
  // 使用统一的sendMessage方法
  chatStore.sendMessage(input.value.message);
  
  list.value.push({
    content: chatMode.value === 'agent' ? '🔄 Agent协作处理中...' : '',
    role: 'assistant',
    status: 'pending'
  });
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
  <div class="relative w-full b-1 b-#1c1c1c20 bg-#fff p-4 card-wrapper dark:bg-#1c1c1c">
    <!-- 模式切换栏 -->
    <div class="flex items-center justify-between mb-2">
      <n-radio-group v-model:value="chatMode" @update:value="handleModeChange" size="small">
        <n-radio-button value="normal">
          💬 普通问答
        </n-radio-button>
        <n-radio-button value="agent">
          🤖 Agent协作
        </n-radio-button>
      </n-radio-group>
      
      <!-- Agent模式提示 -->
      <n-text v-if="chatMode === 'agent'" depth="3" class="text-12px">
        会话: {{ currentSessionId.slice(0, 12) }}...
      </n-text>
    </div>
    
    <textarea
      ref="inputRef"
      v-model.trim="input.message"
      :placeholder="chatMode === 'agent' ? '给 Agent协作助手 发送消息' : '给 派聪明 发送消息'"
      class="min-h-10 w-full cursor-text resize-none b-none bg-transparent color-#333 caret-[rgb(var(--primary-color))] outline-none dark:color-#f1f1f1"
      @keydown="handShortcut"
    />
    <div class="flex items-center justify-between pt-2">
      <div class="flex items-center text-18px color-gray-500">
        <NText class="text-14px">连接状态：</NText>
        <icon-eos-icons:loading v-if="currentWsStatus === 'CONNECTING'" class="color-yellow" />
        <icon-fluent:plug-connected-checkmark-20-filled v-else-if="currentWsStatus === 'OPEN'" class="color-green" />
        <icon-tabler:plug-connected-x v-else class="color-red" />
        <!-- 模式标签 -->
        <n-tag v-if="chatMode === 'agent'" type="info" size="small" class="ml-2">
          Agent模式
        </n-tag>
      </div>
      <NButton :disabled="sendable" strong circle type="primary" @click="handleSend">
        <template #icon>
          <icon-material-symbols:stop-rounded v-if="isSending" />
          <icon-guidance:send v-else />
        </template>
      </NButton>
    </div>
  </div>
</template>

<style scoped></style>
