"use client";

import { useCallback, useEffect, useState } from "react";
import { ChevronLeft, ChevronRight, FileText, Loader2, RotateCcw, X } from "lucide-react";
import { listKnowledgeChunks } from "@/lib/knowledge-base";
import type { KnowledgeChunkPage, KnowledgeDocument } from "@/lib/knowledge-base";
import { formatSourceLocation } from "@/lib/document-format";

const PAGE_SIZE = 20; // 与后端 chunks 端点默认值一致
const CLOSE_ANIMATION_MS = 440; // 对齐抽屉 transform 过渡时长

interface KnowledgeChunkDrawerProps {
  doc: KnowledgeDocument | null;
  open: boolean;
  onClose: () => void;
}

// 生成分页页码序列：页数少时全部展示，多时以当前页为中心开窗并以 … 折叠
function buildPageList(current: number, total: number): (number | "…")[] {
  if (total <= 7) {
    return Array.from({ length: total }, (_, i) => i + 1);
  }
  const wanted = new Set(
    [1, total, current - 1, current, current + 1].filter((p) => p >= 1 && p <= total)
  );
  const sorted = [...wanted].sort((a, b) => a - b);
  const list: (number | "…")[] = [];
  sorted.forEach((p, i) => {
    if (i > 0 && p - sorted[i - 1] > 1) {
      list.push("…");
    }
    list.push(p);
  });
  return list;
}

