const STATUS_MAP: Record<string, { label: string; className: string }> = {
  UPLOADED: { label: "已上传", className: "bg-slate-100 text-slate-600" },
  PROCESSING: { label: "处理中", className: "bg-amber-100 text-amber-700" },
  READY: { label: "完成", className: "bg-emerald-100 text-emerald-700" },
  FAILED: { label: "失败", className: "bg-red-100 text-red-700" }
};

export default function StatusBadge({ status }: { status: string }) {
  const item = STATUS_MAP[status] ?? {
    label: status,
    className: "bg-slate-100 text-slate-600"
  };
  return (
    <span
      className={`inline-flex items-center rounded-md px-2 py-0.5 text-xs font-medium ${item.className}`}
    >
      {item.label}
    </span>
  );
}
