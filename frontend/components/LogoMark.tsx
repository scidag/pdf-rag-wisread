"use client";

import { FileText } from "lucide-react";

export default function LogoMark() {
  return (
    <div className="flex h-[34px] w-[34px] shrink-0 items-center justify-center rounded-[9px] border border-[rgba(80,140,255,0.35)] bg-gradient-to-br from-[#1e3a6e] to-[#0f2040] text-[rgba(150,200,255,0.9)] shadow-[0_0_18px_rgba(60,120,255,0.22)]">
      <FileText className="h-4 w-4" />
    </div>
  );
}
