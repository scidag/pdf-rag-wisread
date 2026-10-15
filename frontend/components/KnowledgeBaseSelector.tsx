"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import {
  BookOpen,
  Check,
  ChevronDown,
  Library,
  Loader2,
  RefreshCw
} from "lucide-react";
import { listKnowledgeBases } from "@/lib/knowledge-base";
import type { KnowledgeBase } from "@/lib/knowledge-base";

const STATUS_META: Record<string, { label: string; badgeClass: string }> = {
  DRAFT: {
    label: "草稿",
    badgeClass:
      "border-[rgba(150,180,225,0.3)] bg-[rgba(150,180,225,0.09)] text-[rgba(175,205,245,0.9)]"
  },
  PROCESSING: {
    label: "处理中",
    badgeClass:
      "border-[rgba(245,177,78,0.34)] bg-[rgba(245,177,78,0.09)] text-[rgba(250,205,135,0.98)]"
  },
  ACTIVE: {
    label: "可用",
    badgeClass:
      "border-[rgba(74,222,128,0.32)] bg-[rgba(74,222,128,0.09)] text-[rgba(125,232,165,0.98)]"
  },
  ARCHIVED: {
    label: "归档",
    badgeClass:
      "border-[rgba(255,255,255,0.14)] bg-[rgba(255,255,255,0.05)] text-[rgba(185,210,245,0.62)]"
  }
};

function StatusBadge({ status }: { status: string }) {
  const meta = STATUS_META[status] ?? {
    label: status,
    badgeClass:
      "border-[rgba(150,180,225,0.3)] bg-[rgba(150,180,225,0.09)] text-[rgba(175,205,245,0.9)]"
  };
  return (
    <span
      className={`inline-flex shrink-0 items-center gap-[5px] rounded-full border px-[8px] py-[1.5px] font-mono text-[0.6rem] font-semibold tracking-[0.06em] ${meta.badgeClass}`}
    >
      <span className="h-[5px] w-[5px] rounded-full bg-current" />
      {meta.label}
    </span>
  );
}

interface KnowledgeBaseSelectorProps {
  /** 当前生效的知识库 ID（会话绑定或新建会话前的暂选，null 表示未选择） */
  knowledgeBaseId: number | null;
  /** 暂选模式（尚无会话）：选择仅记录在前端，新建会话时随请求携带 */
  pending?: boolean;
  /** 选择/解绑回调；已有会话时由父级调用 PATCH 接口，失败时抛错 */
  onChange: (knowledgeBaseId: number | null) => Promise<void>;
}

