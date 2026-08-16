"use client";

import { useState } from "react";
import { Loader2, Send, Sparkles } from "lucide-react";
import type { Message } from "@/lib/types";
import SourceCard from "./SourceCard";

interface ChatPanelProps {
  messages: Message[];
  streaming: boolean;
  streamingContent: string;
  onSend: (content: string) => Promise<void>;
  hasConversation: boolean;
}

export default function ChatPanel({
  messages,
  streaming,
  streamingContent,
  onSend,
  hasConversation
}: ChatPanelProps) {
  const [content, setContent] = useState("");

  async function handleSend() {
    const value = content.trim();
    if (!value || streaming || !hasConversation) {
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
    <section className="flex h-full min-h-0 flex-1 flex-col bg-[rgba(4,8,16,0.18)]">
      <div className="flex flex-1 flex-col gap-[18px] overflow-y-auto px-[clamp(16px,3vw,36px)] py-6">
        {!hasConversation && (
          <div className="flex flex-1 items-center justify-center">
            <div className="max-w-[420px] text-center">
              <div className="mx-auto mb-4 flex h-[52px] w-[52px] items-center justify-center rounded-[14px] border border-[rgba(80,140,255,0.28)] bg-[rgba(80,140,255,0.09)] text-[rgba(130,185,255,0.9)] shadow-[0_0_30px_rgba(60,120,255,0.14)]">
                <Sparkles className="h-6 w-6" />
              </div>
              <h2 className="font-serif text-base font-bold">开始向文档提问</h2>
              <p className="mt-2 text-[0.78rem] leading-[1.7] text-[rgba(160,190,230,0.45)]">
                选择会话或新建会话，输入问题即可获得带来源引用的 AI 回答。
              </p>
            </div>
          </div>
        )}

        {hasConversation &&
          allMessages.length === 0 &&
          !streaming && (
            <div className="flex flex-1 items-center justify-center">
              <div className="max-w-[420px] text-center">
                <div className="mx-auto mb-4 flex h-[52px] w-[52px] items-center justify-center rounded-[14px] border border-[rgba(80,140,255,0.28)] bg-[rgba(80,140,255,0.09)] text-[rgba(130,185,255,0.9)] shadow-[0_0_30px_rgba(60,120,255,0.14)]">
                  <Sparkles className="h-6 w-6" />
                </div>
                <h2 className="font-serif text-base font-bold">输入问题开始</h2>
                <p className="mt-2 text-[0.78rem] leading-[1.7] text-[rgba(160,190,230,0.45)]">
                  系统将基于项目内所有已就绪文档检索并生成带引用的回答。
                </p>
              </div>
            </div>
          )}

        {allMessages.map((message) => (
          <div
            key={message.id}
            className={`flex w-full items-start gap-[10px] ${
              message.role === "user" ? "justify-end" : ""
            }`}
          >
            {message.role === "assistant" && (
              <div className="flex h-[30px] w-[30px] shrink-0 items-center justify-center rounded-[9px] border border-[rgba(80,140,255,0.3)] bg-gradient-to-br from-[#1d3f7a] to-[#102a56] text-[rgba(150,200,255,0.9)]">
                <Sparkles className="h-4 w-4" />
              </div>
            )}
            <div
              className={`max-w-[min(780px,82%)] space-y-2 rounded-xl p-[15px] text-[0.82rem] leading-[1.75] ${
                message.role === "user"
                  ? "border border-[rgba(80,150,255,0.35)] bg-gradient-to-br from-[#2860d6] to-[#3a7fff] text-[rgba(235,245,255,0.97)] shadow-[0_4px_20px_rgba(40,100,255,0.24)]"
                  : "border border-border bg-[rgba(255,255,255,0.04)] text-[rgba(215,232,255,0.9)] shadow-[0_8px_26px_rgba(0,0,0,0.2)]"
              }`}
            >
              <p className="whitespace-pre-wrap break-words">
                {message.content || (streaming ? "正在思考…" : "")}
              </p>
              {message.sources.length > 0 && (
                <div className="grid gap-2">
                  {message.sources.map((source) => (
                    <SourceCard key={source.index} source={source} />
                  ))}
                </div>
              )}
            </div>
          </div>
        ))}
      </div>

      <form
        onSubmit={(e) => {
          e.preventDefault();
          handleSend();
        }}
        className="flex items-end gap-[10px] border-t border-border bg-[rgba(6,12,26,0.82)] px-[clamp(16px,3vw,36px)] py-3 backdrop-blur-xl"
      >
        <div className="flex flex-1 items-end gap-2 rounded-xl border border-[rgba(255,255,255,0.09)] bg-[rgba(255,255,255,0.035)] px-[14px] py-2 transition focus-within:border-[rgba(80,140,255,0.5)] focus-within:shadow-[0_0_0_3px_rgba(60,120,255,0.1)]">
          <textarea
            value={content}
            onChange={(event) => setContent(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter" && !event.shiftKey) {
                event.preventDefault();
                handleSend();
              }
            }}
            rows={1}
            placeholder={hasConversation ? "输入你的问题…" : "请先选择或新建会话"}
            disabled={!hasConversation}
            className="max-h-[120px] min-h-[40px] flex-1 resize-none border-0 bg-transparent text-[0.82rem] leading-[1.6] text-[rgba(225,238,255,0.9)] outline-none placeholder:text-[rgba(150,185,235,0.4)] disabled:cursor-not-allowed"
          />
          <button
            type="submit"
            aria-label="发送"
            disabled={!content.trim() || streaming || !hasConversation}
            className="flex h-[38px] w-[38px] shrink-0 items-center justify-center rounded-[10px] border border-[rgba(80,150,255,0.35)] bg-gradient-to-br from-[#2860d6] to-[#3a7fff] text-[rgba(235,245,255,0.96)] transition hover:brightness-110 disabled:cursor-not-allowed disabled:opacity-45"
          >
            {streaming ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <Send className="h-4 w-4" />
            )}
          </button>
        </div>
      </form>
    </section>
  );
}
