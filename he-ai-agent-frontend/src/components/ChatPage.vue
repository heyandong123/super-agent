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

// 会话持久化：服务端生成的 conversationId 存 localStorage，刷新后复用。
// 单用户单会话重构后不再存 userId：记忆（画像/摘要/历史）全局唯一一份，
// 换个 chatId 也照样接得上上一段对话，客户端无需再携带用户身份。
const SESSION_STORAGE_KEY = "yu_ai_agent_session";
function loadSession() {
  try {
    const raw = localStorage.getItem(SESSION_STORAGE_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch (e) {
    console.warn("读取本地会话失败", e);
    return null;
  }
}
const savedSession = loadSession();
// 有本地会话则复用；无则等服务端 session 事件下发（autoChatId 页面保留旧的客户端生成逻辑）
const chatId = ref(savedSession?.chatId || (props.autoChatId ? `chat_${Date.now()}_${Math.random().toString(36).slice(2, 8)}` : ""));

function handleSession(data) {
  if (!data) {
    return;
  }
  if (data.conversationId) {
    chatId.value = data.conversationId;
  }
  try {
    localStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify({ chatId: chatId.value }));
  } catch (e) {
    console.warn("保存本地会话失败", e);
  }
}

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

// ---- 逐 token 流式渲染（2026-10 流式轮）----
// 三路状态：think 增量追加到当前气泡；final 增量自开一条气泡并记住 id；
// final 事件（终态权威）按 id 覆盖那条气泡的内容。
// 为什么要「终态覆盖」：delta 只是临时渲染，中途失败/服务端兜底时内容可能与权威全文不一致，
// final 一到就以它为准——这也是 OpenAI Agents SDK 的 response.completed 语义。
const finalBubbleId = ref(null);

function appendDeltaToFinalBubble(text) {
  let bubble = messages.value.find((msg) => msg.id === finalBubbleId.value);
  if (!bubble) {
    // 首个 final 增量：新开一条气泡，与思考叙述/步骤行分开
    bubble = {
      id: `final_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`,
      role: "ai",
      content: "",
      streaming: false
    };
    finalBubbleId.value = bubble.id;
    messages.value.push(bubble);
  }
  bubble.content += text;
  bubble.streaming = true; // 打字光标
  scrollToBottom();
}

function handleDelta(data) {
  if (!data || !data.text) {
    return;
  }
  if (data.scope === "final") {
    appendDeltaToFinalBubble(data.text);
    return;
  }
  // think（默认）：模型的思考叙述，追加到当前气泡——与工具步骤行交错出现，
  // 观感对齐 Claude Code「文本流 + 工具行」的形态
  updateLastAiMessage(data.text);
}

function handleFinal(data) {
  const content = data && data.content ? data.content : "";
  const bubble = messages.value.find((msg) => msg.id === finalBubbleId.value);
  if (bubble) {
    // 流式渲染过：用权威全文覆盖（替换而不是追加，避免重复），并收掉打字光标
    bubble.content = content;
    bubble.streaming = false;
    scrollToBottom();
    return;
  }
  // 没有任何 final 增量（流式关闭 / 合成失败走兜底文本）：直落一整条
  if (content) {
    appendMessage("ai", content);
  }
}

function stopSse() {
  if (sseRef.value) {
    sseRef.value.close();
    sseRef.value = null;
  }
}

function handleStreamEnd() {
  isLoading.value = false;
  // 断流/出错时把打字光标收掉：光标留着会暗示「还在生成」
  messages.value.forEach((msg) => {
    msg.streaming = false;
  });
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
  finalBubbleId.value = null;

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
    },
    handleSession,
    handleDelta,
    handleFinal
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
        <p v-if="chatId" class="chat-id">会话 ID：{{ chatId }}</p>
      </div>
    </div>

    <div ref="chatWindow" class="chat-window">
      <div v-for="msg in messages" :key="msg.id" class="message-row" :class="msg.role === 'user' ? 'user' : 'ai'">
        <div v-if="msg.role === 'ai'" class="ai-avatar">{{ aiAvatar }}</div>
        <div class="bubble">{{ msg.content }}<span v-if="msg.streaming" class="stream-cursor"></span></div>
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
