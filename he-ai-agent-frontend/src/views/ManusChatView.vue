<script setup>
import ChatPage from "../components/ChatPage.vue";
import { getManusChatSse } from "../services/chatApi";

function streamRequest(message, _chatId, onMessage, onError, onDone) {
  return getManusChatSse(message, onMessage, onError, onDone);
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
