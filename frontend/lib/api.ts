import { API_BASE } from "./config";
import { getAccessToken, restoreSession } from "./auth-store";
import type {
  Conversation,
  Document,
  Message,
  Source,
  User
} from "./types";

export async function apiFetch<T>(
  path: string,
  options: RequestInit = {}
): Promise<T> {
  const headers = new Headers(options.headers);
  const token = getAccessToken();
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }
  if (!(options.body instanceof FormData)) {
    headers.set("Content-Type", "application/json");
  }

  let response = await fetch(`${API_BASE}${path}`, {
    ...options,
    credentials: "include",
    headers
  });

  if (response.status === 401 && (await restoreSession())) {
    const retryHeaders = new Headers(options.headers);
    const refreshedToken = getAccessToken();
    if (refreshedToken) {
      retryHeaders.set("Authorization", `Bearer ${refreshedToken}`);
    }
    if (!(options.body instanceof FormData)) {
      retryHeaders.set("Content-Type", "application/json");
    }
    response = await fetch(`${API_BASE}${path}`, {
      ...options,
      credentials: "include",
      headers: retryHeaders
    });
  }

  if (!response.ok) {
    const error = await response.json().catch(() => null);
    throw new Error(error?.message ?? `请求失败 (${response.status})`);
  }

  return response.json() as Promise<T>;
}

export function getMe() {
  return apiFetch<User>("/users/me");
}

export function listDocuments() {
  return apiFetch<Document[]>("/documents");
}

export function getDocument(documentId: number) {
  return apiFetch<Document>(`/documents/${documentId}`);
}

export function uploadDocument(file: File) {
  const body = new FormData();
  body.append("file", file);
  return apiFetch<Document>("/documents", { method: "POST", body });
}

export function deleteDocument(documentId: number) {
  return apiFetch<void>(`/documents/${documentId}`, { method: "DELETE" });
}

export function createConversation(documentId: number, title?: string) {
  return apiFetch<Conversation>("/conversations", {
    method: "POST",
    body: JSON.stringify({ documentId, title })
  });
}

export function listConversations(documentId: number) {
  return apiFetch<Conversation[]>(`/conversations?documentId=${documentId}`);
}

export function listMessages(conversationId: number) {
  return apiFetch<Message[]>(`/conversations/${conversationId}/messages`);
}

export type ChatDonePayload = {
  content: string;
  sources: Source[];
};
