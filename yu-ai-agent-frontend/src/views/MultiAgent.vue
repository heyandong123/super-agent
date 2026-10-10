<template>
  <div class="multi-agent-container">
    <div class="header">
      <div class="back-button" @click="goBack">返回</div>
      <h1 class="title">AI多智能体协调器</h1>
      <div class="chat-id">会话ID: {{ chatId }}</div>
    </div>
    
    <div class="content-wrapper">
      <div class="chat-area">
        <ChatRoom 
          :messages="messages" 
          :connection-status="connectionStatus"
          ai-type="multi"
          @send-message="sendMessage"
        />
      </div>
    </div>
    
    <div class="footer-container">
      <AppFooter />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { useHead } from '@vueuse/head'
import ChatRoom from '../components/ChatRoom.vue'
import AppFooter from '../components/AppFooter.vue'
import { chatWithMultiAgentManus } from '../api'

// 设置页面标题和元数据
useHead({
  title: 'AI多智能体协调器 - 恋爱大师智能体应用平台',
  meta: [
    {
      name: 'description',
      content: 'AI多智能体协调器会拆解复杂任务，调度搜索、文档和通用智能体协作完成任务'
    },
    {
      name: 'keywords',
      content: 'AI多智能体,智能体协调器,任务拆解,多Agent,AI助手,AI超级智能体'
    }
  ]
})

const router = useRouter()
const messages = ref([])
const connectionStatus = ref('disconnected')
let eventSource = null

// 会话持久化：服务端生成的 conversationId + userId 存 sessionStorage。
// 用 sessionStorage 而非 localStorage：每个标签页独立会话（开两个页面记忆不混淆），
// 刷新页面会话保留（多轮记忆不丢），关闭标签页则下次开启新会话。
const SESSION_STORAGE_KEY = 'yu_ai_agent_multi_session'
const loadSession = () => {
  try {
    const raw = sessionStorage.getItem(SESSION_STORAGE_KEY)
    return raw ? JSON.parse(raw) : null
  } catch (e) {
    return null
  }
}
const saveSession = () => {
  sessionStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify({
    chatId: chatId.value,
    userId: userId.value
  }))
}
const savedSession = loadSession()
// chatId 为空 = 首次会话，请求不带 chatId，由服务端生成 UUID 并通过 session 事件回传
const chatId = ref(savedSession?.chatId || '')
const userId = ref(savedSession?.userId || '')

// 添加消息到列表
const addMessage = (content, isUser, type = '') => {
  messages.value.push({
    content,
    isUser,
    type,
    time: new Date().getTime()
  })
}

