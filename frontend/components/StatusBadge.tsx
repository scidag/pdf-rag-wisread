const STATUS_MAP: Record<string, { label: string; cls: string }> = {
  UPLOADED: {
    label: "已上传",
    cls: "border-[rgba(160,185,225,0.24)] bg-[rgba(160,185,225,0.1)] text-[rgba(160,185,225,0.8)]"
  },
  PROCESSING: {
    label: "处理中",
    cls: "border-[rgba(245,177,78,0.26)] bg-[rgba(245,177,78,0.1)] text-[#f5b14e]"
  },
  READY: {
    label: "完成",
    cls: "border-[rgba(74,222,128,0.24)] bg-[rgba(74,222,128,0.1)] text-[#4ade80]"
  },
  FAILED: {
    label: "失败",
    cls: "border-[rgba(255,122,138,0.26)] bg-[rgba(255,122,138,0.1)] text-[#ff7a8a]"
  }
};

export default function StatusBadge({ status }: { status: string }) {
  const item = STATUS_MAP[status] ?? STATUS_MAP.UPLOADED;
  return (
    <span
      className={`inline-flex items-center gap-[5px] rounded-full border px-2 py-1 text-[0.66rem] font-bold ${item.cls}`}
    >
      <span
        className="h-[5px] w-[5px] rounded-full"
        style={{ backgroundColor: "currentColor" }}
      />
      {item.label}
    </span>
  );
}
