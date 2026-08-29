"use client";

import { useRouter } from "next/navigation";
import { Bell, Home, LayoutGrid, LogOut, Plus } from "lucide-react";
import type { User } from "@/lib/types";
import LogoMark from "./LogoMark";

interface TopbarProps {
  user: User;
  activeNav?: "home" | "projects";
  onLogout?: () => void;
  onNewProject?: () => void;
  primaryLabel?: string;
  onPrimary?: () => void;
  primaryIcon?: "plus" | "upload";
}

export default function Topbar({
  user,
  activeNav,
  onLogout,
  onNewProject,
  primaryLabel = "新建项目",
  onPrimary,
  primaryIcon = "plus"
}: TopbarProps) {
  const router = useRouter();

  const navItemCls = (active: boolean) =>
    `inline-flex items-center gap-[6px] rounded-[8px] border px-[15px] py-[7px] text-[0.8rem] font-bold transition ${
      active
        ? "border-[rgba(255,255,255,0.09)] bg-[rgba(255,255,255,0.07)] text-[rgba(220,235,255,0.92)] shadow-[0_1px_4px_rgba(0,0,0,0.3)]"
        : "border-transparent bg-transparent text-[rgba(180,200,230,0.45)] hover:text-[rgba(220,235,255,0.75)]"
    }`;

  return (
    <header className="relative z-[5] flex h-16 items-center gap-[18px] border-b border-border bg-[rgba(6,12,26,0.78)] px-[clamp(16px,3vw,32px)] backdrop-blur-xl">
      <button
        type="button"
        onClick={() => router.push("/home")}
        className="flex items-center gap-[10px]"
        aria-label="返回首页"
      >
        <LogoMark />
        <div className="text-left">
          <span className="font-serif text-[1.04rem] font-black tracking-[0.02em] text-[rgba(220,235,255,0.94)]">
            智阅
          </span>
          <span className="mt-px block font-mono text-[0.58rem] font-medium uppercase tracking-[0.16em] text-[rgba(150,185,235,0.5)]">
            Wisread
          </span>
        </div>
      </button>

      <div className="ml-3 flex gap-[3px] rounded-[10px] border border-[rgba(255,255,255,0.06)] bg-[rgba(255,255,255,0.035)] p-[3px]">
        <button
          type="button"
          onClick={() => router.push("/home")}
          className={navItemCls(activeNav === "home")}
        >
          <Home className="h-3.5 w-3.5" />
          <span className="hidden md:inline">首页</span>
        </button>
        <button
          type="button"
          onClick={() => router.push("/projects")}
          className={navItemCls(activeNav === "projects")}
        >
          <LayoutGrid className="h-3.5 w-3.5" />
          <span className="hidden md:inline">项目</span>
        </button>
      </div>

      <div className="ml-auto flex items-center gap-[10px]">
        <button
          type="button"
          aria-label="通知"
          className="hidden h-9 w-9 items-center justify-center rounded-[9px] border border-[rgba(255,255,255,0.07)] bg-[rgba(255,255,255,0.03)] text-[rgba(180,205,240,0.62)] transition hover:bg-[rgba(255,255,255,0.07)] hover:text-[rgba(230,240,255,0.95)] md:inline-flex"
        >
          <Bell className="h-4 w-4" />
        </button>
        <button
          type="button"
          onClick={onPrimary ?? onNewProject}
          className="inline-flex h-[38px] items-center gap-2 rounded-[10px] border border-[rgba(80,150,255,0.35)] bg-gradient-to-br from-[#2860d6] to-[#3a7fff] px-4 text-[0.82rem] font-bold text-[rgba(230,242,255,0.96)] shadow-[0_4px_20px_rgba(40,100,255,0.28)] transition hover:brightness-110"
        >
          {primaryIcon === "plus" ? <Plus className="h-4 w-4" /> : null}
          <span>{primaryLabel}</span>
        </button>
        <div
          className="flex h-[34px] w-[34px] shrink-0 items-center justify-center rounded-full border border-[rgba(120,180,255,0.32)] bg-gradient-to-br from-[#1d3f7a] to-[#102a56] text-[0.78rem] font-bold text-[rgba(220,235,255,0.95)] shadow-[0_0_16px_rgba(60,120,255,0.2)]"
          title={user.username}
        >
          {user.username.charAt(0).toUpperCase()}
        </div>
        {onLogout && (
          <button
            type="button"
            onClick={onLogout}
            aria-label="退出登录"
            className="flex h-9 w-9 items-center justify-center rounded-[9px] border border-[rgba(255,255,255,0.07)] bg-[rgba(255,255,255,0.03)] text-[rgba(180,205,240,0.62)] transition hover:bg-[rgba(255,255,255,0.07)] hover:text-[rgba(230,240,255,0.95)]"
          >
            <LogOut className="h-4 w-4" />
          </button>
        )}
      </div>
    </header>
  );
}
