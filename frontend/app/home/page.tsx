"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import type { ReactNode } from "react";
import { useRouter } from "next/navigation";
import {
  ChevronRight,
  Clock,
  Database,
  FileText,
  LayoutGrid,
  Loader2,
  MessageCircle,
  MessagesSquare,
  Plus,
  Sparkles,
  Upload
} from "lucide-react";
import { logout, restoreSession } from "@/lib/auth-store";
import {
  createProject,
  listConversations,
  listDocuments,
  listProjects
} from "@/lib/api";
import type { Conversation, Document, Project, User } from "@/lib/types";
import Topbar from "@/components/Topbar";
import ProjectCreateDialog from "@/components/ProjectCreateDialog";
import StatusBadge from "@/components/StatusBadge";

function formatRelativeTime(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime();
  const minute = 60_000;
  const hour = 3_600_000;
  const day = 86_400_000;
  if (diff < minute) return "刚刚";
  if (diff < hour) return `${Math.floor(diff / minute)} 分钟前`;
  if (diff < day) return `${Math.floor(diff / hour)} 小时前`;
  if (diff < 2 * day) return "昨天";
  return `${Math.floor(diff / day)} 天前`;
}

function formatFileSize(bytes: number | null): string {
  if (!bytes) return "";
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function StatCard({
  label,
  value,
  meta,
  icon
}: {
  label: string;
  value: string;
  meta: string;
  icon: ReactNode;
}) {
  return (
    <div className="relative min-w-0 overflow-hidden rounded-[14px] border border-border bg-[rgba(6,12,26,0.72)] p-[16px] pb-[15px] transition hover:border-[rgba(80,140,255,0.3)]">
      <div className="pointer-events-none absolute -bottom-[30px] -right-[18px] h-[82px] w-[82px] rounded-full bg-[radial-gradient(circle,rgba(80,140,255,0.16),transparent_68%)]" />
      <div className="mb-3 flex items-center justify-between gap-[10px]">
        <span className="min-w-0 truncate text-[0.74rem] font-semibold text-[rgba(170,200,240,0.55)]">
          {label}
        </span>
        <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-lg border border-[rgba(80,140,255,0.24)] bg-[rgba(80,140,255,0.1)] text-[rgba(130,185,255,0.9)]">
          {icon}
        </span>
      </div>
      <div className="font-mono text-[1.32rem] font-semibold tracking-[-0.02em] text-[rgba(225,238,255,0.95)]">
        {value}
      </div>
      <div className="mt-1.5 truncate text-[0.7rem] text-[rgba(150,185,235,0.42)]">
        {meta}
      </div>
    </div>
  );
}

export default function HomePage() {
  const router = useRouter();
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  const [projects, setProjects] = useState<Project[]>([]);
  const [documents, setDocuments] = useState<Document[]>([]);
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [dialogOpen, setDialogOpen] = useState(false);

  const loadHomeData = useCallback(async () => {
    const items = await listProjects();
    setProjects(items);
    const [docsByProject, convsByProject] = await Promise.all([
      Promise.all(
        items.map((project) =>
          listDocuments(project.id).catch(() => [] as Document[])
        )
      ),
      Promise.all(
        items.map((project) =>
          listConversations(project.id).catch(() => [] as Conversation[])
        )
      )
    ]);
    setDocuments(
      docsByProject
        .flat()
        .sort(
          (a, b) =>
            new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()
        )
    );
    setConversations(
      convsByProject
        .flat()
        .sort(
          (a, b) =>
            new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime()
        )
    );
  }, []);

  useEffect(() => {
    let cancelled = false;
    async function init() {
      const restored = await restoreSession();
      if (!restored) {
        router.replace("/login");
        return;
      }
      if (cancelled) return;
      setUser(restored);
      try {
        await loadHomeData();
      } catch {
        // 数据加载失败时保留空态
      } finally {
        if (!cancelled) setLoading(false);
      }
    }
    init();
    return () => {
      cancelled = true;
    };
  }, [loadHomeData, router]);

  const now = useMemo(() => new Date(), []);
  const hour = now.getHours();
  const greeting =
    hour < 6 ? "夜深了" : hour < 12 ? "早上好" : hour < 14 ? "中午好" : hour < 18 ? "下午好" : "晚上好";
  const week = ["日", "一", "二", "三", "四", "五", "六"][now.getDay()];
  const dateLabel = `${now.getFullYear()}年${now.getMonth() + 1}月${now.getDate()}日 · 周${week}`;

  const projectNameOf = useCallback(
    (projectId: number | null) =>
      projects.find((p) => p.id === projectId)?.name ?? "",
    [projects]
  );

  const stats = useMemo(() => {
    const projectCount = projects.length;
    const docCount = documents.length;
    const convCount = conversations.length;
    return {
      projectCount,
      docCount,
      convCount,
      capacity: projectCount * 5
    };
  }, [projects, documents, conversations]);

  async function handleCreate(name: string, description?: string) {
    const project = await createProject(name, description);
    setProjects((current) => [project, ...current]);
    router.push(`/projects/${project.id}`);
  }

  async function handleLogout() {
    await logout();
    router.replace("/login");
  }

  function handleUploadShortcut() {
    if (projects.length > 0) {
      router.push(`/projects/${projects[0].id}`);
    } else {
      setDialogOpen(true);
    }
  }

  if (loading) {
    return (
      <main className="flex min-h-screen items-center justify-center bg-bg">
        <Loader2 className="h-6 w-6 animate-spin text-accent" />
      </main>
    );
  }

  if (!user) {
    return null;
  }

  return (
    <main className="grid-bg relative min-h-screen bg-gradient-to-b from-[#07101f] to-[#040810]">
      <Topbar
        user={user}
        activeNav="home"
        onLogout={handleLogout}
        onNewProject={() => setDialogOpen(true)}
      />

      <div className="relative z-[2] px-[clamp(16px,3vw,32px)] pb-20 pt-[clamp(24px,4vw,44px)]">
        <section className="mb-7 flex items-end justify-between gap-[18px]">
          <div>
            <h1 className="font-serif text-[clamp(1.6rem,3vw,2.2rem)] font-black leading-[1.34]">
              {greeting}，{user.username}
            </h1>
            <p className="mt-2 font-mono text-[0.72rem] tracking-[0.04em] text-[rgba(160,185,225,0.42)]">
              {dateLabel}
            </p>
          </div>
          <button
            type="button"
            onClick={() => setDialogOpen(true)}
            className="inline-flex h-[38px] items-center gap-2 rounded-[10px] border border-[rgba(80,150,255,0.35)] bg-gradient-to-br from-[#2860d6] to-[#3a7fff] px-4 text-[0.82rem] font-bold text-[rgba(230,242,255,0.96)] shadow-[0_4px_20px_rgba(40,100,255,0.28)] transition hover:brightness-110"
          >
            <Plus className="h-4 w-4" />
            <span>新建项目</span>
          </button>
        </section>

        <section className="mb-5 grid grid-cols-[repeat(auto-fit,minmax(200px,1fr))] gap-3">
          <StatCard
            label="项目总数"
            value={String(stats.projectCount)}
            meta="累计创建的工作区"
            icon={<LayoutGrid className="h-3.5 w-3.5" />}
          />
          <StatCard
            label="文档总数"
            value={String(stats.docCount)}
            meta={`跨 ${stats.projectCount} 个项目`}
            icon={<FileText className="h-3.5 w-3.5" />}
          />
          <StatCard
            label="已完成问答"
            value={String(stats.convCount)}
            meta="累计问答会话"
            icon={<MessagesSquare className="h-3.5 w-3.5" />}
          />
          <StatCard
            label="文档额度"
            value={`${stats.docCount} / ${stats.capacity}`}
            meta="每项目上限 5 个 PDF"
            icon={<Database className="h-3.5 w-3.5" />}
          />
        </section>

        {projects.length === 0 ? (
          <div className="flex flex-col items-center justify-center px-5 py-16 text-center">
            <div className="mb-4 flex h-[52px] w-[52px] items-center justify-center rounded-[14px] border border-[rgba(80,140,255,0.28)] bg-[rgba(80,140,255,0.09)] text-[rgba(130,185,255,0.9)] shadow-[0_0_30px_rgba(60,120,255,0.14)]">
              <Sparkles className="h-6 w-6" />
            </div>
            <h2 className="font-serif text-base font-bold">还没有项目</h2>
            <p className="mt-2 max-w-[380px] text-[0.78rem] leading-[1.7] text-[rgba(160,190,230,0.45)]">
              新建一个项目，上传 PDF 文档，开始向文档提问。
            </p>
            <button
              type="button"
              onClick={() => setDialogOpen(true)}
              className="mt-6 inline-flex h-[38px] items-center gap-2 rounded-[10px] border border-[rgba(80,150,255,0.35)] bg-gradient-to-br from-[#2860d6] to-[#3a7fff] px-4 text-[0.82rem] font-bold text-[rgba(230,242,255,0.96)] shadow-[0_4px_20px_rgba(40,100,255,0.28)] transition hover:brightness-110"
            >
              <Plus className="h-4 w-4" />
              <span>新建项目</span>
            </button>
          </div>
        ) : (
          <section className="grid grid-cols-1 gap-4 xl:grid-cols-[minmax(0,1.55fr)_minmax(0,1fr)]">
            {/* 最近文档 */}
            <div className="min-w-0 rounded-[14px] border border-border bg-[rgba(6,12,26,0.74)] p-[18px]">
              <div className="mb-[14px] flex items-center justify-between gap-3">
                <span className="inline-flex items-center gap-2 font-serif text-[0.95rem] font-bold text-[rgba(220,235,255,0.9)]">
                  <Clock className="h-4 w-4 text-[rgba(110,170,255,0.75)]" />
                  最近文档
                </span>
                <button
                  type="button"
                  onClick={() => router.push("/projects")}
                  className="inline-flex items-center gap-1 rounded-[7px] px-2 py-[5px] text-[0.72rem] font-semibold text-[rgba(110,165,255,0.72)] transition hover:bg-[rgba(80,140,255,0.1)] hover:text-[rgba(110,165,255,1)]"
                >
                  查看全部 <ChevronRight className="h-3.5 w-3.5" />
                </button>
              </div>

              {documents.length === 0 ? (
                <p className="px-3 py-8 text-center text-[0.78rem] text-[rgba(160,190,230,0.4)]">
                  暂无文档，进入项目上传 PDF 开始提问
                </p>
              ) : (
                <div className="space-y-[9px]">
                  {documents.slice(0, 3).map((document) => (
                    <button
                      key={document.id}
                      type="button"
                      onClick={() => router.push(`/projects/${document.projectId}`)}
                      className="flex w-full items-center gap-3 rounded-[10px] border border-[rgba(255,255,255,0.06)] bg-[rgba(255,255,255,0.022)] px-3 py-[11px] text-left transition hover:border-[rgba(80,140,255,0.38)] hover:bg-[rgba(80,140,255,0.07)]"
                    >
                      <span className="flex h-[38px] w-[38px] shrink-0 items-center justify-center rounded-[9px] border border-[rgba(80,140,255,0.3)] bg-gradient-to-br from-[#17365f] to-[#0d1f3d] text-[rgba(150,200,255,0.9)]">
                        <FileText className="h-4 w-4" />
                      </span>
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-[0.82rem] font-bold text-[rgba(220,235,255,0.9)]">
                          {document.filename}
                        </span>
                        <span className="mt-1 flex items-center gap-2 font-mono text-[0.65rem] text-[rgba(150,185,235,0.42)]">
                          {document.pageCount ? `${document.pageCount} 页` : ""}
                          {formatFileSize(document.fileSize)}
                          <span className="truncate">
                            {projectNameOf(document.projectId)}
                          </span>
                        </span>
                      </span>
                      <StatusBadge status={document.status} />
                      <ChevronRight className="h-4 w-4 shrink-0 text-[rgba(160,195,240,0.4)]" />
                    </button>
                  ))}
                </div>
              )}

              <div className="mt-4 grid grid-cols-3 gap-3">
                <button
                  type="button"
                  onClick={() => setDialogOpen(true)}
                  className="inline-flex min-w-0 items-center gap-[10px] rounded-[11px] border border-[rgba(255,255,255,0.07)] bg-[rgba(255,255,255,0.028)] px-[14px] py-[13px] text-[0.78rem] font-bold text-[rgba(205,225,250,0.82)] transition hover:border-[rgba(80,140,255,0.34)] hover:bg-[rgba(80,140,255,0.08)]"
                >
                  <Plus className="h-4 w-4 shrink-0 text-[rgba(110,170,255,0.8)]" />
                  <span className="truncate">新建项目</span>
                </button>
                <button
                  type="button"
                  onClick={() => router.push("/projects")}
                  className="inline-flex min-w-0 items-center gap-[10px] rounded-[11px] border border-[rgba(255,255,255,0.07)] bg-[rgba(255,255,255,0.028)] px-[14px] py-[13px] text-[0.78rem] font-bold text-[rgba(205,225,250,0.82)] transition hover:border-[rgba(80,140,255,0.34)] hover:bg-[rgba(80,140,255,0.08)]"
                >
                  <LayoutGrid className="h-4 w-4 shrink-0 text-[rgba(110,170,255,0.8)]" />
                  <span className="truncate">打开工作区</span>
                </button>
                <button
                  type="button"
                  onClick={handleUploadShortcut}
                  className="inline-flex min-w-0 items-center gap-[10px] rounded-[11px] border border-[rgba(255,255,255,0.07)] bg-[rgba(255,255,255,0.028)] px-[14px] py-[13px] text-[0.78rem] font-bold text-[rgba(205,225,250,0.82)] transition hover:border-[rgba(80,140,255,0.34)] hover:bg-[rgba(80,140,255,0.08)]"
                >
                  <Upload className="h-4 w-4 shrink-0 text-[rgba(110,170,255,0.8)]" />
                  <span className="truncate">上传 PDF</span>
                </button>
              </div>
            </div>

            {/* 最近问答 */}
            <div className="min-w-0 rounded-[14px] border border-border bg-[rgba(6,12,26,0.74)] p-[18px]">
              <div className="mb-[14px] flex items-center justify-between gap-3">
                <span className="inline-flex items-center gap-2 font-serif text-[0.95rem] font-bold text-[rgba(220,235,255,0.9)]">
                  <Sparkles className="h-4 w-4 text-[rgba(110,170,255,0.75)]" />
                  最近问答
                </span>
                <button
                  type="button"
                  onClick={() => router.push("/projects")}
                  className="inline-flex items-center gap-1 rounded-[7px] px-2 py-[5px] text-[0.72rem] font-semibold text-[rgba(110,165,255,0.72)] transition hover:bg-[rgba(80,140,255,0.1)] hover:text-[rgba(110,165,255,1)]"
                >
                  继续 <ChevronRight className="h-3.5 w-3.5" />
                </button>
              </div>

              {conversations.length === 0 ? (
                <p className="px-3 py-8 text-center text-[0.78rem] text-[rgba(160,190,230,0.4)]">
                  暂无问答，进入项目新建会话开始提问
                </p>
              ) : (
                <div>
                  {conversations.slice(0, 3).map((conversation) => (
                    <button
                      key={conversation.id}
                      type="button"
                      onClick={() =>
                        router.push(
                          `/projects/${conversation.projectId}?conv=${conversation.id}`
                        )
                      }
                      className="block w-full border-b border-[rgba(255,255,255,0.06)] py-[13px] text-left transition first:pt-0 last:border-b-0 last:pb-0 hover:opacity-90"
                    >
                      <div className="flex items-start gap-2 text-[0.78rem] font-semibold leading-[1.5] text-[rgba(215,232,255,0.86)]">
                        <MessageCircle className="mt-[2px] h-4 w-4 shrink-0 text-[rgba(100,160,255,0.7)]" />
                        <span className="min-w-0">
                          <span className="block truncate">
                            {conversation.title}
                          </span>
                          <span className="mt-[3px] block truncate font-mono text-[0.62rem] font-normal text-[rgba(150,185,235,0.4)]">
                            {projectNameOf(conversation.projectId)} ·{" "}
                            {formatRelativeTime(conversation.updatedAt)}
                          </span>
                        </span>
                      </div>
                    </button>
                  ))}
                </div>
              )}
            </div>
          </section>
        )}
      </div>

      <ProjectCreateDialog
        open={dialogOpen}
        onClose={() => setDialogOpen(false)}
        onCreate={handleCreate}
      />
    </main>
  );
}
