"use client";

import { useState } from "react";
import { Loader2, Send } from "lucide-react";
import type { Message } from "@/lib/types";
import SourceCard from "./SourceCard";

interface ChatPanelProps {
  messages: Message[];
  streaming: boolean;
  streamingContent: string;
  onSend: (content: string) => Promise<void>;
}

export default function ChatPanel({
  messages,
  streaming,
  streamingContent,
  onSend
}: ChatPanelProps) {
  const [content, setContent] = useState("");

  async function handleSend() {
    const value = content.trim();
    if (!value || streaming) {
      return;
    }
    setContent("");
    await onSend(value);
  }

  const allMessages: Message[] = streaming
    ? [
        ...messages,
        {
          id: -1,
          role: "assistant",
          content: streamingContent,
          sources: [],
          createdAt: new Date().toISOString()
        }
      ]
    : messages;

  return (
    <section className="flex h-full min-h-[70vh] flex-col lg:min-h-0">
      <div className="flex-1 space-y-4 overflow-y-auto p-4 lg:p-6">
        {allMessages.length === 0 && (
          <div className="flex h-full items-center justify-center">
            <div className="text-center">
              <h2 className="text-lg font-semibold text-slate-800">
                开始向文档提问
              </h2>
              <p className="mt-1 text-sm text-slate-500">
                先选择文档并新建会话
              </p>
            </div>
          </div>
        )}

        {allMessages.map((message) => (
          <div
            key={message.id}
            className={`flex ${message.role === "user" ? "justify-end" : "justify-start"}`}
          >
            <div
              className={`max-w-[85%] space-y-2 rounded-lg p-3 ${
                message.role === "user"
                  ? "bg-emerald-600 text-white"
                  : "border border-slate-200 bg-white text-slate-800"
              }`}
            >
              <p className="whitespace-pre-wrap text-sm leading-6">
                {message.content ||
                  (streaming ? "正在思考..." : "")}
              </p>
              {message.sources.length > 0 && (
                <div className="space-y-2">
                  {message.sources.map((source) => (
                    <SourceCard key={source.index} source={source} />
                  ))}
                </div>
              )}
            </div>
          </div>
        ))}
      </div>

      <div className="border-t border-slate-200 bg-white p-4">
        <div className="flex items-end gap-2">
          <textarea
            value={content}
            onChange={(event) => setContent(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter" && !event.shiftKey) {
                event.preventDefault();
                handleSend();
              }
            }}
            rows={2}
            placeholder="输入你的问题..."
            className="min-h-[44px] flex-1 resize-none rounded-md border border-slate-300 px-3 py-2 text-sm outline-none focus:border-emerald-500 focus:ring-2 focus:ring-emerald-100"
          />
          <button
            type="button"
            aria-label="发送"
            onClick={handleSend}
            disabled={!content.trim() || streaming}
            className="flex h-[44px] w-[44px] shrink-0 items-center justify-center rounded-md bg-emerald-600 text-white transition hover:bg-emerald-700 disabled:opacity-50"
          >
            {streaming ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <Send className="h-4 w-4" />
            )}
          </button>
        </div>
      </div>
    </section>
  );
}
