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

// 封装SSE连接(支持命名事件: session / process / delta / final)
export const connectSSE = (url, params, onMessage, onError, onProcess, onFinal, onSession, onDelta) => {
  const queryString = Object.keys(params)
    .map(key => `${encodeURIComponent(key)}=${encodeURIComponent(params[key])}`)
    .join('&')

  const fullUrl = `${API_BASE_URL}${url}?${queryString}`
  const eventSource = new EventSource(fullUrl)

  // 服务端确认会话事件：无会话时服务端生成 conversationId 回传前端，前端存 localStorage 复用。
  // 单用户单会话重构后不再有 userId（记忆全局唯一一份，不按用户/会话寻址）
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

  // 文本增量事件（2026-10 流式轮）：{type, scope, step, text}。
  // scope=think 是模型的思考叙述，scope=final 是最终答案的逐字增量；
  // final 事件仍是权威全文，页面收到后用它覆盖流式期间的临时内容。
  eventSource.addEventListener('delta', event => {
    try {
      const data = JSON.parse(event.data)
      if (onDelta) onDelta(data)
    } catch (e) {
      // delta 是临时渲染，解析不了直接丢，不影响 final 落定
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
export const chatWithManus = (message, chatId, callbacks = {}) => {
  const params = { message }
  if (chatId) params.chatId = chatId
  return connectSSE('/ai/manus/chat', params,
    callbacks.onMessage, callbacks.onError,
    callbacks.onProcess, callbacks.onFinal, callbacks.onSession, callbacks.onDelta)
}

// AI多智能体协调器聊天
export const chatWithMultiAgentManus = (message, chatId, callbacks = {}) => {
  const params = { message }
  if (chatId) params.chatId = chatId
  return connectSSE('/ai/manus/multi_agent/chat', params,
    callbacks.onMessage, callbacks.onError,
    callbacks.onProcess, callbacks.onFinal, callbacks.onSession, callbacks.onDelta)
}

export default {
  chatWithLoveApp,
  chatWithManus,
  chatWithMultiAgentManus
} 
