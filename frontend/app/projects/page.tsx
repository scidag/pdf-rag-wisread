"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { Loader2, Plus, Trash2 } from "lucide-react";
import { logout, restoreSession } from "@/lib/auth-store";
import {
  createProject,
  deleteProjects,
  listProjects
} from "@/lib/api";
import type { Project, User } from "@/lib/types";
import Topbar from "@/components/Topbar";
import ProjectCard from "@/components/ProjectCard";
import ProjectCreateDialog from "@/components/ProjectCreateDialog";

export default function ProjectsPage() {
  const router = useRouter();
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  const [projects, setProjects] = useState<Project[]>([]);
  const [selectedIds, setSelectedIds] = useState<number[]>([]);
  const [dialogOpen, setDialogOpen] = useState(false);

  const loadProjects = useCallback(async () => {
    const items = await listProjects();
    setProjects(items);
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
        await loadProjects();
      } finally {
        setLoading(false);
      }
    }
    init();
    return () => {
      cancelled = true;
    };
  }, [loadProjects, router]);

  async function handleCreate(name: string, description?: string) {
    const project = await createProject(name, description);
    setProjects((current) => [project, ...current]);
  }

  function toggleSelectAll() {
    setSelectedIds(
      selectedIds.length === projects.length ? [] : projects.map((p) => p.id)
    );
  }

  function toggleSelect(id: number) {
    setSelectedIds((current) =>
      current.includes(id)
        ? current.filter((item) => item !== id)
        : [...current, id]
    );
  }

  async function handleDelete(ids: number[]) {
    const label = ids.length === 1 ? "这个项目" : `选中的 ${ids.length} 个项目`;
    if (!window.confirm(`确定删除${label}？删除后项目将移入回收站，可随时恢复。`)) {
      return;
    }
    try {
      await deleteProjects(ids);
      const idSet = new Set(ids);
      setProjects((current) => current.filter((p) => !idSet.has(p.id)));
      setSelectedIds([]);
    } catch {
      window.alert("删除失败，请重试");
    }
  }

  async function handleLogout() {
    await logout();
    router.replace("/login");
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
        activeNav="projects"
        onLogout={handleLogout}
        onNewProject={() => setDialogOpen(true)}
      />

      <div className="relative z-[2] w-full px-[clamp(16px,3vw,32px)] pb-20 pt-[clamp(24px,4vw,44px)]">
        <section className="mb-7 flex items-end justify-between gap-[18px]">
          <div className="flex items-center gap-[14px]">
            <label className="flex h-[38px] w-[38px] shrink-0 cursor-pointer items-center justify-center rounded-[10px] border border-[rgba(80,150,255,0.28)] bg-[rgba(80,140,255,0.07)] transition hover:bg-[rgba(80,140,255,0.12)]">
              <input
                type="checkbox"
                checked={projects.length > 0 && selectedIds.length === projects.length}
                onChange={toggleSelectAll}
                aria-label="全选项目"
                className="h-4 w-4 cursor-pointer accent-[#3a7fff]"
              />
            </label>
            <h1 className="font-serif text-[clamp(1.6rem,3vw,2.2rem)] font-black leading-[1.34]">
              我的项目
            </h1>
            <p className="mt-2 font-mono text-[0.72rem] tracking-[0.04em] text-[rgba(160,185,225,0.42)]">
              共 {projects.length} 个项目 · 点击项目进入文档上传与对话
            </p>
          </div>
        </section>

        {selectedIds.length > 0 ? (
          <div className="mb-5 flex items-center justify-between gap-3 rounded-[10px] border border-[rgba(255,80,100,0.3)] bg-[rgba(255,80,100,0.08)] px-4 py-3">
            <span className="text-[0.8rem] font-semibold text-[rgba(255,175,190,0.95)]">
              已选择 {selectedIds.length} 个项目
            </span>
            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={() => setSelectedIds([])}
                className="h-[34px] rounded-[8px] border border-[rgba(255,255,255,0.08)] bg-[rgba(255,255,255,0.03)] px-3 text-[0.76rem] font-semibold text-[rgba(200,220,250,0.72)] transition hover:bg-[rgba(255,255,255,0.065)]"
              >
                取消选择
              </button>
              <button
                type="button"
                onClick={() => handleDelete(selectedIds)}
                className="inline-flex h-[34px] items-center gap-2 rounded-[8px] border border-[rgba(255,80,100,0.4)] bg-[rgba(255,80,100,0.14)] px-3 text-[0.76rem] font-bold text-[rgba(255,175,190,0.95)] transition hover:bg-[rgba(255,80,100,0.22)]"
              >
                <Trash2 className="h-3.5 w-3.5" />
                删除
              </button>
            </div>
          </div>
        ) : null}

        {projects.length === 0 ? (
            <div className="flex flex-col items-center justify-center px-5 py-20 text-center">
              <div className="mb-4 flex h-[52px] w-[52px] items-center justify-center rounded-[14px] border border-[rgba(80,140,255,0.28)] bg-[rgba(80,140,255,0.09)] text-[rgba(130,185,255,0.9)] shadow-[0_0_30px_rgba(60,120,255,0.14)]">
                <Plus className="h-6 w-6" />
              </div>
              <h2 className="font-serif text-base font-bold">还没有项目</h2>
              <p className="mt-2 max-w-[380px] text-[0.78rem] leading-[1.7] text-[rgba(160,190,230,0.45)]">
                新建一个项目，上传 PDF 文档，开始向文档提问。
              </p>
            </div>
          ) : (
            <div className="grid grid-cols-[repeat(auto-fill,minmax(280px,1fr))] gap-4">
              {projects.map((project) => (
                <ProjectCard
                  key={project.id}
                  project={project}
                  selected={selectedIds.includes(project.id)}
                  onToggleSelect={() => toggleSelect(project.id)}
                  onDelete={() => handleDelete([project.id])}
                  onClick={() => router.push(`/projects/${project.id}`)}
                />
              ))}
            </div>
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
