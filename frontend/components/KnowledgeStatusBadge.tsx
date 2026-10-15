const KB_STATUS_MAP: Record<string, { label: string; cls: string; pulse?: boolean }> = {
  DRAFT: {
    label: "草稿",
    cls: "border-[rgba(170,200,240,0.28)] bg-[rgba(170,200,240,0.08)] text-[rgba(195,218,248,0.8)]"
  },
  PROCESSING: {
    label: "处理中",
    cls: "border-[rgba(245,177,78,0.32)] bg-[rgba(245,177,78,0.08)] text-[#f5b14e]",
    pulse: true
  },
  ACTIVE: {
    label: "已发布",
    cls: "border-[rgba(74,222,128,0.3)] bg-[rgba(74,222,128,0.08)] text-[#4ade80]"
  },
  ARCHIVED: {
    label: "已归档",
    cls: "border-[rgba(255,255,255,0.12)] bg-transparent text-[rgba(190,215,245,0.62)]"
  }
};

const DOC_STATUS_MAP: Record<string, { label: string; cls: string; pulse?: boolean }> = {
  UPLOADED: {
    label: "已上传",
    cls: "border-[rgba(138,180,255,0.32)] bg-[rgba(58,127,255,0.09)] text-[#8ab4ff]"
  },
  PROCESSING: {
    label: "解析中",
    cls: "border-[rgba(245,177,78,0.32)] bg-[rgba(245,177,78,0.08)] text-[#f5b14e]",
    pulse: true
  },
  INDEXED: {
    label: "已索引",
    cls: "border-[rgba(74,222,128,0.3)] bg-[rgba(74,222,128,0.08)] text-[#4ade80]"
  },
  FAILED: {
    label: "失败",
    cls: "border-[rgba(255,122,138,0.32)] bg-[rgba(255,122,138,0.08)] text-[#ff7a8a]"
  }
};

interface KnowledgeStatusBadgeProps {
  status: string;
  /** kb：知识库状态；doc：知识库文档状态 */
  kind: "kb" | "doc";
}

export default function KnowledgeStatusBadge({ status, kind }: KnowledgeStatusBadgeProps) {
  const fallback = kind === "kb" ? KB_STATUS_MAP.DRAFT : DOC_STATUS_MAP.UPLOADED;
  const item = (kind === "kb" ? KB_STATUS_MAP[status] : DOC_STATUS_MAP[status]) ?? fallback;
  return (
    <span
      className={`inline-flex shrink-0 items-center gap-[6px] rounded-full border px-[9px] py-[3px] font-mono text-[0.66rem] font-semibold tracking-[0.08em] ${item.cls}`}
    >
      <span
        className={`h-[6px] w-[6px] rounded-full ${item.pulse ? "animate-pulse" : ""}`}
        style={{ backgroundColor: "currentColor" }}
      />
      {item.label}
    </span>
  );
}
