"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { FileText, Loader2 } from "lucide-react";
import { login, register } from "@/lib/auth-store";
import type { User } from "@/lib/types";

interface AuthPanelProps {
  mode: "login" | "register";
}

export default function AuthPanel({ mode }: AuthPanelProps) {
  const router = useRouter();
  const [username, setUsername] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    setLoading(true);
    try {
      const user: User =
        mode === "login"
          ? await login(email, password)
          : await register(username, email, password);
      router.push("/workspace");
    } catch (err) {
      setError(err instanceof Error ? err.message : "请求失败");
    } finally {
      setLoading(false);
    }
  }

  return (
    <main className="min-h-screen flex items-center justify-center bg-slate-100 px-4">
      <div className="w-full max-w-md rounded-lg border border-slate-200 bg-white p-8 shadow-sm">
        <div className="mb-8 flex items-center gap-3">
          <div className="flex h-11 w-11 items-center justify-center rounded-lg bg-emerald-600 text-white">
            <FileText className="h-5 w-5" />
          </div>
          <div>
            <h1 className="text-xl font-semibold text-slate-900">智阅 Wisread</h1>
            <p className="text-sm text-slate-500">
              {mode === "login" ? "登录你的工作区" : "创建你的工作区"}
            </p>
          </div>
        </div>

        <form className="space-y-4" onSubmit={handleSubmit}>
          {mode === "register" && (
            <div>
              <label
                htmlFor="username"
                className="mb-1 block text-sm font-medium text-slate-700"
              >
                用户名
              </label>
              <input
                id="username"
                value={username}
                onChange={(event) => setUsername(event.target.value)}
                required
                className="w-full rounded-md border border-slate-300 px-3 py-2 text-sm outline-none focus:border-emerald-500 focus:ring-2 focus:ring-emerald-100"
              />
            </div>
          )}

          <div>
            <label
              htmlFor="email"
              className="mb-1 block text-sm font-medium text-slate-700"
            >
              邮箱
            </label>
            <input
              id="email"
              type="email"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              required
              className="w-full rounded-md border border-slate-300 px-3 py-2 text-sm outline-none focus:border-emerald-500 focus:ring-2 focus:ring-emerald-100"
            />
          </div>

          <div>
            <label
              htmlFor="password"
              className="mb-1 block text-sm font-medium text-slate-700"
            >
              密码
            </label>
            <input
              id="password"
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              required
              minLength={8}
              className="w-full rounded-md border border-slate-300 px-3 py-2 text-sm outline-none focus:border-emerald-500 focus:ring-2 focus:ring-emerald-100"
            />
          </div>

          {error && (
            <p className="rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">
              {error}
            </p>
          )}

          <button
            type="submit"
            disabled={loading}
            className="flex w-full items-center justify-center gap-2 rounded-md bg-emerald-600 px-4 py-2.5 text-sm font-medium text-white transition hover:bg-emerald-700 disabled:opacity-60"
          >
            {loading && <Loader2 className="h-4 w-4 animate-spin" />}
            {mode === "login" ? "登录" : "注册"}
          </button>
        </form>

        <p className="mt-6 text-center text-sm text-slate-500">
          {mode === "login" ? (
            <>
              还没有账号？{" "}
              <Link className="font-medium text-emerald-600" href="/register">
                去注册
              </Link>
            </>
          ) : (
            <>
              已有账号？{" "}
              <Link className="font-medium text-emerald-600" href="/login">
                去登录
              </Link>
            </>
          )}
        </p>
      </div>
    </main>
  );
}