// 发送消息
const sendMessage = (message) => {
  addMessage(message, true, 'user-question')
  
  // 连接SSE
  if (eventSource) {
    eventSource.close()
  }
  
  // 设置连接状态
  connectionStatus.value = 'connecting'
  
  // 临时存储
  let messageBuffer = []
  let lastBubbleTime = Date.now()
  let isFirstResponse = true
  
  const chineseEndPunctuation = ['。', '！', '？', '…']
  const minBubbleInterval = 800
  
  // 创建消息气泡的函数
  const createBubble = (content, type = 'ai-answer') => {
    if (!content.trim()) return
    
    const now = Date.now()
    const timeSinceLastBubble = now - lastBubbleTime
    
    if (isFirstResponse) {
      addMessage(content, false, type)
      isFirstResponse = false
    } else if (timeSinceLastBubble < minBubbleInterval) {
      setTimeout(() => {
        addMessage(content, false, type)
      }, minBubbleInterval - timeSinceLastBubble)
    } else {
      addMessage(content, false, type)
    }
    
    lastBubbleTime = now
    messageBuffer = []
  }
  
  eventSource = chatWithMultiAgentManus(message, chatId.value, userId.value, {
    onSession: (data) => {
      // 服务端确认会话：首次请求服务端生成 conversationId，之后请求携带复用
      if (data.conversationId) chatId.value = data.conversationId
      if (data.userId) userId.value = data.userId
      saveSession()
    },
    onFinal: (data) => {
      // 后端 BaseAgent 发送命名事件 final（完整回复），不再依赖裸文本流
      createBubble(data.content, 'ai-final')
      connectionStatus.value = 'disconnected'
      eventSource.close()
    }
  })

  // 监听SSE消息（兜底：仅后端发送裸文本时生效）
  eventSource.onmessage = (event) => {
    const data = event.data
    
    if (data && data !== '[DONE]') {
      messageBuffer.push(data)
      
      const combinedText = messageBuffer.join('')
      const lastChar = data.charAt(data.length - 1)
      const hasCompleteSentence = chineseEndPunctuation.includes(lastChar) || data.includes('\n\n')
      const isLongEnough = combinedText.length > 40
      
      if (hasCompleteSentence || isLongEnough) {
        createBubble(combinedText)
      }
    }
    
    if (data === '[DONE]') {
      if (messageBuffer.length > 0) {
        createBubble(messageBuffer.join(''), 'ai-final')
      }
      
      connectionStatus.value = 'disconnected'
      eventSource.close()
    }
  }
  
  // 监听SSE错误
  eventSource.onerror = (error) => {
    console.error('SSE Error:', error)
    connectionStatus.value = messageBuffer.length > 0 ? 'disconnected' : 'error'
    eventSource.close()
    
    if (messageBuffer.length > 0) {
      createBubble(messageBuffer.join(''), 'ai-final')
    }
  }
}

// 返回主页
const goBack = () => {
  router.push('/')
}

// 页面加载时添加欢迎消息（会话 ID 已在会话持久化逻辑中加载/生成）
onMounted(() => {
  addMessage('你好，我是AI多智能体协调器。我会先拆解任务，再调度搜索、文档和通用智能体协作完成复杂目标。你可以给我一个需要多步骤处理的任务。', false)
})

// 组件销毁前关闭SSE连接
onBeforeUnmount(() => {
  if (eventSource) {
    eventSource.close()
  }
})
</script>

<style scoped>
.multi-agent-container {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  background-color: #f7fffc;
}

.header {
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  padding: 16px 24px;
  background: linear-gradient(90deg, #009b7a, #0077b6);
  color: white;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
  position: sticky;
  top: 0;
  z-index: 10;
}

.back-button {
  font-size: 16px;
  cursor: pointer;
  display: flex;
  align-items: center;
  transition: opacity 0.2s;
  justify-self: start;
}

.back-button:hover {
  opacity: 0.8;
}

.back-button:before {
  content: '←';
  margin-right: 8px;
}

.title {
  font-size: 20px;
  font-weight: bold;
  margin: 0;
  text-align: center;
  justify-self: center;
}

.chat-id {
  font-size: 14px;
  opacity: 0.85;
  justify-self: end;
}

.content-wrapper {
  display: flex;
  flex-direction: column;
  flex: 1;
}

.chat-area {
  flex: 1;
  padding: 16px;
  overflow: hidden;
  position: relative;
  min-height: calc(100vh - 56px - 180px);
  margin-bottom: 16px;
}

.footer-container {
  margin-top: auto;
}

/* 响应式样式 */
@media (max-width: 768px) {
  .header {
    padding: 12px 16px;
  }
  
  .title {
    font-size: 18px;
  }
  
  .chat-id {
    font-size: 12px;
  }
  
  .chat-area {
    padding: 12px;
    min-height: calc(100vh - 48px - 160px);
    margin-bottom: 12px;
  }
}

@media (max-width: 480px) {
  .header {
    padding: 10px 12px;
  }
  
  .back-button {
    font-size: 14px;
  }
  
  .title {
    font-size: 16px;
  }
  
  .chat-id {
    display: none;
  }
  
  .chat-area {
    padding: 8px;
    min-height: calc(100vh - 42px - 150px);
    margin-bottom: 8px;
  }
}
</style>
