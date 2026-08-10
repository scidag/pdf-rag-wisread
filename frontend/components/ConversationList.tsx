"use client";

import { MessageSquare, Plus } from "lucide-react";
import type { Conversation } from "@/lib/types";

interface ConversationListProps {
  conversations: Conversation[];
  selectedConversationId: number | null;
  onCreate: () => void;
  onSelect: (conversation: Conversation) => void;
}

export default function ConversationList({
  conversations,
  selectedConversationId,
  onCreate,
  onSelect
}: ConversationListProps) {
  return (
    <div>
      <div className="mb-2 flex items-center justify-between">
        <h2 className="text-sm font-semibold text-slate-700">会话</h2>
        <button
          type="button"
          onClick={onCreate}
          className="flex items-center gap-1 rounded-md px-2 py-1 text-xs font-medium text-emerald-700 hover:bg-emerald-50"
        >
          <Plus className="h-3.5 w-3.5" />
          新建
        </button>
      </div>
      {conversations.length === 0 ? (
        <p className="text-sm text-slate-500">选择文档后新建会话</p>
      ) : (
        <div className="space-y-1">
          {conversations.map((conversation) => {
            const selected = conversation.id === selectedConversationId;
            return (
              <button
                key={conversation.id}
                type="button"
                onClick={() => onSelect(conversation)}
                className={`flex w-full items-center gap-2 rounded-md px-2 py-2 text-left text-sm transition ${
                  selected
                    ? "bg-emerald-50 text-emerald-800"
                    : "text-slate-600 hover:bg-slate-100"
                }`}
              >
                <MessageSquare className="h-4 w-4 shrink-0" />
                <span className="line-clamp-1">{conversation.title}</span>
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}
