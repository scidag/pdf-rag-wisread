"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { FileText, Loader2, X } from "lucide-react";

/**
 * 统一的多类型文档预览弹层（纯客户端渲染）。
 *
 * 为什么不用 iframe 直塞 blob URL：浏览器只能原生内联渲染 PDF 与纯文本，
 * docx/xlsx 会直接触发下载、md 无排版。因此按文件类型分流：
 * - pdf：浏览器原生 viewer（iframe + object URL）
 * - docx：动态 import docx-preview，renderAsync 渲染 Word 分页页面
 * - xlsx：动态 import xlsx，sheet_to_json 解析后渲染表格（支持多工作表切换）
 * - md：动态 import marked，渲染为完整暗色排版（样式见 globals.css .md-preview）
 * - txt：纯文本渲染
 * 渲染库均按需动态 import，避免增大首屏包体；也未引入服务端解析依赖。
 */

type PreviewKind = "pdf" | "docx" | "xlsx" | "md" | "txt" | "unsupported";

interface DocumentPreviewProps {
  filename: string;
  fileType: string | null;
  blob: Blob;
  onClose: () => void;
}

/** xlsx 预览最多渲染的行数，防止超大工作表拖垮页面 */
const MAX_XLSX_ROWS = 500;

function resolveKind(fileType: string | null, filename: string): PreviewKind {
  const raw = fileType && /^[a-z0-9]+$/i.test(fileType) ? fileType : filename.split(".").pop() ?? "";
  switch (raw.toLowerCase()) {
    case "pdf":
      return "pdf";
    case "docx":
      return "docx";
    case "xlsx":
      return "xlsx";
    case "md":
    case "markdown":
      return "md";
    case "txt":
      return "txt";
    default:
      return "unsupported";
  }
}

interface SheetPreview {
  name: string;
  rows: string[][];
}

