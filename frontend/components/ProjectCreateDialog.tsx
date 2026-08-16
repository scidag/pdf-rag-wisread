"use client";

import { useState } from "react";
import { X } from "lucide-react";

interface ProjectCreateDialogProps {
  open: boolean;
  onClose: () => void;
  onCreate: (name: string, description?: string) => Promise<void>;
}

export default function ProjectCreateDialog({
  open,
  onClose,
  onCreate
}: ProjectCreateDialogProps) {
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [submitting, setSubmitting] = useState(false);

  if (!open) {
    return null;
  }

  async function handleSubmit() {
    const trimmed = name.trim();
    if (!trimmed || submitting) {
      return;
    }
    setSubmitting(true);
    try {
      await onCreate(trimmed, description.trim() || undefined);
      setName("");
      setDescription("");
      onClose();
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4">
      <div className="w-full max-w-md overflow-hidden rounded-2xl border border-border bg-[rgba(6,12,26,0.95)] shadow-2xl backdrop-blur-xl">
        <div className="h-px bg-gradient-to-r from-transparent via-[rgba(80,140,255,0.5)] to-transparent" />
        <div className="p-6">
          <div className="mb-5 flex items-center justify-between">
            <h2 className="font-serif text-lg font-bold text-[rgba(220,235,255,0.95)]">
              新建项目
            </h2>
            <button
              type="button"
              onClick={onClose}
              aria-label="关闭"
              className="flex h-8 w-8 items-center justify-center rounded-lg text-[rgba(180,205,240,0.62)] transition hover:bg-[rgba(255,255,255,0.07)] hover:text-[rgba(230,240,255,0.95)]"
            >
              <X className="h-4 w-4" />
            </button>
          </div>
          <div className="space-y-3">
            <div>
              <label className="mb-1.5 block text-[0.74rem] font-semibold text-[rgba(170,200,240,0.55)]">
                项目名称
              </label>
              <input
                type="text"
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="例如：产品白皮书解读"
                autoFocus
                className="w-full rounded-[10px] border border-[rgba(255,255,255,0.07)] bg-[rgba(255,255,255,0.03)] px-4 py-[11px] text-[0.86rem] text-[rgba(225,235,255,0.9)] outline-none transition focus:border-[rgba(80,140,255,0.55)] focus:bg-[rgba(255,255,255,0.055)] focus:shadow-[0_0_0_3px_rgba(60,120,255,0.1)]"
              />
            </div>
            <div>
              <label className="mb-1.5 block text-[0.74rem] font-semibold text-[rgba(170,200,240,0.55)]">
                描述（可选）
              </label>
              <textarea
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder="项目用途说明"
                rows={3}
                className="w-full resize-none rounded-[10px] border border-[rgba(255,255,255,0.07)] bg-[rgba(255,255,255,0.03)] px-4 py-[11px] text-[0.86rem] text-[rgba(225,235,255,0.9)] outline-none transition focus:border-[rgba(80,140,255,0.55)] focus:bg-[rgba(255,255,255,0.055)] focus:shadow-[0_0_0_3px_rgba(60,120,255,0.1)]"
              />
            </div>
          </div>
          <div className="mt-6 flex justify-end gap-2">
            <button
              type="button"
              onClick={onClose}
              className="h-9 rounded-[9px] border border-[rgba(255,255,255,0.08)] bg-[rgba(255,255,255,0.03)] px-4 text-[0.78rem] font-semibold text-[rgba(200,220,250,0.72)] transition hover:bg-[rgba(255,255,255,0.065)]"
            >
              取消
            </button>
            <button
              type="button"
              onClick={handleSubmit}
              disabled={!name.trim() || submitting}
              className="h-9 items-center gap-2 rounded-[10px] border border-[rgba(80,150,255,0.35)] bg-gradient-to-br from-[#2860d6] to-[#3a7fff] px-5 text-[0.82rem] font-bold text-[rgba(230,242,255,0.96)] shadow-[0_4px_20px_rgba(40,100,255,0.28)] transition hover:brightness-110 disabled:opacity-50"
            >
              {submitting ? "创建中…" : "创建项目"}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