export default function KnowledgeChunkDrawer({ doc, open, onClose }: KnowledgeChunkDrawerProps) {
  const [mounted, setMounted] = useState(false);
  const [pageData, setPageData] = useState<KnowledgeChunkPage | null>(null);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 关闭时等滑出动画结束后再卸载，保证 transform 过渡可见
  useEffect(() => {
    if (open) {
      setMounted(true);
      return;
    }
    if (!mounted) return;
    const timer = setTimeout(() => setMounted(false), CLOSE_ANIMATION_MS);
    return () => clearTimeout(timer);
  }, [open, mounted]);

  // 打开时锁定背景滚动 + Esc 关闭（对齐原型行为）
  useEffect(() => {
    if (!open) return;
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKeyDown);
    return () => {
      document.body.style.overflow = prevOverflow;
      window.removeEventListener("keydown", onKeyDown);
    };
  }, [open, onClose]);

  const loadChunks = useCallback(async (docId: number, page: number) => {
    setLoading(true);
    setError(null);
    try {
      const data = await listKnowledgeChunks(docId, page, PAGE_SIZE);
      setPageData(data);
    } catch (err) {
      setError(err instanceof Error ? err.message : "切片加载失败，请重试");
    } finally {
      setLoading(false);
    }
  }, []);

  // 切换文档时回到第 1 页
  useEffect(() => {
    if (open) {
      setPage(1);
    }
  }, [open, doc?.id]);

  useEffect(() => {
    if (!open || !doc) return;
    loadChunks(doc.id, page);
  }, [open, doc, page, loadChunks]);

  if (!mounted) {
    return null;
  }

  const totalPages = pageData ? Math.max(1, Math.ceil(pageData.total / PAGE_SIZE)) : 1;
  const totalChunks = pageData?.total ?? 0;

  return (
    <>
      {/* 遮罩：淡入淡出，点击关闭 */}
      <div
        aria-hidden={!open}
        onClick={onClose}
        className={`fixed inset-0 z-[90] bg-[rgba(2,5,12,0.62)] backdrop-blur-[2px] transition-opacity duration-[320ms] ${
          open ? "opacity-100" : "pointer-events-none opacity-0"
        }`}
      />
      {/* 抽屉：右侧滑入（transform 过渡对齐原型 cubic-bezier(0.22,1,0.36,1) 420ms） */}
      <aside
        role="dialog"
        aria-modal="true"
        aria-hidden={!open}
        aria-label={`查看切片：${doc?.filename ?? ""}`}
        className={`fixed top-0 right-0 z-[100] flex h-screen w-[420px] max-w-[94vw] flex-col border-l border-[rgba(255,255,255,0.12)] bg-[#060c1a] shadow-[-24px_0_60px_rgba(0,0,0,0.5)] transition-transform duration-[420ms] ease-[cubic-bezier(0.22,1,0.36,1)] ${
          open ? "translate-x-0" : "translate-x-full"
        }`}
      >
        <div className="flex items-start justify-between gap-3 border-b border-[rgba(255,255,255,0.12)] px-[22px] pt-5 pb-4">
          <div className="min-w-0">
            <div className="flex items-center gap-2 text-[0.97rem] font-bold text-[#e8f0ff]">
              <FileText className="h-4 w-4 shrink-0 text-[#3a7fff]" />
              <span className="truncate" title={doc?.filename ?? undefined}>
                切片明细
              </span>
            </div>
            <p className="mt-2 truncate font-mono text-[0.72rem] text-[rgba(170,200,240,0.66)]">
              {doc?.filename ?? "—"} · 共 {totalChunks.toLocaleString()} 条切片
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="关闭切片抽屉"
            className="flex h-[30px] w-[30px] shrink-0 items-center justify-center rounded-[8px] text-[rgba(170,200,240,0.66)] transition hover:border hover:border-[rgba(255,255,255,0.12)] hover:bg-[rgba(255,255,255,0.03)] hover:text-[#e8f0ff]"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="flex flex-1 flex-col gap-3 overflow-y-auto px-[22px] pt-[18px] pb-7">
          {loading ? (
            <div className="flex flex-1 flex-col items-center justify-center gap-3 py-16 text-[0.82rem] text-[rgba(170,200,240,0.66)]">
              <Loader2 className="h-5 w-5 animate-spin text-[#3a7fff]" />
              正在加载切片…
            </div>
          ) : error ? (
            <div className="flex flex-1 flex-col items-center justify-center gap-4 py-16 text-center">
              <p className="rounded-[10px] border border-[rgba(255,80,100,0.3)] bg-[rgba(255,80,100,0.08)] px-4 py-3 text-[0.8rem] text-[rgba(255,175,190,0.95)]">
                {error}
              </p>
              {doc && (
                <button
                  type="button"
                  onClick={() => loadChunks(doc.id, page)}
                  className="inline-flex h-[34px] items-center gap-2 rounded-[10px] border border-[rgba(58,127,255,0.42)] bg-[rgba(58,127,255,0.1)] px-[14px] text-[0.78rem] font-semibold text-[#8ab4ff] transition hover:bg-[rgba(58,127,255,0.2)]"
                >
                  <RotateCcw className="h-3.5 w-3.5" />
                  重新加载
                </button>
              )}
            </div>
          ) : !pageData || pageData.items.length === 0 ? (
            <div className="flex flex-1 flex-col items-center justify-center gap-3 py-16 text-center">
              <div className="flex h-[46px] w-[46px] items-center justify-center rounded-[12px] border border-[rgba(80,140,255,0.28)] bg-[rgba(80,140,255,0.09)] text-[rgba(130,185,255,0.9)]">
                <FileText className="h-5 w-5" />
              </div>
              <p className="text-[0.86rem] font-bold text-[rgba(220,235,255,0.9)]">暂无切片</p>
              <p className="max-w-[260px] text-[0.78rem] leading-[1.7] text-[rgba(170,200,240,0.66)]">
                该文档解析后尚未生成切片，可尝试重新解析后再查看。
              </p>
            </div>
          ) : (
            pageData.items.map((chunk) => (
              <div
                key={chunk.chunkIndex}
                className="rounded-[10px] border border-[rgba(255,255,255,0.07)] bg-[rgba(255,255,255,0.03)] px-4 py-[14px] transition hover:border-[rgba(58,127,255,0.3)]"
              >
                <div className="mb-[9px] flex items-center justify-between gap-2.5">
                  <span className="font-mono text-[0.75rem] font-semibold tracking-[0.06em] text-[#3a7fff]">
                    #{String(chunk.chunkIndex + 1).padStart(3, "0")}
                  </span>
                  <span className="rounded-[6px] border border-[rgba(58,127,255,0.22)] bg-[rgba(58,127,255,0.1)] px-2 py-[2px] font-mono text-[0.62rem] tracking-[0.04em] whitespace-nowrap text-[#8ab4ff]">
                    {chunk.locationLabel || "页"}
                  </span>
                </div>
                <p className="text-[0.78rem] leading-[1.75] break-words whitespace-pre-wrap text-[rgba(200,220,250,0.82)]">
                  {chunk.content}
                </p>
                <p className="mt-2.5 font-mono text-[0.69rem] text-[rgba(160,185,225,0.6)]">
                  {formatSourceLocation(chunk)} · {chunk.tokenCount.toLocaleString()} tokens
                </p>
              </div>
            ))
          )}
        </div>

        {/* 分页控件 */}
        {pageData && pageData.items.length > 0 && (
          <div className="flex items-center justify-between gap-3 border-t border-[rgba(255,255,255,0.12)] px-[22px] py-3">
            <button
              type="button"
              disabled={page <= 1 || loading}
              onClick={() => setPage((p) => Math.max(1, p - 1))}
              className="inline-flex h-[30px] items-center gap-1 rounded-[8px] border border-[rgba(255,255,255,0.12)] bg-[rgba(255,255,255,0.03)] px-[10px] font-mono text-[0.72rem] text-[rgba(200,220,250,0.85)] transition hover:border-[rgba(58,127,255,0.42)] hover:bg-[rgba(58,127,255,0.08)] disabled:cursor-not-allowed disabled:opacity-35"
            >
              <ChevronLeft className="h-3.5 w-3.5" />
              上一页
            </button>
            <div className="flex items-center gap-1">
              {buildPageList(page, totalPages).map((item, index) =>
                item === "…" ? (
                  <span
                    key={`ellipsis-${index}`}
                    className="px-1 font-mono text-[0.72rem] text-[rgba(160,185,225,0.6)]"
                  >
                    …
                  </span>
                ) : (
                  <button
                    key={item}
                    type="button"
                    disabled={loading}
                    onClick={() => setPage(item)}
                    aria-current={item === page ? "page" : undefined}
                    className={`h-[26px] min-w-[26px] rounded-[7px] px-1.5 font-mono text-[0.72rem] transition disabled:cursor-not-allowed ${
                      item === page
                        ? "border border-[rgba(58,127,255,0.5)] bg-[rgba(58,127,255,0.18)] font-semibold text-[#8ab4ff]"
                        : "text-[rgba(170,200,240,0.66)] hover:bg-[rgba(255,255,255,0.05)] hover:text-[rgba(225,238,255,0.92)]"
                    }`}
                  >
                    {item}
                  </button>
                )
              )}
            </div>
            <button
              type="button"
              disabled={page >= totalPages || loading}
              onClick={() => setPage((p) => Math.min(totalPages, p + 1))}
              className="inline-flex h-[30px] items-center gap-1 rounded-[8px] border border-[rgba(255,255,255,0.12)] bg-[rgba(255,255,255,0.03)] px-[10px] font-mono text-[0.72rem] text-[rgba(200,220,250,0.85)] transition hover:border-[rgba(58,127,255,0.42)] hover:bg-[rgba(58,127,255,0.08)] disabled:cursor-not-allowed disabled:opacity-35"
            >
              下一页
              <ChevronRight className="h-3.5 w-3.5" />
            </button>
          </div>
        )}

        <div className="border-t border-[rgba(255,255,255,0.12)] px-[22px] py-[14px] font-mono text-[0.69rem] text-[rgba(160,185,225,0.6)]">
          切片由 {doc?.embeddingModelVersion || "embedding 模型"} 索引 · 每页 {PAGE_SIZE} 条 · 按
          文档顺序展示
        </div>
      </aside>
    </>
  );
}
