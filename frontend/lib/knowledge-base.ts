import { API_BASE } from "./config";
import { getAccessToken, restoreSession } from "./auth-store";

/* ---------- 类型（对齐 KnowledgeBaseResponse / KnowledgeDocumentResponse / KnowledgeDocumentBatchItemResponse） ---------- */

export interface KnowledgeBase {
  id: number;
  userId: number;
  name: string;
  description: string | null;
  /** DRAFT / PROCESSING / ACTIVE / ARCHIVED */
  status: string;
  topK: number | null;
  similarityThreshold: number | null;
  rerankEnabled: boolean | null;
  rerankK: number | null;
  documentCount: number;
  chunkCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface KnowledgeDocument {
  id: number;
  knowledgeBaseId: number;
  filename: string;
  fileType: string | null;
  fileSize: number | null;
  pageCount: number | null;
  tokenCount: number | null;
  /** UPLOADED / PROCESSING / INDEXED / FAILED */
  status: string;
  errorMessage: string | null;
  embeddingModelVersion: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface KnowledgeDocumentBatchItem {
  filename: string | null;
  success: boolean;
  document: KnowledgeDocument | null;
  errorMessage: string | null;
}

/* ---------- 请求封装（对齐 api.ts 的鉴权/重试与 auth-store 的错误文案惯例） ---------- */

async function kbFetch<T>(path: string, options: RequestInit = {}): Promise<T> {
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

  const raw = await response.text();
  let data: { message?: string } | null = null;
  try {
    data = raw ? JSON.parse(raw) : null;
  } catch {
    // 服务端返回了非 JSON 内容（如网关/错误页 HTML），data 保持 null
  }
  if (!response.ok) {
    throw new Error(data?.message ?? `请求失败（HTTP ${response.status}），请稍后重试`);
  }
  // 202 / 204 无响应体
  if (!raw) {
    return undefined as T;
  }
  if (!data) {
    throw new Error(`服务响应异常（HTTP ${response.status}），请稍后重试`);
  }
  return data as T;
}

/* ---------- 知识库 ---------- */

export function listKnowledgeBases() {
  return kbFetch<KnowledgeBase[]>("/knowledge-bases");
}

export function getKnowledgeBase(knowledgeBaseId: number) {
  return kbFetch<KnowledgeBase>(`/knowledge-bases/${knowledgeBaseId}`);
}

export function createKnowledgeBase(name: string, description?: string) {
  return kbFetch<KnowledgeBase>("/knowledge-bases", {
    method: "POST",
    body: JSON.stringify({ name, description })
  });
}

export function updateKnowledgeBase(
  knowledgeBaseId: number,
  data: { name?: string; description?: string }
) {
  return kbFetch<KnowledgeBase>(`/knowledge-bases/${knowledgeBaseId}`, {
    method: "PATCH",
    body: JSON.stringify(data)
  });
}

export function publishKnowledgeBase(knowledgeBaseId: number) {
  return kbFetch<KnowledgeBase>(`/knowledge-bases/${knowledgeBaseId}/publish`, {
    method: "PATCH"
  });
}

export function archiveKnowledgeBase(knowledgeBaseId: number) {
  return kbFetch<KnowledgeBase>(`/knowledge-bases/${knowledgeBaseId}/archive`, {
    method: "PATCH"
  });
}

export function unarchiveKnowledgeBase(knowledgeBaseId: number) {
  return kbFetch<KnowledgeBase>(`/knowledge-bases/${knowledgeBaseId}/unarchive`, {
    method: "PATCH"
  });
}

export function restoreKnowledgeBase(knowledgeBaseId: number) {
  return kbFetch<KnowledgeBase>(`/knowledge-bases/${knowledgeBaseId}/restore`, {
    method: "PATCH"
  });
}

export function deleteKnowledgeBase(knowledgeBaseId: number) {
  return kbFetch<void>(`/knowledge-bases/${knowledgeBaseId}`, { method: "DELETE" });
}

export function deleteKnowledgeBases(knowledgeBaseIds: number[]) {
  return kbFetch<void>("/knowledge-bases/batch-delete", {
    method: "POST",
    body: JSON.stringify({ ids: knowledgeBaseIds })
  });
}

export function listDeletedKnowledgeBases() {
  return kbFetch<KnowledgeBase[]>("/knowledge-bases/deleted");
}

/* ---------- 知识库文档 ---------- */

export function listKnowledgeDocuments(knowledgeBaseId: number) {
  return kbFetch<KnowledgeDocument[]>(`/knowledge-bases/${knowledgeBaseId}/documents`);
}

export function getKnowledgeDocument(documentId: number) {
  return kbFetch<KnowledgeDocument>(`/knowledge-documents/${documentId}`);
}

export function uploadKnowledgeDocument(knowledgeBaseId: number, file: File) {
  const body = new FormData();
  body.append("file", file);
  return kbFetch<KnowledgeDocument>(`/knowledge-bases/${knowledgeBaseId}/documents`, {
    method: "POST",
    body
  });
}

export function batchUploadKnowledgeDocuments(
  knowledgeBaseId: number,
  files: File[]
) {
  const body = new FormData();
  files.forEach((file) => body.append("files", file));
  return kbFetch<KnowledgeDocumentBatchItem[]>(
    `/knowledge-bases/${knowledgeBaseId}/documents/batch`,
    { method: "POST", body }
  );
}

export function reparseKnowledgeDocument(documentId: number) {
  return kbFetch<void>(`/knowledge-documents/${documentId}/reparse`, {
    method: "POST"
  });
}

export function deleteKnowledgeDocument(documentId: number) {
  return kbFetch<void>(`/knowledge-documents/${documentId}`, { method: "DELETE" });
}

/**
 * 获取知识库文档原始内容（Blob），用于前端预览。
 * 二进制响应不走 kbFetch（其按 JSON 解析会报"服务响应异常"），
 * 自行处理鉴权头与 401 刷新重试，与 kbFetch 行为对齐。
 */
export async function getKnowledgeDocumentContent(
  documentId: number
): Promise<Blob> {
  const request = async (): Promise<Response> => {
    const headers = new Headers();
    const token = getAccessToken();
    if (token) {
      headers.set("Authorization", `Bearer ${token}`);
    }
    return fetch(`${API_BASE}/knowledge-documents/${documentId}/content`, {
      credentials: "include",
      headers
    });
  };

  let response = await request();
  if (response.status === 401 && (await restoreSession())) {
    response = await request();
  }

  if (!response.ok) {
    const error = await response.json().catch(() => null);
    throw new Error(error?.message ?? `请求失败（HTTP ${response.status}），请稍后重试`);
  }
  return response.blob();
}

/* ---------- 检索配置 / RAG 测试 / 切片（对齐 KnowledgeRetrievalConfig* / KnowledgeRetrievalTestResponse / KnowledgeChunk*） ---------- */

export interface KnowledgeRetrievalConfig {
  topK: number | null;
  similarityThreshold: number | null;
  rerankEnabled: boolean | null;
  rerankK: number | null;
}

export interface KnowledgeRetrievalConfigUpdate {
  topK?: number;
  similarityThreshold?: number;
  rerankEnabled?: boolean;
  rerankK?: number;
}

export interface KnowledgeChunk {
  chunkIndex: number;
  content: string;
  pageStart: number;
  pageEnd: number;
  locationLabel: string | null;
  tokenCount: number;
}

export interface KnowledgeChunkPage {
  items: KnowledgeChunk[];
  total: number;
  page: number;
  size: number;
}

export interface KnowledgeTestRecallItem {
  chunkId: number | null;
  documentId: number | null;
  filename: string | null;
  pageStart: number;
  pageEnd: number;
  locationLabel: string | null;
  distance: number;
  similarity: number;
  snippet: string | null;
}

export interface KnowledgeTestCitation {
  index: number;
  chunkId: number | null;
  documentId: number | null;
  filename: string | null;
  pageStart: number;
  pageEnd: number;
  locationLabel: string | null;
  snippet: string | null;
}

export interface KnowledgeRetrievalTestResult {
  rewrite: {
    originalQuestion: string | null;
    rewrittenQuestion: string | null;
    rewritten: boolean;
    mode: string | null;
  } | null;
  recall: KnowledgeTestRecallItem[] | null;
  rerank: {
    enabled: boolean | null;
    strategy: string | null;
    note: string | null;
  } | null;
  answer: string | null;
  rejected: boolean;
  citations: KnowledgeTestCitation[] | null;
  usedConfig: KnowledgeRetrievalConfig | null;
  timings: {
    rewriteMs: number;
    recallMs: number;
    rerankMs: number;
    answerMs: number;
  } | null;
}

export function getKnowledgeRetrievalConfig(knowledgeBaseId: number) {
  return kbFetch<KnowledgeRetrievalConfig>(
    `/knowledge-bases/${knowledgeBaseId}/retrieval-config`
  );
}

export function updateKnowledgeRetrievalConfig(
  knowledgeBaseId: number,
  data: KnowledgeRetrievalConfigUpdate
) {
  return kbFetch<KnowledgeRetrievalConfig>(
    `/knowledge-bases/${knowledgeBaseId}/retrieval-config`,
    { method: "PATCH", body: JSON.stringify(data) }
  );
}

export function runKnowledgeRetrievalTest(knowledgeBaseId: number, question: string) {
  return kbFetch<KnowledgeRetrievalTestResult>(
    `/knowledge-bases/${knowledgeBaseId}/retrieval-test`,
    { method: "POST", body: JSON.stringify({ question }) }
  );
}

export function listKnowledgeChunks(documentId: number, page: number, size: number) {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  return kbFetch<KnowledgeChunkPage>(
    `/knowledge-documents/${documentId}/chunks?${params.toString()}`
  );
}
