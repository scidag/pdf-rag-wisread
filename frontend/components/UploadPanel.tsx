"use client";

import { useRef, useState } from "react";
import { Loader2, Upload } from "lucide-react";

interface UploadPanelProps {
  onUpload: (file: File) => Promise<void>;
}

export default function UploadPanel({ onUpload }: UploadPanelProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [loading, setLoading] = useState(false);

  async function handleFile(file: File | undefined) {
    if (!file) {
      return;
    }
    setLoading(true);
    try {
      await onUpload(file);
    } finally {
      setLoading(false);
    }
  }

  return (
    <div>
      <input
        ref={inputRef}
        type="file"
        accept="application/pdf"
        className="hidden"
        onChange={(event) => {
          handleFile(event.target.files?.[0]);
          event.target.value = "";
        }}
      />
      <button
        type="button"
        onClick={() => inputRef.current?.click()}
        disabled={loading}
        className="flex w-full items-center justify-center gap-2 rounded-md bg-slate-900 px-3 py-2 text-sm font-medium text-white transition hover:bg-slate-700 disabled:opacity-60"
      >
        {loading ? (
          <Loader2 className="h-4 w-4 animate-spin" />
        ) : (
          <Upload className="h-4 w-4" />
        )}
        上传 PDF
      </button>
    </div>
  );
}
