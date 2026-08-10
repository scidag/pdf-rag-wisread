import { BookOpen } from "lucide-react";
import type { Source } from "@/lib/types";

export default function SourceCard({ source }: { source: Source }) {
  return (
    <div className="rounded-md border border-slate-200 bg-slate-50 p-3">
      <div className="mb-1 flex items-center gap-2 text-xs font-medium text-slate-600">
        <BookOpen className="h-3.5 w-3.5" />
        <span>来源 [{source.index}]</span>
        <span className="text-slate-400">
          第 {source.pageStart}
          {source.pageEnd !== source.pageStart
            ? `-${source.pageEnd}`
            : ""}{" "}
          页
        </span>
      </div>
      <p className="text-sm leading-6 text-slate-700">{source.snippet}</p>
    </div>
  );
}