export default function DocumentPreview({
  filename,
  fileType,
  blob,
  onClose
}: DocumentPreviewProps) {
  const kind = useMemo(() => resolveKind(fileType, filename), [fileType, filename]);

  // pdf 专用 object URL，组件卸册时自动回收
  const objectUrl = useMemo(() => URL.createObjectURL(blob), [blob]);
  useEffect(() => () => URL.revokeObjectURL(objectUrl), [objectUrl]);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [html, setHtml] = useState<string | null>(null);
  const [text, setText] = useState<string | null>(null);
  const [sheets, setSheets] = useState<SheetPreview[] | null>(null);
  const [activeSheet, setActiveSheet] = useState(0);
  const docxContainerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        setError(null);
        if (kind === "pdf" || kind === "unsupported") {
          setLoading(false);
          return;
        }
        setLoading(true);
        if (kind === "docx") {
          const { renderAsync } = await import("docx-preview");
          const container = docxContainerRef.current;
          if (!container || cancelled) return;
          container.innerHTML = "";
          await renderAsync(blob, container, undefined, {
            inWrapper: true,
            breakPages: true,
            ignoreWidth: false,
            ignoreHeight: false,
            useBase64URL: true
          });
        } else if (kind === "xlsx") {
          const XLSX = await import("xlsx");
          const workbook = XLSX.read(await blob.arrayBuffer(), { type: "array" });
          const parsed = workbook.SheetNames.map((name) => ({
            name,
            rows: XLSX.utils
              .sheet_to_json<string[]>(workbook.Sheets[name], { header: 1, raw: false, defval: "" })
              .map((row) => row.map((cell) => String(cell ?? "")))
              .filter((row) => row.some((cell) => cell !== ""))
          }));
          if (cancelled) return;
          setSheets(parsed);
          setActiveSheet(0);
        } else if (kind === "md") {
          const { marked } = await import("marked");
          const source = await blob.text();
          const rendered = await marked.parse(source, { gfm: true, breaks: true });
          if (cancelled) return;
          setHtml(typeof rendered === "string" ? rendered : String(rendered));
        } else if (kind === "txt") {
          const source = await blob.text();
          if (cancelled) return;
          setText(source);
        }
        if (!cancelled) setLoading(false);
      } catch (err) {
        if (cancelled) return;
        setError(err instanceof Error ? err.message : "文档解析失败，无法预览");
        setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [kind, blob]);

  const sheet = sheets?.[activeSheet];
  const truncated = (sheet?.rows.length ?? 0) > MAX_XLSX_ROWS;

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={`预览 ${filename}`}
      className="fixed inset-0 z-50 flex flex-col bg-black/75 p-[clamp(8px,2vw,24px)]"
    >
      <div className="flex h-12 shrink-0 items-center gap-3 rounded-t-xl border border-b-0 border-[rgba(255,255,255,0.1)] bg-[rgba(8,16,30,0.98)] px-4">
        <FileText className="h-4 w-4 shrink-0 text-[rgba(130,185,255,0.9)]" />
        <span
          className="truncate text-[0.8rem] font-semibold text-[rgba(220,235,255,0.92)]"
          title={filename}
        >
          {filename}
        </span>
        <button
          type="button"
          onClick={onClose}
          aria-label="关闭预览"
          className="ml-auto flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-[rgba(180,205,240,0.62)] transition hover:bg-[rgba(255,255,255,0.07)] hover:text-[rgba(230,240,255,0.95)]"
        >
          <X className="h-4 w-4" />
        </button>
      </div>
      <div className="relative min-h-0 w-full flex-1 overflow-hidden rounded-b-xl border border-[rgba(255,255,255,0.1)] bg-[rgba(5,10,20,0.97)]">
        {kind === "pdf" && (
          <iframe title={`${filename} 预览`} src={objectUrl} className="h-full w-full bg-white" />
        )}
        {kind === "docx" && (
          <div ref={docxContainerRef} className="docx-scroll h-full w-full overflow-auto" />
        )}
        {kind === "xlsx" && sheet && (
          <div className="h-full w-full overflow-auto p-4">
            {sheets && sheets.length > 1 && (
              <div className="mb-3 flex flex-wrap gap-1.5">
                {sheets.map((item, index) => (
                  <button
                    key={item.name}
                    type="button"
                    onClick={() => setActiveSheet(index)}
                    className={`rounded-[8px] border px-3 py-1.5 text-[0.72rem] font-semibold transition ${
                      index === activeSheet
                        ? "border-[rgba(58,127,255,0.55)] bg-[rgba(58,127,255,0.16)] text-[rgba(230,240,255,0.95)]"
                        : "border-[rgba(255,255,255,0.1)] text-[rgba(180,205,240,0.68)] hover:border-[rgba(255,255,255,0.2)] hover:text-[rgba(230,240,255,0.9)]"
                    }`}
                  >
                    {item.name}
                  </button>
                ))}
              </div>
            )}
            <div className="overflow-auto rounded-lg border border-[rgba(255,255,255,0.08)]">
              <table className="w-full border-collapse text-[0.76rem]">
                <thead>
                  <tr>
                    {sheet.rows[0]?.map((cell, index) => (
                      <th
                        key={index}
                        className="border border-[rgba(255,255,255,0.08)] bg-[rgba(58,127,255,0.14)] px-2.5 py-1.5 text-left font-semibold whitespace-nowrap text-[rgba(230,240,255,0.95)]"
                      >
                        {cell}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {sheet.rows.slice(1, MAX_XLSX_ROWS).map((row, rowIndex) => (
                    <tr key={rowIndex} className="even:bg-[rgba(255,255,255,0.02)]">
                      {row.map((cell, index) => (
                        <td
                          key={index}
                          className="border border-[rgba(255,255,255,0.08)] px-2.5 py-1.5 whitespace-nowrap text-[rgba(215,230,255,0.82)]"
                        >
                          {cell}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {truncated && (
              <p className="mt-2 font-mono text-[0.66rem] text-[rgba(150,185,235,0.55)]">
                工作表行数较多，仅显示前 {MAX_XLSX_ROWS} 行
              </p>
            )}
          </div>
        )}
        {kind === "md" && html !== null && (
          <div className="h-full w-full overflow-auto">
            <div className="md-preview" dangerouslySetInnerHTML={{ __html: html }} />
          </div>
        )}
        {kind === "txt" && text !== null && (
          <pre className="h-full w-full overflow-auto p-6 font-mono text-[0.8rem] leading-relaxed break-words whitespace-pre-wrap text-[rgba(215,230,255,0.85)]">
            {text}
          </pre>
        )}
        {kind === "unsupported" && (
          <div className="flex h-full items-center justify-center px-6 text-center text-[0.8rem] text-[rgba(160,190,230,0.55)]">
            暂不支持在浏览器中预览该文件类型（{filename.split(".").pop()?.toLowerCase() ?? "未知"}）
          </div>
        )}
        {loading && (
          <div className="absolute inset-0 flex items-center justify-center bg-[rgba(5,10,20,0.88)]">
            <Loader2 className="h-6 w-6 animate-spin text-accent" />
          </div>
        )}
        {!loading && error && (
          <div className="absolute inset-0 flex items-center justify-center bg-[rgba(5,10,20,0.88)] px-6 text-center text-[0.8rem] text-[rgba(255,165,180,0.9)]">
            文档解析失败：{error}
          </div>
        )}
      </div>
    </div>
  );
}
