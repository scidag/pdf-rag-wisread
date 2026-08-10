"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { Loader2 } from "lucide-react";
import { logout, restoreSession } from "@/lib/auth-store";
import {
  createConversation,
  deleteDocument,
  getDocument,
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
  User
} from "@/lib/types";
import ChatPanel from "@/components/ChatPanel";
import Sidebar from "@/components/Sidebar";

export default function WorkspacePage() {
  const router = useRouter();
  const [user, setUser] = useState<User | null>(null);
  const [loadingAuth, setLoadingAuth] = useState(true);
  const [documents, setDocuments] = useState<Document[]>([]);
  const [selectedDocument, setSelectedDocument] = useState<Document | null>(
    null
  );
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [selectedConversation, setSelectedConversation] =
    useState<Conversation | null>(null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [streaming, setStreaming] = useState(false);
  const [streamingContent, setStreamingContent] = useState("");

  const loadDocuments = useCallback(async () => {
    const docs = await listDocuments();
    setDocuments(docs);
    setSelectedDocument((current) => current ?? docs[0] ?? null);
  }, []);

  useEffect(() => {
    let cancelled = false;
    async function init() {
      const restored = await restoreSession();
      if (!restored) {
        router.replace("/login");
        return;
      }
      if (cancelled) {
        return;
      }
      setUser(restored);
      try {
        await loadDocuments();
      } finally {
        setLoadingAuth(false);
      }
    }
    init();
    return () => {
      cancelled = true;
    };
  }, [loadDocuments, router]);

  useEffect(() => {
    if (!selectedDocument) {
      setConversations([]);
      setSelectedConversation(null);
      setMessages([]);
      return;
    }
    let cancelled = false;
    listConversations(selectedDocument.id)
      .then((items) => {
        if (!cancelled) {
          setConversations(items);
          setSelectedConversation(null);
          setMessages([]);
        }
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [selectedDocument]);

  async function handleUpload(file: File) {
    const document = await uploadDocument(file);
    setDocuments((current) => [document, ...current]);
    pollDocument(document.id);
  }

  function pollDocument(documentId: number) {
    setTimeout(async () => {
      const document = await getDocument(documentId);
      setDocuments((current) =>
        current.map((item) => (item.id === documentId ? document : item))
      );
      if (document.status === "READY" || document.status === "FAILED") {
        setSelectedDocument((current) => current ?? document);
        return;
      }
      pollDocument(documentId);
    }, 2000);
  }

  async function handleDeleteDocument(document: Document) {
    await deleteDocument(document.id);
    setDocuments((current) =>
      current.filter((item) => item.id !== document.id)
    );
    if (selectedDocument?.id === document.id) {
      setSelectedDocument(null);
    }
  }

  async function handleCreateConversation() {
    if (!selectedDocument) {
      return;
    }
    const conversation = await createConversation(selectedDocument.id);
    setConversations((current) => [conversation, ...current]);
    setSelectedConversation(conversation);
    setMessages([]);
  }

  async function handleSelectConversation(conversation: Conversation) {
    setSelectedConversation(conversation);
    const items = await listMessages(conversation.id);
    setMessages(items);
  }

  async function handleSend(content: string) {
    if (!selectedConversation) {
      return;
    }
    setStreaming(true);
    setStreamingContent("");
    try {
      await streamChat(selectedConversation.id, content, {
        onDelta: (token) => setStreamingContent((current) => current + token),
        onDone: async () => {
          const items = await listMessages(selectedConversation.id);
          setMessages(items);
          setStreaming(false);
          setStreamingContent("");
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

  if (loadingAuth) {
    return (
      <main className="flex min-h-screen items-center justify-center bg-slate-100">
        <Loader2 className="h-6 w-6 animate-spin text-emerald-600" />
      </main>
    );
  }

  if (!user) {
    return null;
  }

  return (
    <main className="bg-slate-100 lg:flex lg:min-h-screen">
      <Sidebar
        user={user}
        documents={documents}
        selectedDocumentId={selectedDocument?.id ?? null}
        conversations={conversations}
        selectedConversationId={selectedConversation?.id ?? null}
        onUpload={handleUpload}
        onSelectDocument={setSelectedDocument}
        onDeleteDocument={handleDeleteDocument}
        onCreateConversation={handleCreateConversation}
        onSelectConversation={handleSelectConversation}
        onLogout={handleLogout}
      />
      <div className="flex-1 lg:min-h-screen">
        <ChatPanel
          messages={messages}
          streaming={streaming}
          streamingContent={streamingContent}
          onSend={handleSend}
        />
      </div>
    </main>
  );
}
