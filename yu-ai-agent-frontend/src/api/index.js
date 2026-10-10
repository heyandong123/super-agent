import axios from 'axios'

// 根据环境变量设置 API 基础 URL
const API_BASE_URL = process.env.NODE_ENV === 'production' 
 ? '/api' // 生产环境使用相对路径，适用于前后端部署在同一域名下
 : 'http://localhost:8123/api' // 开发环境指向本地后端服务

// 创建axios实例
const request = axios.create({
  baseURL: API_BASE_URL,
  timeout: 60000
})

// 封装SSE连接(支持命名事件: session / process / final)
export const connectSSE = (url, params, onMessage, onError, onProcess, onFinal, onSession) => {
  const queryString = Object.keys(params)
    .map(key => `${encodeURIComponent(key)}=${encodeURIComponent(params[key])}`)
    .join('&')

  const fullUrl = `${API_BASE_URL}${url}?${queryString}`
  const eventSource = new EventSource(fullUrl)

  // 服务端确认会话事件：无会话时服务端生成 conversationId，连同 userId 回传前端，
  // 前端存 localStorage 复用，保证多轮记忆不断链
  eventSource.addEventListener('session', event => {
    try {
      const data = JSON.parse(event.data)
      if (onSession) onSession(data)
    } catch (e) {
      if (onMessage) onMessage(event.data)
    }
  })

  eventSource.addEventListener('process', event => {
    try {
      const data = JSON.parse(event.data)
      if (onProcess) onProcess(data)
    } catch (e) {
      if (onMessage) onMessage(event.data)
    }
  })

  eventSource.addEventListener('final', event => {
    try {
      const data = JSON.parse(event.data)
      if (onFinal) onFinal(data)
    } catch (e) {
      if (onMessage) onMessage(event.data)
    }
  })

  eventSource.onmessage = event => {
    if (onMessage) onMessage(event.data)
  }

  eventSource.onerror = error => {
    if (onError) onError(error)
    eventSource.close()
  }

  return eventSource
}

// AI恋爱大师聊天
export const chatWithLoveApp = (message, chatId) => {
  return connectSSE('/ai/love_app/chat/sse', { message, chatId })
}

// AI超级智能体聊天
export const chatWithManus = (message, chatId, userId, callbacks = {}) => {
  const params = { message }
  if (chatId) params.chatId = chatId
  if (userId) params.userId = userId
  return connectSSE('/ai/manus/chat', params,
    callbacks.onMessage, callbacks.onError,
    callbacks.onProcess, callbacks.onFinal, callbacks.onSession)
}

// AI多智能体协调器聊天
export const chatWithMultiAgentManus = (message, chatId, userId, callbacks = {}) => {
  const params = { message }
  if (chatId) params.chatId = chatId
  if (userId) params.userId = userId
  return connectSSE('/ai/manus/multi_agent/chat', params,
    callbacks.onMessage, callbacks.onError,
    callbacks.onProcess, callbacks.onFinal, callbacks.onSession)
}

export default {
  chatWithLoveApp,
  chatWithManus,
  chatWithMultiAgentManus
} 
