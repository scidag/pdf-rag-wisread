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
}

export async function apiFetch<T>(
  path: string,
  options: ApiFetchOptions = {}
): Promise<T> {
  const { asBlob = false, ...request } = options;
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
  return (asBlob ? response.blob() : response.json()) as Promise<T>;
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

export function getDocumentContent(documentId: number) {
  return apiFetch<Blob>(`/documents/${documentId}/content`, { asBlob: true });
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

export function createConversation(projectId: number, title?: string) {
  return apiFetch<Conversation>("/conversations", {
    method: "POST",
    body: JSON.stringify({ projectId, title })
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
