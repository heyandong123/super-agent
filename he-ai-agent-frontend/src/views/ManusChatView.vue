<script setup>
import ChatPage from "../components/ChatPage.vue";
import { getManusChatSse } from "../services/chatApi";

// onDelta / onFinal 是 2026-10 流式轮新增的两路回调（逐 token 增量、最终答案定稿），
// 这里只做透传，渲染策略全在 ChatPage 里
function streamRequest(message, chatId, onMessage, onError, onDone, onSession, onDelta, onFinal) {
  return getManusChatSse(message, chatId, onMessage, onError, onDone, onSession, onDelta, onFinal);
}
</script>

<template>
  <ChatPage
    title="AI 超级智能体"
    ai-avatar="智"
    :split-by-step="true"
    :append-extra-break-per-chunk="true"
    :stream-request="streamRequest"
  />
</template>
