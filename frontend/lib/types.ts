export interface User {
  id: number;
  username: string;
  email: string;
}

export interface Project {
  id: number;
  name: string;
  description: string | null;
  documentCount: number;
  conversationCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface Document {
  id: number;
  projectId: number | null;
  filename: string;
  fileType: string | null;
  fileSize: number | null;
  pageCount: number | null;
  tokenCount: number | null;
  status: string;
  errorMessage: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface Source {
  index: number;
  chunkId: number;
  documentId: number | null;
  filename: string | null;
  pageStart: number;
  pageEnd: number;
  locationLabel: string | null;
  snippet: string;
}

export interface Conversation {
  id: number;
  projectId: number;
  // 会话绑定的独立知识库 ID（未绑定为 null，问答走项目检索）
  knowledgeBaseId: number | null;
  title: string;
  createdAt: string;
  updatedAt: string;
}

export interface Message {
  id: number;
  role: "user" | "assistant";
  content: string;
  sources: Source[];
  createdAt: string;
}

export interface AuthResponse {
  accessToken: string;
  // refreshToken 一律且仅通过 HttpOnly Cookie 下发（FR-3），响应体不再包含
  expiresIn: number;
  user: User;
}