export default function KnowledgeBaseSelector({
  knowledgeBaseId,
  pending = false,
  onChange
}: KnowledgeBaseSelectorProps) {
  const [open, setOpen] = useState(false);
  const [kbs, setKbs] = useState<KnowledgeBase[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const rootRef = useRef<HTMLDivElement | null>(null);

  const loadList = useCallback(async () => {
    setLoading(true);
    setLoadError(null);
    try {
      setKbs(await listKnowledgeBases());
    } catch (err) {
      setLoadError(
        err instanceof Error ? err.message : "知识库列表加载失败，请稍后重试"
      );
    } finally {
      setLoading(false);
    }
  }, []);

  // 首次展开时加载列表；加载失败后再次展开自动重试
  useEffect(() => {
    if (open && kbs === null && !loading) {
      loadList();
    }
  }, [open, kbs, loading, loadList]);

  // 已选定知识库时提前加载列表，用于在按钮上回显库名与状态徽牌（含已归档）
  useEffect(() => {
    if (knowledgeBaseId !== null && kbs === null && !loading && !loadError) {
      loadList();
    }
  }, [knowledgeBaseId, kbs, loading, loadError, loadList]);

  useEffect(() => {
    if (!open) return;
    function handlePointerDown(event: MouseEvent) {
      if (rootRef.current && !rootRef.current.contains(event.target as Node)) {
        setOpen(false);
      }
    }
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        setOpen(false);
      }
    }
    document.addEventListener("mousedown", handlePointerDown);
    document.addEventListener("keydown", handleKeyDown);
    return () => {
      document.removeEventListener("mousedown", handlePointerDown);
      document.removeEventListener("keydown", handleKeyDown);
    };
  }, [open]);

  const current = kbs?.find((kb) => kb.id === knowledgeBaseId) ?? null;
  const selectable = (kbs ?? []).filter((kb) => kb.status !== "ARCHIVED");

  const triggerLabel =
    knowledgeBaseId === null
      ? "项目文档检索"
      : current?.name ?? `知识库 #${knowledgeBaseId}`;

  const hint =
    knowledgeBaseId === null
      ? "未选择知识库，将使用项目文档回答"
      : pending
        ? "已暂选该知识库，新建会话时将绑定"
        : "已绑定知识库，AI 将从该库检索并返回引用";

  async function handleSelect(kbId: number | null) {
    if (submitting) return;
    if (kbId === knowledgeBaseId) {
      setOpen(false);
      return;
    }
    setSubmitting(true);
    setActionError(null);
    try {
      await onChange(kbId);
      setOpen(false);
    } catch (err) {
      setActionError(
        err instanceof Error ? err.message : "切换知识库失败，请稍后重试"
      );
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div ref={rootRef} className="flex min-w-0 flex-col gap-[4px]">
      <div className="flex min-w-0 items-center gap-[10px]">
        <div className="relative shrink-0">
          <button
            type="button"
            disabled={submitting}
            onClick={() => setOpen((value) => !value)}
            aria-haspopup="listbox"
            aria-expanded={open}
            className={`inline-flex h-[34px] max-w-[320px] items-center gap-[7px] rounded-[9px] border px-[11px] text-[0.76rem] font-semibold transition ${
              knowledgeBaseId !== null
                ? "border-[rgba(80,140,255,0.38)] bg-[rgba(58,127,255,0.12)] text-[rgba(190,220,255,0.96)]"
                : "border-[rgba(255,255,255,0.09)] bg-[rgba(255,255,255,0.035)] text-[rgba(200,222,250,0.8)]"
            } hover:border-[rgba(80,140,255,0.45)] hover:bg-[rgba(58,127,255,0.1)] disabled:cursor-not-allowed disabled:opacity-55 disabled:hover:border-[rgba(255,255,255,0.09)] disabled:hover:bg-[rgba(255,255,255,0.035)]`}
          >
            {submitting ? (
              <Loader2 className="h-[14px] w-[14px] shrink-0 animate-spin text-[rgba(130,185,255,0.9)]" />
            ) : (
              <Library className="h-[14px] w-[14px] shrink-0 text-[rgba(130,185,255,0.85)]" />
            )}
            <span className="truncate">{triggerLabel}</span>
            {current && <StatusBadge status={current.status} />}
            <ChevronDown
              className={`h-[13px] w-[13px] shrink-0 text-[rgba(160,195,240,0.7)] transition ${
                open ? "rotate-180" : ""
              }`}
            />
          </button>

          {open && (
            <div
              role="listbox"
              aria-label="选择会话知识库"
              className="absolute left-0 top-[calc(100%+8px)] z-40 w-[304px] rounded-[14px] border border-[rgba(80,140,255,0.22)] bg-[rgba(7,13,26,0.97)] p-[6px] shadow-[0_18px_50px_rgba(0,0,0,0.55)] backdrop-blur-xl"
            >
              <div className="flex items-center justify-between px-[10px] pb-[5px] pt-[7px]">
                <span className="font-mono text-[0.6rem] font-bold tracking-[0.16em] text-[rgba(130,185,255,0.7)]">
                  会话知识库
                </span>
                <span className="font-mono text-[0.6rem] text-[rgba(150,185,235,0.55)]">
                  {pending ? "单选 · 新建会话时生效" : "单选 · 对当前会话生效"}
                </span>
              </div>

              {loading && kbs === null ? (
                <div className="flex items-center justify-center gap-2 py-[22px] text-[0.76rem] text-[rgba(170,200,240,0.75)]">
                  <Loader2 className="h-4 w-4 animate-spin" />
                  正在加载知识库…
                </div>
              ) : loadError ? (
                <div className="flex flex-col items-center gap-[8px] px-[12px] py-[16px] text-center">
                  <span className="text-[0.76rem] font-semibold text-[rgba(255,165,180,0.95)]">
                    知识库列表加载失败
                  </span>
                  <span className="text-[0.7rem] leading-[1.6] text-[rgba(255,165,180,0.7)]">
                    {loadError}
                  </span>
                  <button
                    type="button"
                    onClick={loadList}
                    className="inline-flex h-[28px] items-center gap-[6px] rounded-[8px] border border-[rgba(80,140,255,0.35)] bg-[rgba(58,127,255,0.12)] px-[12px] text-[0.72rem] font-semibold text-[rgba(190,220,255,0.95)] transition hover:bg-[rgba(58,127,255,0.2)]"
                  >
                    <RefreshCw className="h-[12px] w-[12px]" />
                    重新加载
                  </button>
                </div>
              ) : selectable.length === 0 ? (
                <div className="flex flex-col items-center gap-[4px] px-[12px] py-[18px] text-center">
                  <span className="text-[0.76rem] font-semibold text-[rgba(200,222,250,0.85)]">
                    暂无可用知识库
                  </span>
                  <span className="text-[0.7rem] text-[rgba(150,185,235,0.6)]">
                    可先在「知识库」模块创建
                  </span>
                </div>
              ) : (
                <div className="max-h-[264px] overflow-y-auto">
                  {knowledgeBaseId !== null && (
                    <button
                      type="button"
                      role="option"
                      aria-selected={false}
                      disabled={submitting}
                      onClick={() => handleSelect(null)}
                      className="flex w-full items-center gap-[8px] rounded-[9px] px-[10px] py-[8px] text-left transition hover:bg-[rgba(255,255,255,0.05)] disabled:cursor-not-allowed disabled:opacity-55"
                    >
                      <BookOpen className="h-[14px] w-[14px] shrink-0 text-[rgba(130,185,255,0.8)]" />
                      <span className="min-w-0 flex-1 truncate text-[0.78rem] font-semibold text-[rgba(200,222,250,0.88)]">
                        不使用知识库（使用项目文档回答）
                      </span>
                    </button>
                  )}
                  {selectable.map((kb) => {
                    const selected = kb.id === knowledgeBaseId;
                    return (
                      <button
                        key={kb.id}
                        type="button"
                        role="option"
                        aria-selected={selected}
                        disabled={submitting}
                        onClick={() => handleSelect(kb.id)}
                        className={`flex w-full items-center gap-[8px] rounded-[9px] px-[10px] py-[7px] text-left transition disabled:cursor-not-allowed disabled:opacity-55 ${
                          selected
                            ? "bg-[rgba(58,127,255,0.16)]"
                            : "hover:bg-[rgba(255,255,255,0.05)]"
                        }`}
                      >
                        <div className="min-w-0 flex-1">
                          <div className="truncate text-[0.78rem] font-semibold text-[rgba(222,236,255,0.94)]">
                            {kb.name}
                          </div>
                          <div className="mt-[2px] font-mono text-[0.62rem] text-[rgba(155,190,235,0.58)]">
                            {kb.documentCount === 0
                              ? "该知识库暂无文档"
                              : `${kb.documentCount} 个文档`}
                          </div>
                        </div>
                        <StatusBadge status={kb.status} />
                        {selected && (
                          <Check className="h-[14px] w-[14px] shrink-0 text-[rgba(130,185,255,0.95)]" />
                        )}
                      </button>
                    );
                  })}
                </div>
              )}
            </div>
          )}
        </div>

        <span className="min-w-0 flex-1 truncate text-[0.72rem] text-[rgba(165,195,235,0.68)]">
          {hint}
        </span>
      </div>

      {actionError && (
        <span className="truncate text-[0.7rem] text-[rgba(255,165,180,0.95)]">
          {actionError}
        </span>
      )}
    </div>
  );
}
