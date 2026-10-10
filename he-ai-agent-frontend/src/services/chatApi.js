import http from "./http";

const API_BASE = "http://localhost:8123/api";

function buildQuery(params) {
  return new URLSearchParams(params).toString();
}

function openSse(url, onMessage, onError, onDone) {
  const eventSource = new EventSource(url);
  eventSource.onmessage = (event) => {
    if (!event.data) {
      return;
    }
    onMessage(event.data);
  };
  eventSource.onerror = () => {
    eventSource.close();
    if (onDone) {
      onDone();
    }
    if (onError) {
      onError(new Error("SSE 连接异常或已结束"));
    }
  };
  return eventSource;
}

export function getLoveChatSse(message, chatId, onMessage, onError, onDone) {
  const query = buildQuery({ message, chatId });
  return openSse(`${API_BASE}/ai/love_app/chat/sse?${query}`, onMessage, onError, onDone);
}

export function getManusChatSse(message, onMessage, onError, onDone) {
  const query = buildQuery({ message });
  return openSse(`${http.defaults.baseURL}/ai/manus/chat?${query}`, onMessage, onError, onDone);
}
