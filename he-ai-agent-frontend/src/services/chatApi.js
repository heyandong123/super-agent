import http from "./http";

const API_BASE = "http://localhost:8123/api";

function buildQuery(params) {
  return new URLSearchParams(params).toString();
}

// 具名事件的 data 是 JSON 字符串：process 与 final 都取其中的 content 字段
// （preview 只有 80 字，是给折叠列表用的摘要）。解析失败就原样返回，避免格式一变整段内容丢失。
function extractContent(raw) {
  if (!raw) {
    return "";
  }
  try {
    const data = JSON.parse(raw);
    if (typeof data === "string") {
      return data;
    }
    return data.content || "";
  } catch (e) {
    return raw;
  }
}

function openSse(url, onMessage, onError, onDone, onSession) {
  const eventSource = new EventSource(url);
  // 后端推的是具名事件（session / process / final），默认的 onmessage 一个都收不到，
  // 所以只监听 onmessage 时 Manus 正文永远是空的。
  // 这里把 process（每一步的执行过程）与 final（最终答案）都回落到 onMessage，
  // 页面组件不用改一行就能恢复渲染；将来若要分开渲染，再给两者各开一个回调即可。
  if (onMessage) {
    const forwardToMessage = (event) => {
      const chunk = extractContent(event.data);
      if (chunk) {
        onMessage(chunk);
      }
    };
    eventSource.addEventListener("process", forwardToMessage);
    eventSource.addEventListener("final", forwardToMessage);
  }
  eventSource.onmessage = (event) => {
    if (!event.data) {
      return;
    }
    onMessage(event.data);
  };
  // session 事件：服务端回传生成的 conversationId / userId（首条消息时无会话，由服务端生成）
  if (onSession) {
    eventSource.addEventListener("session", (event) => {
      try {
        onSession(JSON.parse(event.data));
      } catch (e) {
        console.error("解析 session 事件失败", e);
      }
    });
  }
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

export function getManusChatSse(message, chatId, userId, onMessage, onError, onDone, onSession) {
  const query = buildQuery({ message, chatId: chatId || "", userId: userId || "" });
  return openSse(`${http.defaults.baseURL}/ai/manus/chat?${query}`, onMessage, onError, onDone, onSession);
}
