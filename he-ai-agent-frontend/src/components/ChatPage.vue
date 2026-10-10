<script setup>
import { nextTick, onBeforeUnmount, ref } from "vue";
import { useRouter } from "vue-router";

const props = defineProps({
  title: {
    type: String,
    required: true
  },
  autoChatId: {
    type: Boolean,
    default: false
  },
  streamRequest: {
    type: Function,
    required: true
  },
  splitByStep: {
    type: Boolean,
    default: false
  },
  appendExtraBreakPerChunk: {
    type: Boolean,
    default: false
  },
  aiAvatar: {
    type: String,
    default: "AI"
  }
});

const router = useRouter();
const inputMessage = ref("");
const isLoading = ref(false);
const messages = ref([]);
const chatWindow = ref(null);
const sseRef = ref(null);
const chatId = ref(props.autoChatId ? `chat_${Date.now()}_${Math.random().toString(36).slice(2, 8)}` : "");

function scrollToBottom() {
  nextTick(() => {
    if (chatWindow.value) {
      chatWindow.value.scrollTop = chatWindow.value.scrollHeight;
    }
  });
}

function appendMessage(role, content) {
  messages.value.push({
    id: `${Date.now()}_${Math.random().toString(36).slice(2, 8)}`,
    role,
    content
  });
  scrollToBottom();
}

function updateLastAiMessage(fragment) {
  const last = messages.value[messages.value.length - 1];
  if (!last || last.role !== "ai") {
    appendMessage("ai", fragment);
    return;
  }
  last.content += fragment;
  scrollToBottom();
}

function updateAiMessageByStep(fragment) {
  const parts = fragment.split(/(Step\s+\d+:)/g).filter(Boolean);
  for (const part of parts) {
    const trimmed = part.trim();
    if (!trimmed) {
      continue;
    }

    if (/^Step\s+\d+:$/.test(trimmed)) {
      appendMessage("ai", trimmed);
      continue;
    }

    const last = messages.value[messages.value.length - 1];
    if (!last || last.role !== "ai") {
      appendMessage("ai", part);
      continue;
    }

    last.content += part;
    scrollToBottom();
  }
}

function formatChunk(chunk) {
  if (!props.appendExtraBreakPerChunk) {
    return chunk;
  }
  return `${chunk}\n\n`;
}

function stopSse() {
  if (sseRef.value) {
    sseRef.value.close();
    sseRef.value = null;
  }
}

function handleStreamEnd() {
  isLoading.value = false;
  stopSse();
}

function sendMessage() {
  const text = inputMessage.value.trim();
  if (!text || isLoading.value) {
    return;
  }

  appendMessage("user", text);
  inputMessage.value = "";
  isLoading.value = true;

  stopSse();
  sseRef.value = props.streamRequest(
    text,
    chatId.value,
    (chunk) => {
      const formattedChunk = formatChunk(chunk);
      if (props.splitByStep) {
        updateAiMessageByStep(formattedChunk);
        return;
      }
      updateLastAiMessage(formattedChunk);
    },
    (error) => {
      if (!messages.value.length || messages.value[messages.value.length - 1].role !== "ai") {
        appendMessage("ai", `系统提示：${error.message}`);
      }
      handleStreamEnd();
    },
    () => {
      handleStreamEnd();
    }
  );
}

onBeforeUnmount(() => {
  stopSse();
});
</script>

<template>
  <div class="page">
    <div class="chat-header">
      <button class="back-btn" @click="router.push('/')">返回主页</button>
      <div>
        <h2>{{ title }}</h2>
        <p v-if="autoChatId" class="chat-id">会话 ID：{{ chatId }}</p>
      </div>
    </div>

    <div ref="chatWindow" class="chat-window">
      <div v-for="msg in messages" :key="msg.id" class="message-row" :class="msg.role === 'user' ? 'user' : 'ai'">
        <div v-if="msg.role === 'ai'" class="ai-avatar">{{ aiAvatar }}</div>
        <div class="bubble">{{ msg.content }}</div>
      </div>
      <div v-if="isLoading" class="typing">AI 正在思考中...</div>
    </div>

    <div class="chat-input">
      <input
        v-model="inputMessage"
        type="text"
        placeholder="请输入内容，按回车发送"
        @keydown.enter="sendMessage"
      />
      <button :disabled="isLoading" @click="sendMessage">发送</button>
    </div>
  </div>
</template>
