import { API_BASE } from "./config";
import { getAccessToken, restoreSession } from "./auth-store";
import type { ChatDonePayload } from "./api";

interface StreamCallbacks {
  onDelta: (content: string) => void;
  onDone: (payload: ChatDonePayload) => void;
}

export async function streamChat(
  conversationId: number,
  content: string,
  callbacks: StreamCallbacks
) {
  let response = await postChat(conversationId, content);
  if (response.status === 401 && (await restoreSession())) {
    response = await postChat(conversationId, content);
  }

  if (!response.ok || !response.body) {
    const error = await response.json().catch(() => null);
    throw new Error(error?.message ?? "问答请求失败");
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  while (true) {
    const { done, value } = await reader.read();
    if (done) {
      break;
    }
    buffer += decoder.decode(value, { stream: true });
    let separatorIndex = buffer.indexOf("\n\n");
    while (separatorIndex >= 0) {
      const rawEvent = buffer.slice(0, separatorIndex);
      buffer = buffer.slice(separatorIndex + 2);
      handleEvent(rawEvent, callbacks);
      separatorIndex = buffer.indexOf("\n\n");
    }
  }
}

function postChat(conversationId: number, content: string) {
  const token = getAccessToken();
  return fetch(
    `${API_BASE}/conversations/${conversationId}/messages`,
    {
      method: "POST",
      credentials: "include",
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: `Bearer ${token}` } : {})
      },
      body: JSON.stringify({ content })
    }
  );
}

function handleEvent(rawEvent: string, callbacks: StreamCallbacks) {
  let event = "message";
  let data = "";
  for (const line of rawEvent.split("\n")) {
    if (line.startsWith("event:")) {
      event = line.slice(6).trim();
    } else if (line.startsWith("data:")) {
      data += line.slice(5).trim();
    }
  }
  if (!data) {
    return;
  }

  const payload = JSON.parse(data) as ChatDonePayload & { message?: string };
  if (event === "delta" && payload.content) {
    callbacks.onDelta(payload.content);
  } else if (event === "done") {
    callbacks.onDone(payload);
  } else if (event === "error") {
    throw new Error(payload.message ?? "问答请求失败");
  }
}
