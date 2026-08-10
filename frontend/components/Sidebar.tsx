"use client";

import { FileText, LogOut } from "lucide-react";
import type {
  Conversation,
  Document,
  User
} from "@/lib/types";
import ConversationList from "./ConversationList";
import DocumentList from "./DocumentList";
import UploadPanel from "./UploadPanel";

interface SidebarProps {
  user: User;
  documents: Document[];
  selectedDocumentId: number | null;
  conversations: Conversation[];
  selectedConversationId: number | null;
  onUpload: (file: File) => Promise<void>;
  onSelectDocument: (document: Document) => void;
  onDeleteDocument: (document: Document) => void;
  onCreateConversation: () => void;
  onSelectConversation: (conversation: Conversation) => void;
  onLogout: () => void;
}

export default function Sidebar({
  user,
  documents,
  selectedDocumentId,
  conversations,
  selectedConversationId,
  onUpload,
  onSelectDocument,
  onDeleteDocument,
  onCreateConversation,
  onSelectConversation,
  onLogout
}: SidebarProps) {
  return (
    <aside className="border-b border-slate-200 bg-white lg:min-h-screen lg:w-80 lg:border-r lg:border-b-0">
      <div className="flex items-center justify-between border-b border-slate-200 p-4">
        <div className="flex items-center gap-2">
          <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-emerald-600 text-white">
            <FileText className="h-4 w-4" />
          </div>
          <div>
            <p className="text-sm font-semibold text-slate-900">智阅 Wisread</p>
            <p className="text-xs text-slate-500">{user.username}</p>
          </div>
        </div>
        <button
          type="button"
          onClick={onLogout}
          className="flex h-8 w-8 items-center justify-center rounded-md text-slate-400 transition hover:bg-slate-100 hover:text-slate-700"
          title="退出登录"
        >
          <LogOut className="h-4 w-4" />
        </button>
      </div>

      <div className="space-y-6 p-4">
        <UploadPanel onUpload={onUpload} />

        <div>
          <h2 className="mb-2 text-sm font-semibold text-slate-700">文档</h2>
          <DocumentList
            documents={documents}
            selectedDocumentId={selectedDocumentId}
            onSelect={onSelectDocument}
            onDelete={onDeleteDocument}
          />
        </div>

        <ConversationList
          conversations={conversations}
          selectedConversationId={selectedConversationId}
          onCreate={onCreateConversation}
          onSelect={onSelectConversation}
        />
      </div>
    </aside>
  );
}
