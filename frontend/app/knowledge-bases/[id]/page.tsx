"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import {
  Archive,
  ArchiveRestore,
  ArrowLeft,
  Check,
  Eye,
  FileText,
  Layers,
  Loader2,
  RotateCcw,
  Trash2,
  Upload,
  X
} from "lucide-react";
import { logout, restoreSession } from "@/lib/auth-store";
import {
  archiveKnowledgeBase,
  batchUploadKnowledgeDocuments,
  deleteKnowledgeDocument,
  getKnowledgeBase,
  getKnowledgeDocumentContent,
  listKnowledgeDocuments,
  publishKnowledgeBase,
  reparseKnowledgeDocument,
  unarchiveKnowledgeBase,
  uploadKnowledgeDocument
} from "@/lib/knowledge-base";
import type { KnowledgeBase, KnowledgeDocument } from "@/lib/knowledge-base";
import type { User } from "@/lib/types";
import { documentUnit } from "@/lib/document-format";
import Topbar from "@/components/Topbar";
import KnowledgeStatusBadge from "@/components/KnowledgeStatusBadge";
import KnowledgeChunkDrawer from "@/components/KnowledgeChunkDrawer";
import KnowledgeRetrievalConfigPanel from "@/components/KnowledgeRetrievalConfigPanel";
import KnowledgeRetrievalTestConsole from "@/components/KnowledgeRetrievalTestConsole";
import DocumentPreview from "@/components/DocumentPreview";

const MAX_FILE_SIZE = 100 * 1024 * 1024; // 与后端限制保持一致：100MB
const POLL_INTERVAL_MS = 4000; // 存在非终态文档时每 4s 轮询一次（≤5s）

// 文档名前的类型色点（对齐原型 .fdot 配色）
const FILE_DOT_COLORS: Record<string, string> = {
  pdf: "#ff7a8a",
  docx: "#3a7fff",
  xlsx: "#4ade80",
  md: "#a78bfa",
  txt: "rgba(170, 200, 240, 0.45)"
};

interface UploadResultItem {
  filename: string;
  success: boolean;
  message: string;
}

function isSupportedFilename(filename: string) {
  return /\.(pdf|docx|xlsx|txt|md)$/i.test(filename);
}

function validateFile(file: File): string | null {
  if (!isSupportedFilename(file.name)) {
    return "仅支持 PDF、DOCX、XLSX、TXT、MD 文件";
  }
  if (file.size > MAX_FILE_SIZE) {
    return "文件超过 100MB 大小限制";
  }
  return null;
}

function formatBytes(size: number | null): string {
  if (size === null || size === undefined) {
    return "—";
  }
  if (size < 1024) {
    return `${size} B`;
  }
  if (size < 1024 * 1024) {
    return `${(size / 1024).toFixed(1)} KB`;
  }
  return `${(size / (1024 * 1024)).toFixed(1)} MB`;
}

function formatDate(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? "—" : date.toLocaleDateString("zh-CN");
}

