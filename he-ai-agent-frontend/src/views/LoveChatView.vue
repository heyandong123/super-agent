<script setup>
import ChatPage from "../components/ChatPage.vue";
import { getLoveChatSse } from "../services/chatApi";

function streamRequest(message, chatId, onMessage, onError, onDone) {
  return getLoveChatSse(message, chatId, onMessage, onError, onDone);
}
</script>

<template>
  <ChatPage title="AI 恋爱大师" ai-avatar="恋" :auto-chat-id="true" :stream-request="streamRequest" />
</template>
