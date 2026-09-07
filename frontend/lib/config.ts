const defaultHost =
  typeof window !== "undefined" ? window.location.hostname : "localhost";

export const API_BASE =
  // 用 || 而不是 ??：CI 构建未传 NEXT_PUBLIC_API_BASE 时 ENV 是空字符串，
  // ?? 不会触发回退，导致请求打到前端自身端口拿回 HTML
  process.env.NEXT_PUBLIC_API_BASE || `http://${defaultHost}:8080/api/v1`;
