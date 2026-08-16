"use client";

import { useCallback, useEffect, useState } from "react";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import {
  ArrowLeft,
  Loader2,
  PanelLeft,
  Plus,
  X
} from "lucide-react";
import { logout, restoreSession } from "@/lib/auth-store";
import {
  createConversation,
  deleteDocument,
  getDocument,
  getProject,
  listConversations,
  listDocuments,
  listMessages,
  uploadDocument
} from "@/lib/api";
import { streamChat } from "@/lib/sse";
import type {
  Conversation,
  Document,
  Message,
  Project,
  User
} from "@/lib/types";
import ProjectSidebar from "@/components/ProjectSidebar";
import ChatPanel from "@/components/ChatPanel";

const MAX_FILE_SIZE = 100 * 1024 * 1024; // 与后端限制保持一致：100MB

export default function ProjectDetailPage() {
  const params = useParams<{ projectId: string }>();
  const router = useRouter();
  const searchParams = useSearchParams();
  const projectId = Number(params.projectId);

  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  const [project, setProject] = useState<Project | null>(null);
  const [documents, setDocuments] = useState<Document[]>([]);
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [selectedConversation, setSelectedConversation] =
    useState<Conversation | null>(null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [streaming, setStreaming] = useState(false);
  const [streamingContent, setStreamingContent] = useState("");
  const [mobileSidebarOpen, setMobileSidebarOpen] = useState(false);
  const [uploadError, setUploadError] = useState<string | null>(null);

  const loadProject = useCallback(async () => {
    const p = await getProject(projectId);
    setProject(p);
  }, [projectId]);

  const loadDocuments = useCallback(async () => {
    const docs = await listDocuments(projectId);
    setDocuments(docs);
    docs.forEach((doc) => {
      if (doc.status !== "READY" && doc.status !== "FAILED") {
        pollDocument(doc.id);
      }
    });
  }, [projectId]);

  const loadConversations = useCallback(async () => {
    const items = await listConversations(projectId);
    setConversations(items);
    const convParam = searchParams.get("conv");
    if (convParam) {
      const convId = Number(convParam);
      const found = items.find((c) => c.id === convId);
      if (found) {
        setSelectedConversation(found);
        const msgs = await listMessages(found.id);
        setMessages(msgs);
        return;
      }
    }
    if (items.length > 0 && !selectedConversation) {
      setSelectedConversation(items[0]);
      const msgs = await listMessages(items[0].id);
      setMessages(msgs);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId]);

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
        await Promise.all([loadProject(), loadDocuments(), loadConversations()]);
      } catch {
        // 项目不存在或无权访问
        router.replace("/projects");
        return;
      } finally {
        setLoading(false);
      }
    }
    init();
    return () => {
      cancelled = true;
    };
  }, [loadProject, loadDocuments, loadConversations, router]);

  function pollDocument(documentId: number) {
    setTimeout(async () => {
      try {
        const document = await getDocument(documentId);
        setDocuments((current) =>
          current.map((item) => (item.id === documentId ? document : item))
        );
        await loadProject();
        if (document.status === "READY" || document.status === "FAILED") {
          return;
        }
        pollDocument(documentId);
      } catch {
        // ignore
      }
    }, 2000);
  }

  async function handleUpload(file: File) {
    // 客户端先行校验，避免大文件上传触发连接中断（ERR_CONNECTION_RESET）
    if (!/\.pdf$/i.test(file.name)) {
      setUploadError("只支持 PDF 文件");
      return;
    }
    if (file.size > MAX_FILE_SIZE) {
      setUploadError("文件不能超过 100MB");
      return;
    }
    setUploadError(null);
    try {
      const document = await uploadDocument(file, projectId);
      setDocuments((current) => [document, ...current]);
      pollDocument(document.id);
      await loadProject();
    } catch (err) {
      setUploadError(err instanceof Error ? err.message : "上传失败，请重试");
    }
  }

  async function handleDeleteDocument(document: Document) {
    await deleteDocument(document.id);
    setDocuments((current) =>
      current.filter((item) => item.id !== document.id)
    );
    await loadProject();
  }

  async function handleCreateConversation() {
    const conversation = await createConversation(projectId);
    setConversations((current) => [conversation, ...current]);
    setSelectedConversation(conversation);
    setMessages([]);
    router.replace(`/projects/${projectId}?conv=${conversation.id}`);
    await loadProject();
  }

  async function handleSelectConversation(conversation: Conversation) {
    setSelectedConversation(conversation);
    setMessages([]);
    router.replace(`/projects/${projectId}?conv=${conversation.id}`);
    const items = await listMessages(conversation.id);
    setMessages(items);
    setMobileSidebarOpen(false);
  }

  async function handleSend(content: string) {
    if (!selectedConversation) return;
    setStreaming(true);
    setStreamingContent("");
    try {
      await streamChat(selectedConversation.id, content, {
        onDelta: (token) => setStreamingContent((current) => current + token),
        onDone: async () => {
          if (!selectedConversation) return;
          const items = await listMessages(selectedConversation.id);
          setMessages(items);
          setStreaming(false);
          setStreamingContent("");
          await loadConversations();
        }
      });
    } catch {
      setStreaming(false);
      setStreamingContent("");
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

  if (!user || !project) {
    return null;
  }

  return (
    <main className="grid-bg flex h-screen overflow-hidden bg-gradient-to-br from-[#050a15] to-[#081020]">
      <ProjectSidebar
        user={user}
        project={project}
        documents={documents}
        conversations={conversations}
        selectedConversationId={selectedConversation?.id ?? null}
        onUpload={handleUpload}
        onDeleteDocument={handleDeleteDocument}
        onCreateConversation={handleCreateConversation}
        onSelectConversation={handleSelectConversation}
        onLogout={handleLogout}
        mobileOpen={mobileSidebarOpen}
        onCloseMobile={() => setMobileSidebarOpen(false)}
      />

      <section className="flex min-w-0 flex-1 flex-col">
        <header className="flex min-h-[64px] items-center gap-3 border-b border-border bg-[rgba(6,12,26,0.78)] px-[clamp(16px,2.4vw,28px)] backdrop-blur-xl">
          <button
            type="button"
            onClick={() => setMobileSidebarOpen(true)}
            aria-label="打开侧边栏"
            className="flex h-9 w-9 items-center justify-center rounded-[9px] border border-[rgba(255,255,255,0.07)] bg-[rgba(255,255,255,0.03)] text-[rgba(180,205,240,0.62)] transition hover:bg-[rgba(255,255,255,0.07)] md:hidden"
          >
            <PanelLeft className="h-4 w-4" />
          </button>
          <div className="min-w-0 flex-1">
            <div className="truncate text-[0.84rem] font-bold text-[rgba(220,235,255,0.92)]">
              {project.name}
            </div>
            <div className="mt-1 flex items-center gap-[10px] font-mono text-[0.64rem] text-[rgba(150,185,235,0.42)]">
              <span>{project.documentCount} 个文档</span>
              <span>·</span>
              <span>{project.conversationCount} 个会话</span>
            </div>
          </div>
          <div className="flex shrink-0 items-center gap-[9px]">
            <button
              type="button"
              onClick={() => router.push("/projects")}
              className="inline-flex h-9 items-center gap-2 rounded-[9px] border border-[rgba(255,255,255,0.08)] bg-[rgba(255,255,255,0.03)] px-3 text-[0.78rem] font-semibold text-[rgba(200,220,250,0.72)] transition hover:bg-[rgba(255,255,255,0.065)]"
            >
              <ArrowLeft className="h-4 w-4" />
              <span className="hidden md:inline">返回项目列表</span>
            </button>
            <button
              type="button"
              onClick={handleCreateConversation}
              className="inline-flex h-9 items-center gap-2 rounded-[9px] border border-[rgba(255,255,255,0.08)] bg-[rgba(255,255,255,0.03)] px-3 text-[0.78rem] font-semibold text-[rgba(200,220,250,0.72)] transition hover:bg-[rgba(255,255,255,0.065)]"
            >
              <Plus className="h-4 w-4" />
              <span className="hidden md:inline">新对话</span>
            </button>
          </div>
        </header>

        {uploadError && (
          <div className="flex items-center justify-between gap-3 border-b border-[rgba(255,120,140,0.22)] bg-[rgba(255,80,100,0.08)] px-[clamp(16px,2.4vw,28px)] py-2.5 text-[0.78rem] text-[rgba(255,165,180,0.95)]">
            <span className="truncate">上传失败：{uploadError}</span>
            <button
              type="button"
              onClick={() => setUploadError(null)}
              aria-label="关闭提示"
              className="flex h-6 w-6 shrink-0 items-center justify-center rounded text-[rgba(255,165,180,0.6)] transition hover:bg-[rgba(255,80,100,0.15)] hover:text-[rgba(255,180,195,1)]"
            >
              <X className="h-4 w-4" />
            </button>
          </div>
        )}

        <ChatPanel
          messages={messages}
          streaming={streaming}
          streamingContent={streamingContent}
          onSend={handleSend}
          hasConversation={!!selectedConversation}
        />
      </section>
    </main>
  );
}
