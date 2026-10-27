import { API_BASE } from "./config";
import { getAccessToken, restoreSession } from "./auth-store";
import type {
  Conversation,
  Document,
  Message,
  Project,
  Source,
  User
} from "./types";

interface ApiFetchOptions extends RequestInit {
  asBlob?: boolean;
  /** 下载进度回调（仅 asBlob 且响应带 Content-Length 时 total 可用，否则为 0） */
  onProgress?: (loaded: number, total: number) => void;
}

export async function apiFetch<T>(
  path: string,
  options: ApiFetchOptions = {}
): Promise<T> {
  const { asBlob = false, onProgress, ...request } = options;
  const headers = new Headers(request.headers);
  const token = getAccessToken();
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }
  if (!(request.body instanceof FormData)) {
    headers.set("Content-Type", "application/json");
  }

  let response = await fetch(`${API_BASE}${path}`, {
    ...request,
    credentials: "include",
    headers
  });

  if (response.status === 401 && (await restoreSession())) {
    const retryHeaders = new Headers(request.headers);
    const refreshedToken = getAccessToken();
    if (refreshedToken) {
      retryHeaders.set("Authorization", `Bearer ${refreshedToken}`);
    }
    if (!(request.body instanceof FormData)) {
      retryHeaders.set("Content-Type", "application/json");
    }
    response = await fetch(`${API_BASE}${path}`, {
      ...request,
      credentials: "include",
      headers: retryHeaders
    });
  }

  if (!response.ok) {
    const error = await response.json().catch(() => null);
    throw new Error(error?.message ?? `请求失败 (${response.status})`);
  }

  if (response.status === 204) {
    return undefined as T;
  }
  if (asBlob) {
    if (onProgress && response.body) {
      return (await readBlobWithProgress(response, onProgress)) as T;
    }
    return response.blob() as Promise<T>;
  }
  return response.json() as Promise<T>;
}

/**
 * 流式读取二进制响应并在每片到达时回调进度，最终合并为 Blob。
 * 大文件（如几十 MB 的 docx）在 1Mbps 出口带宽下下载可能长达分钟级，
 * 必须让用户看到「已下载 x MB / 共 y MB」而不是无反馈的转圈。
 */
export async function readBlobWithProgress(
  response: Response,
  onProgress: (loaded: number, total: number) => void
): Promise<Blob> {
  const total = Number(response.headers.get("Content-Length") ?? 0);
  const reader = response.body!.getReader();
  const chunks: BlobPart[] = [];
  let loaded = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    if (value) {
      chunks.push(value as unknown as BlobPart);
      loaded += value.length;
      onProgress(loaded, total);
    }
  }
  return new Blob(chunks);
}

export function getMe() {
  return apiFetch<User>("/users/me");
}

/* ---------- Project ---------- */

export function listProjects() {
  return apiFetch<Project[]>("/projects");
}

export function getProject(projectId: number) {
  return apiFetch<Project>(`/projects/${projectId}`);
}

export function createProject(name: string, description?: string) {
  return apiFetch<Project>("/projects", {
    method: "POST",
    body: JSON.stringify({ name, description })
  });
}

export function updateProject(
  projectId: number,
  data: { name?: string; description?: string }
) {
  return apiFetch<Project>(`/projects/${projectId}`, {
    method: "PATCH",
    body: JSON.stringify(data)
  });
}

export function deleteProject(projectId: number) {
  return apiFetch<void>(`/projects/${projectId}`, { method: "DELETE" });
}

export function deleteProjects(projectIds: number[]) {
  return apiFetch<void>("/projects/batch-delete", {
    method: "POST",
    body: JSON.stringify({ ids: projectIds })
  });
}

export function listDeletedProjects() {
  return apiFetch<Project[]>("/projects/deleted");
}

export function restoreProject(projectId: number) {
  return apiFetch<Project>(`/projects/${projectId}/restore`, {
    method: "PATCH"
  });
}

/* ---------- Document ---------- */

export function listDocuments(projectId: number) {
  return apiFetch<Document[]>(`/documents?projectId=${projectId}`);
}

export function getDocument(documentId: number) {
  return apiFetch<Document>(`/documents/${documentId}`);
}

/**
 * 文档内容会话级缓存：同一 documentId 的原始文件内容不可变（删除后 ID 不复用），
 * 下载过一次后本会话内再次预览直接复用，连协商缓存的往返都省掉。
 * 条目数以文档数上限为界（每项目 5 篇），无需淘汰策略。
 */
const documentContentCache = new Map<number, Blob>();

export async function getDocumentContent(
  documentId: number,
  onProgress?: (loaded: number, total: number) => void
): Promise<Blob> {
  const cached = documentContentCache.get(documentId);
  if (cached) {
    onProgress?.(cached.size, cached.size);
    return cached;
  }
  const blob = await apiFetch<Blob>(`/documents/${documentId}/content`, {
    asBlob: true,
    onProgress
  });
  documentContentCache.set(documentId, blob);
  return blob;
}

export function uploadDocument(file: File, projectId: number) {
  const body = new FormData();
  body.append("file", file);
  return apiFetch<Document>(`/documents?projectId=${projectId}`, {
    method: "POST",
    body
  });
}

export function deleteDocument(documentId: number) {
  return apiFetch<void>(`/documents/${documentId}`, { method: "DELETE" });
}

/* ---------- Conversation ---------- */

export function createConversation(
  projectId: number,
  title?: string,
  knowledgeBaseId?: number | null
) {
  return apiFetch<Conversation>("/conversations", {
    method: "POST",
    body: JSON.stringify({ projectId, title, knowledgeBaseId })
  });
}

// 切换/解绑会话绑定的知识库；knowledgeBaseId 传 null 表示解绑
export function updateConversation(
  conversationId: number,
  data: { knowledgeBaseId?: number | null }
) {
  return apiFetch<Conversation>(`/conversations/${conversationId}`, {
    method: "PATCH",
    body: JSON.stringify(data)
  });
}

export function listConversations(projectId: number) {
  return apiFetch<Conversation[]>(`/conversations?projectId=${projectId}`);
}

export function listMessages(conversationId: number) {
  return apiFetch<Message[]>(`/conversations/${conversationId}/messages`);
}

export type ChatDonePayload = {
  content: string;
  sources: Source[];
};
