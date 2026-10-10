<template>
  <div class="super-agent-container">
    <div class="header">
      <div class="back-button" @click="goBack">返回</div>
      <h1 class="title">AI超级智能体</h1>
      <div class="placeholder"></div>
    </div>
    
    <div class="content-wrapper">
      <div class="chat-area">
        <!-- 执行过程面板 -->
        <div v-if="processSteps.length > 0" class="process-panel">
          <div class="process-header" @click="showProcess = !showProcess">
            <span class="process-icon">{{ showProcess ? '▼' : '▶' }}</span>
            <span>执行过程 ({{ processSteps.length }} 步)</span>
            <span v-if="connectionStatus === 'connecting'" class="spinner"></span>
          </div>
          <div v-if="showProcess" class="process-steps">
            <div v-for="(step, idx) in processSteps" :key="idx" class="process-step"
                 :class="{ expanded: step.expanded }"
                 @click="step.expanded = !step.expanded">
              <div class="step-header">
                <span class="step-num">{{ step.step }}</span>
                <span class="step-tool">{{ step.tool }}</span>
                <span class="step-expand">{{ step.expanded ? '收起' : '展开' }}</span>
              </div>
              <div v-if="step.expanded" class="step-content">{{ step.content }}</div>
              <div v-else class="step-preview">{{ step.preview }}</div>
            </div>
          </div>
        </div>
        <ChatRoom
          :messages="messages"
          :connection-status="connectionStatus"
          ai-type="super"
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
import { chatWithManus } from '../api'

// 设置页面标题和元数据
useHead({
  title: 'AI超级智能体 - 恋爱大师智能体应用平台',
  meta: [
    {
      name: 'description',
      content: 'AI超级智能体是恋爱大师智能体应用平台的全能助手，能解答各类专业问题，提供精准建议和解决方案'
    },
    {
      name: 'keywords',
      content: 'AI超级智能体,智能助手,专业问答,AI问答,专业建议,AI智能体'
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
const SESSION_STORAGE_KEY = 'yu_ai_agent_session'
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

// 步骤列表(可展开的执行过程)
const processSteps = ref([])
const showProcess = ref(true)

// 发送消息
const sendMessage = (message) => {
  addMessage(message, true, 'user-question')
  processSteps.value = []
  connectionStatus.value = 'connecting'

  eventSource = chatWithManus(message, chatId.value, userId.value, {
    onProcess: (data) => {
      processSteps.value.push({
        step: data.step,
        tool: data.tool,
        preview: data.preview,
        content: data.content,
        expanded: false
      })
    },
    onFinal: (data) => {
      addMessage(data.content, false, 'ai-final')
      connectionStatus.value = 'disconnected'
      eventSource.close()
    },
    onError: () => {
      connectionStatus.value = 'error'
      eventSource.close()
    },
    onSession: (data) => {
      // 服务端确认会话：首次请求服务端生成 conversationId，之后请求携带复用
      if (data.conversationId) chatId.value = data.conversationId
      if (data.userId) userId.value = data.userId
      saveSession()
    }
  })
}

// 返回主页
const goBack = () => {
  router.push('/')
}

// 页面加载时添加欢迎消息
onMounted(() => {
  // 添加欢迎消息
  addMessage('你好，我是AI超级智能体。我可以解答各类问题，提供专业建议，请问有什么可以帮助你的吗？', false)
})

// 组件销毁前关闭SSE连接
onBeforeUnmount(() => {
  if (eventSource) {
    eventSource.close()
  }
})
</script>

<style scoped>
.super-agent-container {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  background-color: #f9fbff;
}

.header {
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  padding: 16px 24px;
  background-color: #3f51b5;
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

.placeholder {
  width: 1px;
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
  /* 设置最小高度确保内容显示正常 */
  min-height: calc(100vh - 56px - 180px); /* 100vh减去头部高度和页脚高度 */
  margin-bottom: 16px; /* 为页脚留出空间 */
}

/* 执行过程面板 */
.process-panel {
  background: #f5f7fa;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  margin-bottom: 12px;
  overflow: hidden;
}
.process-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  cursor: pointer;
  font-size: 14px;
  font-weight: 500;
  color: #606266;
  user-select: none;
}
.process-header:hover { background: #ebeef5; }
.process-icon { font-size: 12px; color: #909399; }
.spinner {
  width: 14px; height: 14px;
  border: 2px solid #c0c4cc;
  border-top-color: #3f51b5;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
  margin-left: auto;
}
@keyframes spin { to { transform: rotate(360deg); } }
.process-steps { border-top: 1px solid #e4e7ed; }
.process-step {
  padding: 8px 14px;
  border-bottom: 1px solid #ebeef5;
  cursor: pointer;
  transition: background 0.15s;
}
.process-step:hover { background: #ecf0f8; }
.process-step:last-child { border-bottom: none; }
.step-header {
  display: flex;
  align-items: center;
  gap: 10px;
}
.step-num {
  background: #3f51b5;
  color: #fff;
  border-radius: 50%;
  width: 22px; height: 22px;
  display: flex; align-items: center; justify-content: center;
  font-size: 12px; font-weight: 600;
  flex-shrink: 0;
}
.step-tool {
  font-size: 13px;
  color: #303133;
  font-weight: 500;
  flex: 1;
}
.step-expand {
  font-size: 12px;
  color: #909399;
  flex-shrink: 0;
}
.step-preview {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
  padding-left: 32px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.step-content {
  font-size: 12px;
  color: #606266;
  margin-top: 6px;
  padding: 8px 10px;
  background: #fff;
  border-radius: 4px;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 200px;
  overflow-y: auto;
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
  
  .chat-area {
    padding: 12px;
    min-height: calc(100vh - 48px - 160px); /* 调整计算值 */
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
  
  .chat-area {
    padding: 8px;
    min-height: calc(100vh - 42px - 150px); /* 再次调整计算值 */
    margin-bottom: 8px;
  }
}
</style> 