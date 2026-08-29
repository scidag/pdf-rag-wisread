const defaultHost =
  typeof window !== "undefined" ? window.location.hostname : "localhost";

export const API_BASE =
  process.env.NEXT_PUBLIC_API_BASE ?? `http://${defaultHost}:8080/api/v1`;
