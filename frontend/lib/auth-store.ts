import { API_BASE } from "./config";
import type { AuthResponse, User } from "./types";

let accessToken: string | null = null;

export function getAccessToken() {
  return accessToken;
}

export function setAccessToken(token: string | null) {
  accessToken = token;
}

export async function restoreSession(): Promise<User | null> {
  try {
    const response = await fetch(`${API_BASE}/auth/refresh`, {
      method: "POST",
      credentials: "include"
    });
    if (!response.ok) {
      return null;
    }
    const data = (await response.json()) as AuthResponse;
    accessToken = data.accessToken;
    return data.user;
  } catch {
    return null;
  }
}

export async function login(email: string, password: string): Promise<User> {
  const response = await fetch(`${API_BASE}/auth/login`, {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password })
  });
  const data = await readJson(response);
  accessToken = data.accessToken;
  return data.user;
}

export async function register(
  username: string,
  email: string,
  password: string
): Promise<User> {
  const response = await fetch(`${API_BASE}/auth/register`, {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, email, password })
  });
  const data = await readJson(response);
  accessToken = data.accessToken;
  return data.user;
}

export async function logout(): Promise<void> {
  try {
    const token = accessToken;
    await fetch(`${API_BASE}/auth/logout`, {
      method: "POST",
      credentials: "include",
      headers: token ? { Authorization: `Bearer ${token}` } : undefined
    });
  } finally {
    accessToken = null;
  }
}

async function readJson(response: Response): Promise<AuthResponse> {
  const data = await response.json();
  if (!response.ok) {
    throw new Error(data?.message ?? "请求失败");
  }
  return data as AuthResponse;
}
