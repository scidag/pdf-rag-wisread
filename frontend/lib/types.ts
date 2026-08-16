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
  snippet: string;
}

export interface Conversation {
  id: number;
  projectId: number;
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
  refreshToken: string;
  expiresIn: number;
  user: User;
}
