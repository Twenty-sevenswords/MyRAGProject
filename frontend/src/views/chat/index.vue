<script setup lang="ts">
import ChatList from './modules/chat-list.vue'
import InputBox from './modules/input-box.vue'
// Agent调试面板（自带触发按钮）
import AgentDebugPanel from './modules/agent-debug-panel.vue'
</script>

<template>
  <div class="relative h-full min-h-0 flex-col gap-4">
    <ChatList />
    <InputBox />
    <!-- Agent调试面板（自带触发按钮） -->
    <AgentDebugPanel />
  </div>
</template>

<style scoped></style>