export default function KnowledgeBaseDetailPage() {
  const params = useParams<{ id: string }>();
  const router = useRouter();
  const kbId = Number(params.id);

  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  const [kb, setKb] = useState<KnowledgeBase | null>(null);
  const [documents, setDocuments] = useState<KnowledgeDocument[]>([]);
  const [busyKb, setBusyKb] = useState(false);
  const [busyDocId, setBusyDocId] = useState<number | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);
  const [batchResults, setBatchResults] = useState<UploadResultItem[] | null>(null);
  const [chunkDrawerDoc, setChunkDrawerDoc] = useState<KnowledgeDocument | null>(null);
  const [chunkDrawerOpen, setChunkDrawerOpen] = useState(false);
  // 文档预览：原始 blob + 当前预览文档；previewingId 控制按钮加载态
  const [preview, setPreview] = useState<{
    doc: KnowledgeDocument;
    blob: Blob;
  } | null>(null);
  const [previewingId, setPreviewingId] = useState<number | null>(null);
  // 预览下载进度（大文件在 1Mbps 带宽下耗时分钟级，必须给用户可见反馈）
  const [previewProgress, setPreviewProgress] = useState<{
    id: number;
    loaded: number;
    total: number;
  } | null>(null);

  const singleInputRef = useRef<HTMLInputElement>(null);
  const batchInputRef = useRef<HTMLInputElement>(null);

  const loadKb = useCallback(async () => {
    const item = await getKnowledgeBase(kbId);
    setKb(item);
  }, [kbId]);

  const loadDocuments = useCallback(async () => {
    const docs = await listKnowledgeDocuments(kbId);
    setDocuments(docs);
  }, [kbId]);

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
        const [item, docs] = await Promise.all([
          getKnowledgeBase(kbId),
          listKnowledgeDocuments(kbId)
        ]);
        if (cancelled) return;
        setKb(item);
        setDocuments(docs);
      } catch {
        // 知识库不存在或无权访问
        router.replace("/knowledge-bases");
        return;
      } finally {
        if (!cancelled) setLoading(false);
      }
    }
    init();
    return () => {
      cancelled = true;
    };
  }, [kbId, router]);

  // 状态轮询：存在 UPLOADED / PROCESSING 文档时每 ≤5s 拉一次列表，全部终态后自动停止
  const hasPending = documents.some(
    (doc) => doc.status === "UPLOADED" || doc.status === "PROCESSING"
  );

  useEffect(() => {
    if (!hasPending) {
      return;
    }
    const timer = setInterval(() => {
      loadDocuments().catch(() => undefined);
      loadKb().catch(() => undefined);
    }, POLL_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [hasPending, loadDocuments, loadKb]);

  function refreshAfterUploadChange() {
    loadDocuments().catch(() => undefined);
    loadKb().catch(() => undefined);
  }

  async function handleUpload(file: File) {
    const problem = validateFile(file);
    if (problem) {
      setUploadError(`「${file.name}」${problem}`);
      return;
    }
    setUploadError(null);
    setBatchResults(null);
    setUploading(true);
    try {
      const doc = await uploadKnowledgeDocument(kbId, file);
      setDocuments((current) => [doc, ...current]);
      loadKb().catch(() => undefined);
    } catch (err) {
      setUploadError(err instanceof Error ? err.message : "上传失败，请重试");
    } finally {
      setUploading(false);
    }
  }

  async function handleBatchUpload(files: FileList | null) {
    if (!files || files.length === 0) {
      return;
    }
    const list = Array.from(files);
    const results: UploadResultItem[] = [];
    const valid: File[] = [];
    for (const file of list) {
      const problem = validateFile(file);
      if (problem) {
        results.push({ filename: file.name, success: false, message: problem });
      } else {
        valid.push(file);
      }
    }
    if (valid.length > 0) {
      setUploading(true);
      setUploadError(null);
      setBatchResults(null);
      try {
        const items = await batchUploadKnowledgeDocuments(kbId, valid);
        items.forEach((item) => {
          results.push({
            filename: item.filename ?? item.document?.filename ?? "未知文件",
            success: item.success,
            message: item.success
              ? "上传成功，已进入解析队列"
              : item.errorMessage ?? "上传失败"
          });
        });
        refreshAfterUploadChange();
      } catch (err) {
        setUploadError(err instanceof Error ? err.message : "批量上传失败，请重试");
      } finally {
        setUploading(false);
      }
    }
    if (results.length > 0) {
      setBatchResults(results);
    }
  }

  async function handleKbAction(action: () => Promise<KnowledgeBase>) {
    setBusyKb(true);
    setActionError(null);
    try {
      setKb(await action());
    } catch (err) {
      setActionError(err instanceof Error ? err.message : "操作失败，请稍后重试");
    } finally {
      setBusyKb(false);
    }
  }

  async function handleReparse(doc: KnowledgeDocument) {
    setBusyDocId(doc.id);
    setActionError(null);
    try {
      await reparseKnowledgeDocument(doc.id);
      refreshAfterUploadChange();
    } catch (err) {
      setActionError(err instanceof Error ? err.message : "重新解析失败，请稍后重试");
    } finally {
      setBusyDocId(null);
    }
  }

  async function handleDeleteDoc(doc: KnowledgeDocument) {
    if (!window.confirm(`确定删除文档「${doc.filename}」？将同时删除其切片与向量数据。`)) {
      return;
    }
    setBusyDocId(doc.id);
    setActionError(null);
    try {
      await deleteKnowledgeDocument(doc.id);
      setDocuments((current) => current.filter((item) => item.id !== doc.id));
      loadKb().catch(() => undefined);
    } catch (err) {
      setActionError(err instanceof Error ? err.message : "删除失败，请重试");
    } finally {
      setBusyDocId(null);
    }
  }

  function openUpload(archived: boolean) {
    if (archived) {
      setUploadError("知识库已归档，无法上传文档");
      return;
    }
    singleInputRef.current?.click();
  }

  function closePreview() {
    setPreview(null);
  }

  // 预览：拉取原始文件 blob，交由 DocumentPreview 按类型客户端渲染（与项目侧交互一致）
  async function handlePreview(doc: KnowledgeDocument) {
    setPreviewingId(doc.id);
    setPreviewProgress({ id: doc.id, loaded: 0, total: doc.fileSize ?? 0 });
    setActionError(null);
    try {
      const blob = await getKnowledgeDocumentContent(doc.id, (loaded, total) => {
        setPreviewProgress({ id: doc.id, loaded, total: total || doc.fileSize || 0 });
      });
      setPreview({ doc, blob });
    } catch (err) {
      setActionError(err instanceof Error ? err.message : "预览失败，请稍后重试");
    } finally {
      setPreviewingId(null);
      setPreviewProgress(null);
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

  if (!user || !kb) {
    return null;
  }

  const archived = kb.status === "ARCHIVED";
  const pendingCount = documents.filter(
    (doc) => doc.status === "UPLOADED" || doc.status === "PROCESSING"
  ).length;

  const ghostBtn =
    "inline-flex h-[36px] items-center gap-2 rounded-[10px] border border-[rgba(255,255,255,0.12)] bg-[rgba(255,255,255,0.03)] px-[14px] text-[0.8rem] font-semibold text-[rgba(225,238,255,0.9)] transition hover:border-[rgba(58,127,255,0.42)] hover:bg-[rgba(58,127,255,0.08)]";
  const primaryBtn =
    "inline-flex h-[36px] items-center gap-2 rounded-[10px] bg-accent px-[16px] text-[0.8rem] font-semibold text-white transition hover:bg-[#2860d6] hover:shadow-[0_4px_22px_rgba(58,127,255,0.38)]";
  const iconBtn =
    "flex h-[30px] w-[30px] items-center justify-center rounded-[8px] text-[rgba(180,205,240,0.68)] transition hover:border hover:border-[rgba(255,255,255,0.12)] hover:bg-[rgba(255,255,255,0.04)] hover:text-[rgba(230,240,255,0.95)]";

  return (
    <main className="grid-bg relative min-h-screen bg-gradient-to-b from-[#07101f] to-[#040810]">
      <Topbar
        user={user}
        activeNav="knowledge"
        onLogout={handleLogout}
        primaryIcon="upload"
        primaryLabel="上传文档"
        onPrimary={() => openUpload(archived)}
      />

      <div className="relative z-[2] w-full px-[clamp(16px,3vw,32px)] pb-20 pt-[clamp(24px,4vw,44px)]">
        {/* 库信息头部 */}
        <section className="mb-7">
          <button
            type="button"
            onClick={() => router.push("/knowledge-bases")}
            className="mb-4 inline-flex items-center gap-1.5 text-[0.78rem] font-semibold text-[rgba(170,200,240,0.66)] transition hover:text-[rgba(225,238,255,0.95)]"
          >
            <ArrowLeft className="h-3.5 w-3.5" />
            返回知识库列表
          </button>
          <div className="flex flex-wrap items-end justify-between gap-[18px]">
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-3">
                <h1 className="truncate font-serif text-[clamp(1.5rem,3vw,2rem)] font-black leading-[1.3]">
                  {kb.name}
                </h1>
                <KnowledgeStatusBadge status={kb.status} kind="kb" />
              </div>
              {kb.description && (
                <p className="mt-2 max-w-[640px] text-[0.82rem] leading-[1.7] text-[rgba(170,200,240,0.66)]">
                  {kb.description}
                </p>
              )}
              <p className="mt-2 font-mono text-[0.72rem] tracking-[0.04em] text-[rgba(160,185,225,0.6)]">
                文档 {kb.documentCount} · 分块 {kb.chunkCount.toLocaleString()}
                {archived ? " · 归档后只读，不参与检索与上传" : ""}
              </p>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              {kb.status === "DRAFT" && (
                <button
                  type="button"
                  disabled={busyKb}
                  onClick={() => handleKbAction(() => publishKnowledgeBase(kbId))}
                  className={primaryBtn}
                >
                  {busyKb ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : null}
                  发布知识库
                </button>
              )}
              {(kb.status === "ACTIVE" || kb.status === "DRAFT") && (
                <button
                  type="button"
                  disabled={busyKb}
                  onClick={() => handleKbAction(() => archiveKnowledgeBase(kbId))}
                  className={ghostBtn}
                >
                  <Archive className="h-3.5 w-3.5" />
                  归档
                </button>
              )}
              {archived && (
                <button
                  type="button"
                  disabled={busyKb}
                  onClick={() => handleKbAction(() => unarchiveKnowledgeBase(kbId))}
                  className={ghostBtn}
                >
                  <ArchiveRestore className="h-3.5 w-3.5" />
                  取消归档
                </button>
              )}
            </div>
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

        {/* 文档工具栏 */}
        <div className="mb-[18px] flex flex-wrap items-center justify-between gap-4">
          <div className="flex flex-wrap items-center gap-3">
            <button
              type="button"
              disabled={archived || uploading}
              onClick={() => batchInputRef.current?.click()}
              className={primaryBtn}
            >
              {uploading ? (
                <Loader2 className="h-3.5 w-3.5 animate-spin" />
              ) : (
                <Upload className="h-3.5 w-3.5" />
              )}
              批量上传
            </button>
            <button
              type="button"
              disabled={archived || uploading}
              onClick={() => singleInputRef.current?.click()}
              className={ghostBtn}
            >
              <Upload className="h-3.5 w-3.5" />
              上传文档
            </button>
            {archived && (
              <span className="text-[0.76rem] font-semibold text-[rgba(245,177,78,0.95)]">
                已归档，上传已禁用
              </span>
            )}
          </div>
          <span className="font-mono text-[0.74rem] text-[rgba(160,185,225,0.6)]">
            共 {documents.length} 个文档{pendingCount > 0 ? ` · ${pendingCount} 个待处理` : ""}
          </span>
        </div>

        <input
          ref={singleInputRef}
          type="file"
          accept=".pdf,.docx,.xlsx,.txt,.md"
          className="hidden"
          onChange={(e) => {
            const file = e.target.files?.[0];
            if (file) handleUpload(file);
            e.target.value = "";
          }}
        />
        <input
          ref={batchInputRef}
          type="file"
          accept=".pdf,.docx,.xlsx,.txt,.md"
          multiple
          className="hidden"
          onChange={(e) => {
            handleBatchUpload(e.target.files);
            e.target.value = "";
          }}
        />

        {uploadError && (
          <p
            role="alert"
            className="mb-4 rounded-[10px] border border-[rgba(255,80,100,0.3)] bg-[rgba(255,80,100,0.08)] px-4 py-3 text-[0.8rem] text-[rgba(255,175,190,0.95)]"
          >
            {uploadError}
          </p>
        )}

        {batchResults && (
          <div className="mb-4 rounded-[14px] border border-[rgba(58,127,255,0.28)] bg-[rgba(58,127,255,0.06)] px-4 py-3">
            <div className="mb-2 flex items-center justify-between gap-3">
              <span className="text-[0.8rem] font-bold text-[rgba(220,235,255,0.92)]">
                上传结果 · 成功 {batchResults.filter((r) => r.success).length} / {batchResults.length}
              </span>
              <button
                type="button"
                onClick={() => setBatchResults(null)}
                aria-label="关闭上传结果"
                className="flex h-7 w-7 items-center justify-center rounded-lg text-[rgba(180,205,240,0.62)] transition hover:bg-[rgba(255,255,255,0.07)] hover:text-[rgba(230,240,255,0.95)]"
              >
                <X className="h-3.5 w-3.5" />
              </button>
            </div>
            <ul className="space-y-1.5">
              {batchResults.map((item, index) => (
                <li key={`${item.filename}-${index}`} className="flex items-start gap-2 text-[0.78rem]">
                  {item.success ? (
                    <Check className="mt-0.5 h-3.5 w-3.5 shrink-0 text-[#4ade80]" />
                  ) : (
                    <X className="mt-0.5 h-3.5 w-3.5 shrink-0 text-[#ff7a8a]" />
                  )}
                  <span className="break-all font-medium text-[rgba(220,235,255,0.9)]">
                    {item.filename}
                  </span>
                  <span
                    className={
                      item.success
                        ? "text-[rgba(148,220,170,0.9)]"
                        : "text-[rgba(255,165,180,0.95)]"
                    }
                  >
                    {item.message}
                  </span>
                </li>
              ))}
            </ul>
          </div>
        )}

        {/* 文档列表 */}
        {documents.length === 0 ? (
          <div className="flex flex-col items-center justify-center rounded-[14px] border border-border bg-panel px-5 py-20 text-center backdrop-blur-[8px]">
            <div className="mb-4 flex h-[52px] w-[52px] items-center justify-center rounded-[14px] border border-[rgba(80,140,255,0.28)] bg-[rgba(80,140,255,0.09)] text-[rgba(130,185,255,0.9)] shadow-[0_0_30px_rgba(60,120,255,0.14)]">
              <FileText className="h-6 w-6" />
            </div>
            <h2 className="font-serif text-base font-bold">还没有文档</h2>
            <p className="mt-2 max-w-[380px] text-[0.78rem] leading-[1.7] text-[rgba(160,190,230,0.58)]">
              {archived
                ? "该知识库已归档，取消归档后才能继续上传。"
                : "上传 PDF、DOCX、XLSX、TXT 或 MD 文档，解析完成后即可被问答引用。"}
            </p>
          </div>
        ) : (
          <div className="overflow-hidden rounded-[14px] border border-border bg-panel backdrop-blur-[8px]">
            <div className="overflow-x-auto">
              <table className="w-full min-w-[860px] border-collapse text-[0.84rem] [&>tbody>tr:last-child>td]:border-b-0">
                <thead>
                  <tr>
                    {["文档名", "状态", "页数", "Token", "大小", "上传时间"].map((label, index) => (
                      <th
                        key={label}
                        className={`border-b border-[rgba(255,255,255,0.12)] bg-[rgba(255,255,255,0.03)] px-[18px] py-[13px] text-left font-mono text-[0.66rem] font-semibold tracking-[0.12em] text-[rgba(170,200,240,0.66)] whitespace-nowrap ${
                          index === 5 ? "text-right" : ""
                        }`}
                      >
                        {label}
                      </th>
                    ))}
                    <th className="border-b border-[rgba(255,255,255,0.12)] bg-[rgba(255,255,255,0.03)] px-[18px] py-[13px] text-right font-mono text-[0.66rem] font-semibold tracking-[0.12em] text-[rgba(170,200,240,0.66)]">
                      操作
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {documents.map((doc) => {
                    const dotColor =
                      FILE_DOT_COLORS[doc.fileType?.toLowerCase() ?? ""] ??
                      "rgba(170, 200, 240, 0.45)";
                    return (
                      <tr key={doc.id} className="transition hover:bg-[rgba(255,255,255,0.025)]">
                        <td className="border-b border-border px-[18px] py-[14px]">
                          <div className="flex items-center gap-2.5 font-semibold text-[rgba(225,238,255,0.92)]">
                            <span
                              className="h-2 w-2 shrink-0 rounded-[2.5px]"
                              style={{ backgroundColor: dotColor }}
                            />
                            <span className="break-all" title={doc.filename}>
                              {doc.filename}
                            </span>
                          </div>
                          {doc.status === "FAILED" && doc.errorMessage && (
                            <p className="mt-1 pl-[18px] text-[0.72rem] leading-[1.6] text-[rgba(255,150,165,0.9)]">
                              失败原因：{doc.errorMessage}
                            </p>
                          )}
                        </td>
                        <td className="border-b border-border px-[18px] py-[14px]">
                          <KnowledgeStatusBadge status={doc.status} kind="doc" />
                        </td>
                        <td className="border-b border-border px-[18px] py-[14px] font-mono text-[0.78rem] text-[rgba(190,215,245,0.75)] whitespace-nowrap">
                          {doc.pageCount === null || doc.pageCount === undefined
                            ? doc.status === "PROCESSING"
                              ? "解析中…"
                              : "—"
                            : `${doc.pageCount.toLocaleString()} ${documentUnit(doc.fileType)}`}
                        </td>
                        <td className="border-b border-border px-[18px] py-[14px] font-mono text-[0.78rem] text-[rgba(190,215,245,0.75)] whitespace-nowrap">
                          {doc.tokenCount === null || doc.tokenCount === undefined
                            ? doc.status === "PROCESSING"
                              ? "统计中…"
                              : "—"
                            : doc.tokenCount.toLocaleString()}
                        </td>
                        <td className="border-b border-border px-[18px] py-[14px] font-mono text-[0.78rem] text-[rgba(190,215,245,0.75)] whitespace-nowrap">
                          {previewProgress?.id === doc.id ? (
                            <span className="text-[#8ab4ff]">
                              下载中 {formatBytes(previewProgress.loaded)}
                              {previewProgress.total > 0
                                ? ` / ${formatBytes(previewProgress.total)}（${Math.round(
                                    (previewProgress.loaded / previewProgress.total) * 100
                                  )}%）`
                                : ""}
                            </span>
                          ) : (
                            formatBytes(doc.fileSize)
                          )}
                        </td>
                        <td className="border-b border-border px-[18px] py-[14px] font-mono text-[0.78rem] text-[rgba(160,185,225,0.6)] whitespace-nowrap">
                          {formatDate(doc.createdAt)}
                        </td>
                        <td className="border-b border-border px-[18px] py-[14px]">
                          <div className="flex items-center justify-end gap-1.5">
                            {doc.status === "INDEXED" && (
                              <button
                                type="button"
                                onClick={() => {
                                  setChunkDrawerDoc(doc);
                                  setChunkDrawerOpen(true);
                                }}
                                className="inline-flex items-center gap-[6px] rounded-[8px] border border-[rgba(255,255,255,0.12)] bg-[rgba(255,255,255,0.03)] px-[11px] py-[5px] text-[0.74rem] font-semibold text-[rgba(200,220,250,0.85)] transition hover:border-[rgba(58,127,255,0.42)] hover:bg-[rgba(58,127,255,0.08)] hover:text-[#8ab4ff]"
                              >
                                <Layers className="h-3 w-3" />
                                查看切片
                              </button>
                            )}
                            {doc.status === "FAILED" && (
                              <button
                                type="button"
                                disabled={busyDocId === doc.id}
                                onClick={() => handleReparse(doc)}
                                className="inline-flex items-center gap-[6px] rounded-[8px] border border-[rgba(58,127,255,0.45)] bg-[rgba(58,127,255,0.1)] px-[11px] py-[5px] text-[0.74rem] font-semibold text-[#8ab4ff] transition hover:bg-[rgba(58,127,255,0.2)] hover:shadow-[0_0_16px_rgba(58,127,255,0.25)] disabled:opacity-50"
                              >
                                {busyDocId === doc.id ? (
                                  <Loader2 className="h-3 w-3 animate-spin" />
                                ) : (
                                  <RotateCcw className="h-3 w-3" />
                                )}
                                重新解析
                              </button>
                            )}
                            <button
                              type="button"
                              disabled={previewingId !== null}
                              onClick={() => handlePreview(doc)}
                              aria-label={`预览 ${doc.filename}`}
                              title="预览"
                              className={`${iconBtn} disabled:cursor-wait disabled:opacity-50`}
                            >
                              {previewingId === doc.id ? (
                                <Loader2 className="h-[15px] w-[15px] animate-spin" />
                              ) : (
                                <Eye className="h-[15px] w-[15px]" />
                              )}
                            </button>
                            <button
                              type="button"
                              disabled={busyDocId === doc.id}
                              onClick={() => handleDeleteDoc(doc)}
                              aria-label={`删除 ${doc.filename}`}
                              title="删除"
                              className={`${iconBtn} hover:border-[rgba(255,122,138,0.35)] hover:bg-[rgba(255,122,138,0.07)] hover:text-[#ff7a8a] disabled:opacity-50`}
                            >
                              <Trash2 className="h-[15px] w-[15px]" />
                            </button>
                          </div>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </div>
        )}

        {/* 检索配置 + RAG 测试台：左配置右调试台，窄屏堆叠 */}
        <section className="mt-6 grid grid-cols-1 items-start gap-5 xl:grid-cols-[minmax(0,5fr)_minmax(0,7fr)]">
          <KnowledgeRetrievalConfigPanel knowledgeBaseId={kbId} />
          <KnowledgeRetrievalTestConsole knowledgeBaseId={kbId} />
        </section>
      </div>

      {/* 文档切片抽屉（右侧滑入） */}
      <KnowledgeChunkDrawer
        doc={chunkDrawerDoc}
        open={chunkDrawerOpen}
        onClose={() => setChunkDrawerOpen(false)}
      />

      {/* 文档预览弹层（按文件类型客户端渲染，复用项目侧预览交互） */}
      {preview && (
        <DocumentPreview
          filename={preview.doc.filename}
          fileType={preview.doc.fileType}
          blob={preview.blob}
          onClose={closePreview}
        />
      )}
    </main>
  );
}
