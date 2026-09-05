"use client";

import { useRef, useState } from "react";
import {
  Eye,
  FileText,
  Loader2,
  MessageSquare,
  Plus,
  Upload,
  X
} from "lucide-react";
import { getDocumentContent } from "@/lib/api";
import type { Conversation, Document, Project, User } from "@/lib/types";
import LogoMark from "./LogoMark";
import StatusBadge from "./StatusBadge";

interface ProjectSidebarProps {
  user: User;
  project: Project | null;
  documents: Document[];
  conversations: Conversation[];
  selectedConversationId: number | null;
  onUpload: (file: File) => void;
  onDeleteDocument: (document: Document) => void;
  onCreateConversation: () => void;
  onSelectConversation: (conversation: Conversation) => void;
  onLogout: () => void;
  mobileOpen: boolean;
  onCloseMobile: () => void;
}

export default function ProjectSidebar({
  user,
  project,
  documents,
  conversations,
  selectedConversationId,
  onUpload,
  onDeleteDocument,
  onCreateConversation,
  onSelectConversation,
  onLogout,
  mobileOpen,
  onCloseMobile
}: ProjectSidebarProps) {
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [preview, setPreview] = useState<{
    document: Document;
    url: string;
  } | null>(null);
  const [previewingId, setPreviewingId] = useState<number | null>(null);
  const [previewError, setPreviewError] = useState<string | null>(null);

  function closePreview() {
    if (preview) {
      URL.revokeObjectURL(preview.url);
    }
    setPreview(null);
  }

  async function handlePreview(document: Document) {
    setPreviewingId(document.id);
    setPreviewError(null);
    try {
      const blob = await getDocumentContent(document.id);
      setPreview({ document, url: URL.createObjectURL(blob) });
    } catch (err) {
      setPreviewError(
        err instanceof Error ? err.message : "预览失败，请稍后重试"
      );
    } finally {
      setPreviewingId(null);
    }
  }

  return (
    <>
      {mobileOpen && (
        <div
          className="fixed inset-0 z-35 bg-black/55 md:hidden"
          onClick={onCloseMobile}
        />
      )}
      <aside
        className={`fixed inset-y-0 left-0 z-40 flex w-[300px] flex-col gap-4 overflow-hidden border-r border-border bg-[rgba(6,12,26,0.86)] p-4 backdrop-blur-xl transition-transform duration-300 md:static md:translate-x-0 ${
          mobileOpen ? "translate-x-0" : "-translate-x-[102%]"
        }`}
      >
        <div className="flex items-center justify-between gap-[10px]">
          <div className="flex items-center gap-[10px]">
            <LogoMark />
            <div>
              <span className="block whitespace-nowrap font-serif text-[1.04rem] font-black text-[rgba(220,235,255,0.94)]">
                智阅
              </span>
              <span className="font-mono text-[0.58rem] uppercase tracking-[0.16em] text-[rgba(150,185,235,0.5)]">
                Wisread
              </span>
            </div>
          </div>
          <button
            type="button"
            onClick={onCloseMobile}
            aria-label="关闭侧边栏"
            className="flex h-8 w-8 items-center justify-center rounded-lg text-[rgba(180,205,240,0.62)] hover:bg-[rgba(255,255,255,0.07)] md:hidden"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto">
          {/* 文档区 */}
          <section className="min-h-0">
            <div className="mb-[9px] flex items-center justify-between gap-2">
              <span className="text-[0.7rem] font-bold tracking-[0.06em] text-[rgba(180,205,240,0.6)]">
                文档
                <span className="ml-1.5 font-mono text-[0.62rem] font-semibold tracking-[0.04em] text-[rgba(150,185,235,0.42)]">
                  {documents.length}
                </span>
              </span>
            </div>
            <input
              ref={fileInputRef}
              type="file"
              accept="application/pdf"
              className="hidden"
              onChange={(e) => {
                const file = e.target.files?.[0];
                if (file) onUpload(file);
                e.target.value = "";
              }}
            />
            {previewError && (
              <p
                role="alert"
                className="mb-2 rounded-md border border-[rgba(255,120,140,0.18)] bg-[rgba(255,80,100,0.08)] px-2 py-1.5 text-[0.66rem] text-[rgba(255,165,180,0.95)]"
              >
                {previewError}
              </p>
            )}
            <div className="space-y-[7px]">
              {documents.length === 0 && (
                <p className="px-2 py-4 text-center text-[0.72rem] text-[rgba(160,190,230,0.4)]">
                  暂无文档，点击添加上传
                </p>
              )}
              {documents.map((document) => (
                <div
                  key={document.id}
                  className="flex items-center gap-[9px] rounded-[9px] border border-transparent px-[10px] py-[9px] text-[rgba(185,210,245,0.68)] transition hover:bg-[rgba(255,255,255,0.045)]"
                >
                  <span className="flex h-[30px] w-[30px] shrink-0 items-center justify-center rounded-[8px] bg-[rgba(80,140,255,0.1)] text-[rgba(120,180,255,0.85)]">
                    <FileText className="h-4 w-4" />
                  </span>
                  <div className="min-w-0 flex-1">
                    <div
                      className="truncate text-[0.76rem] font-semibold"
                      title={document.filename}
                    >
                      {document.filename}
                    </div>
                    <div className="mt-[3px] font-mono text-[0.6rem] text-[rgba(150,185,235,0.4)]">
                      {document.pageCount ? `${document.pageCount} 页 · ` : ""}
                      {document.fileSize
                        ? `${(document.fileSize / 1024 / 1024).toFixed(1)} MB`
                        : ""}
                    </div>
                  </div>
                  <StatusBadge status={document.status} />
                  <button
                    type="button"
                    onClick={() => handlePreview(document)}
                    disabled={previewingId !== null}
                    aria-label={`预览文档 ${document.filename}`}
                    title="预览文档"
                    className="flex h-6 w-6 shrink-0 items-center justify-center rounded text-[rgba(160,195,240,0.4)] transition hover:bg-[rgba(80,140,255,0.12)] hover:text-[rgba(130,185,255,1)] disabled:cursor-wait disabled:opacity-45"
                  >
                    {previewingId === document.id ? (
                      <Loader2 className="h-3.5 w-3.5 animate-spin" />
                    ) : (
                      <Eye className="h-3.5 w-3.5" />
                    )}
                  </button>
                  <button
                    type="button"
                    onClick={() => onDeleteDocument(document)}
                    aria-label="删除文档"
                    className="flex h-6 w-6 shrink-0 items-center justify-center rounded text-[rgba(160,195,240,0.4)] transition hover:text-[#ff7a8a]"
                  >
                    <X className="h-3.5 w-3.5" />
                  </button>
                </div>
              ))}
            </div>
          </section>

          {/* 会话区 */}
          <section className="min-h-0">
            <div className="mb-[9px] flex items-center justify-between gap-2">
              <span className="text-[0.7rem] font-bold tracking-[0.06em] text-[rgba(180,205,240,0.6)]">
                会话
                <span className="ml-1.5 font-mono text-[0.62rem] font-semibold tracking-[0.04em] text-[rgba(150,185,235,0.42)]">
                  {conversations.length}
                </span>
              </span>
              <button
                type="button"
                onClick={onCreateConversation}
                className="inline-flex items-center gap-1 rounded-[6px] px-[7px] py-1 text-[0.68rem] font-bold text-[rgba(110,165,255,0.75)] transition hover:bg-[rgba(80,140,255,0.1)] hover:text-[rgba(110,165,255,1)]"
              >
                <Plus className="h-3 w-3" />
                新建
              </button>
            </div>
            <div className="space-y-[7px]">
              {conversations.length === 0 && (
                <p className="px-2 py-4 text-center text-[0.72rem] text-[rgba(160,190,230,0.4)]">
                  暂无会话，点击新建开始提问
                </p>
              )}
              {conversations.map((conversation) => (
                <button
                  key={conversation.id}
                  type="button"
                  onClick={() => onSelectConversation(conversation)}
                  className={`flex w-full items-center gap-[9px] rounded-[9px] border px-[10px] py-[9px] text-left transition ${
                    selectedConversationId === conversation.id
                      ? "border-[rgba(80,140,255,0.32)] bg-[rgba(80,140,255,0.1)] text-[rgba(225,238,255,0.95)]"
                      : "border-transparent text-[rgba(185,210,245,0.68)] hover:bg-[rgba(255,255,255,0.045)]"
                  }`}
                >
                  <span className="flex h-[30px] w-[30px] shrink-0 items-center justify-center rounded-[8px] bg-[rgba(80,140,255,0.1)] text-[rgba(120,180,255,0.85)]">
                    <MessageSquare className="h-4 w-4" />
                  </span>
                  <span className="min-w-0 flex-1 truncate text-[0.76rem] font-semibold">
                    {conversation.title}
                  </span>
                </button>
              ))}
            </div>
          </section>
        </div>

        <div className="flex shrink-0 items-center gap-2 border-t border-border bg-[rgba(4,8,18,0.72)] px-[14px] py-3">
          <button
            type="button"
            onClick={() => fileInputRef.current?.click()}
            className="inline-flex h-9 flex-1 items-center justify-center gap-2 whitespace-nowrap rounded-[10px] border border-[rgba(80,150,255,0.35)] bg-gradient-to-br from-[#2860d6] to-[#3a7fff] px-3 text-[0.72rem] font-bold text-[rgba(230,242,255,0.96)] shadow-[0_4px_20px_rgba(40,100,255,0.28)] transition hover:brightness-110"
          >
            <Upload className="h-4 w-4" />
            <span>上传文档</span>
          </button>
          <div
            className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full border border-[rgba(120,180,255,0.32)] bg-gradient-to-br from-[#1d3f7a] to-[#102a56] text-[0.78rem] font-bold text-[rgba(220,235,255,0.95)]"
            title={user.username}
          >
            {user.username.charAt(0).toUpperCase()}
          </div>
        </div>
      </aside>
      {preview && (
        <div
          role="dialog"
          aria-modal="true"
          aria-label={`预览 ${preview.document.filename}`}
          className="fixed inset-0 z-50 flex flex-col bg-black/75 p-[clamp(8px,2vw,24px)]"
        >
          <div className="flex h-12 shrink-0 items-center gap-3 rounded-t-xl border border-b-0 border-[rgba(255,255,255,0.1)] bg-[rgba(8,16,30,0.98)] px-4">
            <FileText className="h-4 w-4 shrink-0 text-[rgba(130,185,255,0.9)]" />
            <span
              className="truncate text-[0.8rem] font-semibold text-[rgba(220,235,255,0.92)]"
              title={preview.document.filename}
            >
              {preview.document.filename}
            </span>
            <button
              type="button"
              onClick={closePreview}
              aria-label="关闭预览"
              className="ml-auto flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-[rgba(180,205,240,0.62)] transition hover:bg-[rgba(255,255,255,0.07)] hover:text-[rgba(230,240,255,0.95)]"
            >
              <X className="h-4 w-4" />
            </button>
          </div>
          <iframe
            title={`${preview.document.filename} 预览`}
            src={preview.url}
            className="min-h-0 w-full flex-1 rounded-b-xl border border-[rgba(255,255,255,0.1)] bg-white"
          />
        </div>
      )}
    </>
  );
}
