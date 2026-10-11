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

function openSse(url, onMessage, onError, onDone, onSession, onDelta, onFinal) {
  const eventSource = new EventSource(url);
  // 后端推的是具名事件（session / process / delta / final），默认的 onmessage 一个都收不到，
  // 所以只监听 onmessage 时 Manus 正文永远是空的。
  // 事件分工（2026-10 流式轮拆分）：
  //   process —— 每一步的工具执行摘要，仍走 onMessage（保持既有渲染路径不变）；
  //   delta   —— 逐 token 的文本增量（scope=think 是思考叙述、scope=final 是最终答案），走 onDelta；
  //   final   —— 最终答案的权威全文，走 onFinal（没有 onFinal 时回落 onMessage，旧调用方不用改）。
  if (onMessage) {
    const forwardToMessage = (event) => {
      const chunk = extractContent(event.data);
      if (chunk) {
        onMessage(chunk);
      }
    };
    eventSource.addEventListener("process", forwardToMessage);
    if (!onFinal) {
      // 兼容路径：调用方还没接 onFinal 时，final 照旧拼进正文（单测/旧页面）
      eventSource.addEventListener("final", forwardToMessage);
    }
  }
  // 文本增量：json 是 {type, scope, step, text}。这里只把「谁在说 + 说什么」交给页面，
  // 渲染策略（思考追加/答案独立气泡/终态覆盖）留在组件里，传输层不掺和。
  if (onDelta) {
    eventSource.addEventListener("delta", (event) => {
      try {
        const data = JSON.parse(event.data);
        if (data && data.text) {
          onDelta(data);
        }
      } catch (e) {
        console.warn("解析 delta 事件失败", e);
      }
    });
  }
  if (onFinal) {
    eventSource.addEventListener("final", (event) => {
      try {
        onFinal(JSON.parse(event.data));
      } catch (e) {
        // 兜底：解析不了就当普通文本处理，绝不因为格式问题丢掉最终答案
        onFinal({ content: event.data });
      }
    });
  }
  eventSource.onmessage = (event) => {
    if (!event.data) {
      return;
    }
    onMessage(event.data);
  };
  // session 事件：服务端回传生成的 conversationId（首条消息时无会话，由服务端生成）；
  // 单用户单会话重构后不再有 userId（记忆全局唯一一份，不按用户/会话寻址）
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

export function getManusChatSse(message, chatId, onMessage, onError, onDone, onSession, onDelta, onFinal) {
  const query = buildQuery({ message, chatId: chatId || "" });
  return openSse(
    `${http.defaults.baseURL}/ai/manus/chat?${query}`,
    onMessage,
    onError,
    onDone,
    onSession,
    onDelta,
    onFinal
  );
}
