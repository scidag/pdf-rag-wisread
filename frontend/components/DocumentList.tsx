"use client";

import { Trash2 } from "lucide-react";
import type { Document } from "@/lib/types";
import StatusBadge from "./StatusBadge";

interface DocumentListProps {
  documents: Document[];
  selectedDocumentId: number | null;
  onSelect: (document: Document) => void;
  onDelete: (document: Document) => void;
}

export default function DocumentList({
  documents,
  selectedDocumentId,
  onSelect,
  onDelete
}: DocumentListProps) {
  if (documents.length === 0) {
    return (
      <p className="rounded-md border border-dashed border-slate-300 px-3 py-4 text-center text-sm text-slate-500">
        还没有文档
      </p>
    );
  }

  return (
    <div className="space-y-2">
      {documents.map((document) => {
        const selected = document.id === selectedDocumentId;
        return (
          <div
            key={document.id}
            className={`w-full rounded-md border p-3 text-left transition ${
              selected
                ? "border-emerald-500 bg-emerald-50"
                : "border-slate-200 bg-white hover:border-slate-300"
            }`}
          >
            <div className="flex items-start justify-between gap-2">
              <button
                type="button"
                onClick={() => onSelect(document)}
                className="min-w-0 flex-1 text-left"
              >
                <span className="line-clamp-2 text-sm font-medium text-slate-800">
                  {document.filename}
                </span>
              </button>
              <button
                type="button"
                onClick={() => {
                  onDelete(document);
                }}
                className="shrink-0 text-slate-400 transition hover:text-red-600"
              >
                <Trash2 className="h-4 w-4" />
              </button>
            </div>
            <div className="mt-2 flex items-center gap-2">
              <StatusBadge status={document.status} />
              {document.pageCount != null && (
                <span className="text-xs text-slate-500">
                  {document.pageCount} 页
                </span>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
}
