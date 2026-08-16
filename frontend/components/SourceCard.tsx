import { BookOpen } from "lucide-react";
import type { Source } from "@/lib/types";

export default function SourceCard({ source }: { source: Source }) {
  return (
    <div className="rounded-lg border border-[rgba(255,255,255,0.07)] bg-[rgba(255,255,255,0.025)] p-[11px]">
      <div className="mb-[5px] flex items-center gap-1.5 font-mono text-[0.64rem] font-semibold text-[rgba(130,185,255,0.85)]">
        <BookOpen className="h-3.5 w-3.5" />
        <span>[{source.index}]</span>
        {source.filename && (
          <span className="truncate text-[rgba(130,185,255,0.7)]">
            {source.filename}
          </span>
        )}
        <span className="text-[rgba(130,185,255,0.6)]">
          第 {source.pageStart}
          {source.pageEnd !== source.pageStart ? `-${source.pageEnd}` : ""} 页
        </span>
      </div>
      <p className="text-[0.7rem] leading-[1.6] text-[rgba(165,195,235,0.6)]">
        {source.snippet}
      </p>
    </div>
  );
}
