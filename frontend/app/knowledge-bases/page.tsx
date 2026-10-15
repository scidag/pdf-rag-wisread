"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  Archive,
  ArchiveRestore,
  ArrowRight,
  Library,
  Loader2,
  Plus,
  RotateCcw,
  Trash2
} from "lucide-react";
import { logout, restoreSession } from "@/lib/auth-store";
import {
  archiveKnowledgeBase,
  createKnowledgeBase,
  deleteKnowledgeBase,
  deleteKnowledgeBases,
  listDeletedKnowledgeBases,
  listKnowledgeBases,
  publishKnowledgeBase,
  restoreKnowledgeBase,
  unarchiveKnowledgeBase
} from "@/lib/knowledge-base";
import type { KnowledgeBase } from "@/lib/knowledge-base";
import type { User } from "@/lib/types";
import Topbar from "@/components/Topbar";
import KnowledgeStatusBadge from "@/components/KnowledgeStatusBadge";
import KnowledgeBaseCreateDialog from "@/components/KnowledgeBaseCreateDialog";

const MAX_BATCH_DELETE = 100; // 与后端限制保持一致：单次最多删除 100 个

export default function KnowledgeBasesPage() {
  const router = useRouter();
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  const [view, setView] = useState<"list" | "trash">("list");
  const [kbs, setKbs] = useState<KnowledgeBase[]>([]);
  const [trashKbs, setTrashKbs] = useState<KnowledgeBase[]>([]);
  const [trashLoading, setTrashLoading] = useState(false);
  const [selectedIds, setSelectedIds] = useState<number[]>([]);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const loadKbs = useCallback(async () => {
    const items = await listKnowledgeBases();
    setKbs(items);
  }, []);

  const loadTrash = useCallback(async () => {
    setTrashLoading(true);
    try {
      const items = await listDeletedKnowledgeBases();
      setTrashKbs(items);
    } finally {
      setTrashLoading(false);
    }
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
        await loadKbs();
      } finally {
        if (!cancelled) setLoading(false);
      }
    }
    init();
    return () => {
      cancelled = true;
    };
  }, [loadKbs, router]);

  function switchView(target: "list" | "trash") {
    setView(target);
    setSelectedIds([]);
    setActionError(null);
    if (target === "trash") {
      loadTrash().catch(() => setActionError("回收站加载失败，请重试"));
    }
  }

  async function handleCreate(name: string, description?: string) {
    const kb = await createKnowledgeBase(name, description);
    setKbs((current) => [kb, ...current]);
  }

  async function runKbAction(kbId: number, action: () => Promise<KnowledgeBase>) {
    setBusyId(kbId);
    setActionError(null);
    try {
      const updated = await action();
      setKbs((current) => current.map((item) => (item.id === kbId ? updated : item)));
    } catch (err) {
      setActionError(err instanceof Error ? err.message : "操作失败，请稍后重试");
    } finally {
      setBusyId(null);
    }
  }

  async function handleDeleteOne(kb: KnowledgeBase) {
    if (!window.confirm(`确定删除知识库「${kb.name}」？删除后将移入回收站，可随时恢复。`)) {
      return;
    }
    setBusyId(kb.id);
    setActionError(null);
    try {
      await deleteKnowledgeBase(kb.id);
      setKbs((current) => current.filter((item) => item.id !== kb.id));
      setSelectedIds((current) => current.filter((id) => id !== kb.id));
    } catch (err) {
      setActionError(err instanceof Error ? err.message : "删除失败，请重试");
    } finally {
      setBusyId(null);
    }
  }

  function toggleSelectAll() {
    setSelectedIds(
      selectedIds.length === kbs.length ? [] : kbs.map((item) => item.id)
    );
  }

  function toggleSelect(id: number) {
    setSelectedIds((current) =>
      current.includes(id)
        ? current.filter((item) => item !== id)
        : [...current, id]
    );
  }

  async function handleBatchDelete() {
    if (selectedIds.length > MAX_BATCH_DELETE) {
      window.alert(`单次最多删除 ${MAX_BATCH_DELETE} 个知识库，请分批操作`);
      return;
    }
    if (!window.confirm(`确定删除选中的 ${selectedIds.length} 个知识库？删除后将移入回收站，可随时恢复。`)) {
      return;
    }
    setActionError(null);
    try {
      await deleteKnowledgeBases(selectedIds);
      const idSet = new Set(selectedIds);
      setKbs((current) => current.filter((item) => !idSet.has(item.id)));
      setSelectedIds([]);
    } catch (err) {
      setActionError(err instanceof Error ? err.message : "删除失败，请重试");
    }
  }

  async function handleRestore(kb: KnowledgeBase) {
    setBusyId(kb.id);
    setActionError(null);
    try {
      await restoreKnowledgeBase(kb.id);
      setTrashKbs((current) => current.filter((item) => item.id !== kb.id));
    } catch (err) {
      setActionError(err instanceof Error ? err.message : "恢复失败，请重试");
    } finally {
      setBusyId(null);
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

  const items = view === "list" ? kbs : trashKbs;
  const empty = view === "list" ? kbs.length === 0 : trashKbs.length === 0;
  const overLimit = selectedIds.length > MAX_BATCH_DELETE;

  return (
    <main className="grid-bg relative min-h-screen bg-gradient-to-b from-[#07101f] to-[#040810]">
      <Topbar
        user={user}
        activeNav="knowledge"
        onLogout={handleLogout}
        primaryLabel="新建知识库"
        onPrimary={() => setDialogOpen(true)}
      />

      <div className="relative z-[2] w-full px-[clamp(16px,3vw,32px)] pb-20 pt-[clamp(24px,4vw,44px)]">
        <section className="mb-7 flex flex-wrap items-end justify-between gap-[18px]">
          <div className="flex items-center gap-[14px]">
            <div className="flex h-[38px] w-[38px] shrink-0 items-center justify-center rounded-[10px] border border-[rgba(80,150,255,0.28)] bg-[rgba(80,140,255,0.07)] text-[rgba(130,185,255,0.9)]">
              <Library className="h-5 w-5" />
            </div>
            <h1 className="font-serif text-[clamp(1.6rem,3vw,2.2rem)] font-black leading-[1.34]">
              {view === "list" ? "知识库" : "回收站"}
            </h1>
            <p className="mt-2 font-mono text-[0.72rem] tracking-[0.04em] text-[rgba(160,185,225,0.55)]">
              {view === "list"
                ? `共 ${kbs.length} 个知识库 · 上传文档构建可检索的向量资产`
                : `共 ${trashKbs.length} 个已删除知识库 · 可随时恢复`}
            </p>
          </div>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => switchView(view === "list" ? "trash" : "list")}
              className={`inline-flex h-[38px] items-center gap-2 rounded-[10px] border px-4 text-[0.8rem] font-semibold transition ${
                view === "trash"
                  ? "border-[rgba(80,150,255,0.35)] bg-[rgba(58,127,255,0.1)] text-[#8ab4ff]"
                  : "border-[rgba(255,255,255,0.1)] bg-[rgba(255,255,255,0.03)] text-[rgba(200,220,250,0.78)] hover:border-[rgba(58,127,255,0.4)] hover:bg-[rgba(58,127,255,0.08)]"
              }`}
            >
              <Trash2 className="h-4 w-4" />
              {view === "trash" ? "返回列表" : "回收站"}
            </button>
          </div>
        </section>

        {actionError && (
          <p
            role="alert"
            className="mb-5 rounded-[10px] border border-[rgba(255,80,100,0.3)] bg-[rgba(255,80,100,0.08)] px-4 py-3 text-[0.8rem] text-[rgba(255,175,190,0.95)]"
          >
            {actionError}
          </p>
        )}

        {view === "list" && selectedIds.length > 0 && (
          <div className="mb-5 flex flex-wrap items-center justify-between gap-3 rounded-[10px] border border-[rgba(255,80,100,0.3)] bg-[rgba(255,80,100,0.08)] px-4 py-3">
            <span className="text-[0.8rem] font-semibold text-[rgba(255,175,190,0.95)]">
              已选择 {selectedIds.length} 个知识库
              {overLimit ? " · 单次最多删除 100 个" : ""}
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
                onClick={handleBatchDelete}
                className="inline-flex h-[34px] items-center gap-2 rounded-[8px] border border-[rgba(255,80,100,0.4)] bg-[rgba(255,80,100,0.14)] px-3 text-[0.76rem] font-bold text-[rgba(255,175,190,0.95)] transition hover:bg-[rgba(255,80,100,0.22)]"
              >
                <Trash2 className="h-3.5 w-3.5" />
                删除选中
              </button>
            </div>
          </div>
        )}

        {view === "list" && kbs.length > 0 && (
          <label className="mb-4 flex w-fit cursor-pointer items-center gap-2 text-[0.76rem] font-semibold text-[rgba(170,200,240,0.62)]">
            <input
              type="checkbox"
              checked={selectedIds.length === kbs.length && kbs.length > 0}
              onChange={toggleSelectAll}
              aria-label="全选知识库"
              className="h-4 w-4 cursor-pointer accent-[#3a7fff]"
            />
            全选
          </label>
        )}

        {empty ? (
          trashLoading ? (
            <div className="flex justify-center py-20">
              <Loader2 className="h-6 w-6 animate-spin text-accent" />
            </div>
          ) : (
            <div className="flex flex-col items-center justify-center px-5 py-20 text-center">
              <div className="mb-4 flex h-[52px] w-[52px] items-center justify-center rounded-[14px] border border-[rgba(80,140,255,0.28)] bg-[rgba(80,140,255,0.09)] text-[rgba(130,185,255,0.9)] shadow-[0_0_30px_rgba(60,120,255,0.14)]">
                {view === "list" ? <Plus className="h-6 w-6" /> : <Trash2 className="h-6 w-6" />}
              </div>
              <h2 className="font-serif text-base font-bold">
                {view === "list" ? "还没有知识库" : "回收站为空"}
              </h2>
              <p className="mt-2 max-w-[380px] text-[0.78rem] leading-[1.7] text-[rgba(160,190,230,0.55)]">
                {view === "list"
                  ? "新建一个知识库，上传文档并完成解析，让问答引用可以逐条溯源。"
                  : "删除的知识库会进入回收站，可在此恢复。"}
              </p>
            </div>
          )
        ) : (
          <div className="grid grid-cols-[repeat(auto-fill,minmax(340px,1fr))] gap-4">
            {items.map((kb) => (
              <article
                key={kb.id}
                className="group flex flex-col rounded-[14px] border border-border bg-panel p-[22px_24px] backdrop-blur-[8px] transition duration-300 hover:-translate-y-[2px] hover:border-[rgba(58,127,255,0.38)] hover:shadow-[0_0_0_1px_rgba(58,127,255,0.1),0_10px_36px_rgba(0,0,0,0.42)]"
              >
                <div className="flex items-center justify-between gap-3">
                  <h2 className="truncate text-[1.02rem] font-bold text-[rgba(225,238,255,0.94)]" title={kb.name}>
                    {kb.name}
                  </h2>
                  <KnowledgeStatusBadge status={kb.status} kind="kb" />
                </div>
                <p className="mt-2 line-clamp-2 min-h-[2.6em] text-[0.78rem] leading-[1.65] text-[rgba(170,200,240,0.62)]">
                  {kb.description || "暂无描述"}
                </p>
                <div className="mt-4 flex items-center justify-between gap-3 border-t border-border pt-[14px]">
                  <div className="flex flex-wrap gap-4 font-mono text-[0.72rem] text-[rgba(160,185,225,0.6)]">
                    <span>
                      文档 <b className="font-semibold text-[rgba(200,220,250,0.85)]">{kb.documentCount}</b>
                    </span>
                    <span>
                      分块 <b className="font-semibold text-[rgba(200,220,250,0.85)]">{kb.chunkCount.toLocaleString()}</b>
                    </span>
                  </div>
                  {view === "list" ? (
                    <div className="flex items-center gap-1.5">
                      {kb.status === "DRAFT" && (
                        <button
                          type="button"
                          disabled={busyId === kb.id}
                          onClick={() => runKbAction(kb.id, () => publishKnowledgeBase(kb.id))}
                          className="inline-flex items-center gap-1 rounded-[8px] border border-[rgba(58,127,255,0.4)] bg-[rgba(58,127,255,0.1)] px-[10px] py-[5px] text-[0.74rem] font-semibold text-[#8ab4ff] transition hover:bg-[rgba(58,127,255,0.2)] disabled:opacity-50"
                        >
                          {busyId === kb.id ? (
                            <Loader2 className="h-3 w-3 animate-spin" />
                          ) : null}
                          发布
                        </button>
                      )}
                      {(kb.status === "ACTIVE" || kb.status === "DRAFT") && (
                        <button
                          type="button"
                          disabled={busyId === kb.id}
                          onClick={() => runKbAction(kb.id, () => archiveKnowledgeBase(kb.id))}
                          aria-label={`归档 ${kb.name}`}
                          title="归档"
                          className="flex h-[28px] w-[28px] items-center justify-center rounded-[8px] text-[rgba(180,205,240,0.62)] transition hover:border hover:border-[rgba(255,255,255,0.12)] hover:bg-[rgba(255,255,255,0.04)] hover:text-[rgba(230,240,255,0.95)] disabled:opacity-50"
                        >
                          <Archive className="h-3.5 w-3.5" />
                        </button>
                      )}
                      {kb.status === "ARCHIVED" && (
                        <button
                          type="button"
                          disabled={busyId === kb.id}
                          onClick={() => runKbAction(kb.id, () => unarchiveKnowledgeBase(kb.id))}
                          aria-label={`取消归档 ${kb.name}`}
                          title="取消归档"
                          className="flex h-[28px] w-[28px] items-center justify-center rounded-[8px] text-[rgba(180,205,240,0.62)] transition hover:border hover:border-[rgba(255,255,255,0.12)] hover:bg-[rgba(255,255,255,0.04)] hover:text-[rgba(230,240,255,0.95)] disabled:opacity-50"
                        >
                          <ArchiveRestore className="h-3.5 w-3.5" />
                        </button>
                      )}
                      <button
                        type="button"
                        disabled={busyId === kb.id}
                        onClick={() => handleDeleteOne(kb)}
                        aria-label={`删除 ${kb.name}`}
                        title="删除"
                        className="flex h-[28px] w-[28px] items-center justify-center rounded-[8px] text-[rgba(180,205,240,0.62)] transition hover:border hover:border-[rgba(255,122,138,0.35)] hover:bg-[rgba(255,122,138,0.07)] hover:text-[#ff7a8a] disabled:opacity-50"
                      >
                        <Trash2 className="h-3.5 w-3.5" />
                      </button>
                      <button
                        type="button"
                        onClick={() => router.push(`/knowledge-bases/${kb.id}`)}
                        className="inline-flex items-center gap-[6px] rounded-[8px] px-2.5 py-[5px] text-[0.76rem] font-semibold text-[#8ab4ff] transition hover:border hover:border-[rgba(58,127,255,0.4)] hover:bg-[rgba(58,127,255,0.08)]"
                      >
                        进入
                        <ArrowRight className="h-3 w-3" />
                      </button>
                      <input
                        type="checkbox"
                        checked={selectedIds.includes(kb.id)}
                        onChange={() => toggleSelect(kb.id)}
                        aria-label={`选择 ${kb.name}`}
                        className="ml-1 h-3.5 w-3.5 cursor-pointer accent-[#3a7fff]"
                      />
                    </div>
                  ) : (
                    <button
                      type="button"
                      disabled={busyId === kb.id}
                      onClick={() => handleRestore(kb)}
                      className="inline-flex items-center gap-1.5 rounded-[8px] border border-[rgba(58,127,255,0.4)] bg-[rgba(58,127,255,0.1)] px-[10px] py-[5px] text-[0.74rem] font-semibold text-[#8ab4ff] transition hover:bg-[rgba(58,127,255,0.2)] disabled:opacity-50"
                    >
                      {busyId === kb.id ? (
                        <Loader2 className="h-3 w-3 animate-spin" />
                      ) : (
                        <RotateCcw className="h-3 w-3" />
                      )}
                      恢复
                    </button>
                  )}
                </div>
              </article>
            ))}
          </div>
        )}
      </div>

      <KnowledgeBaseCreateDialog
        open={dialogOpen}
        onClose={() => setDialogOpen(false)}
        onCreate={handleCreate}
      />
    </main>
  );
}
