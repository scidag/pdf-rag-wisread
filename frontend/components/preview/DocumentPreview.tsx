"use client";

import {
  Component,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { Download, Loader2, TriangleAlert } from "lucide-react";
import type { Document } from "@/lib/types";

/** xlsx 预览最多渲染的行数，防止大表卡死页面 */
const XLSX_MAX_ROWS = 500;

/**
 * 文档预览统一入口：按 fileType 分支选择渲染方式。
 * - pdf / txt / null / 未知类型：iframe 原生渲染（与旧版行为一致，null 对齐后端 PDF 兜底逻辑）
 * - md / docx / xlsx：按需动态加载前端渲染库，失败时回退为下载提示
 * 所有渲染分支由 ErrorBoundary 包裹，任一库渲染崩溃都不会影响页面其余部分。
 */
export default function DocumentPreview({
  document,
  blob,
}: {
  document: Document;
  blob: Blob;
}) {
  return (
    <div className="flex min-h-0 w-full flex-1 flex-col overflow-hidden rounded-b-xl border border-[rgba(255,255,255,0.1)] bg-white">
      <PreviewErrorBoundary document={document} blob={blob}>
        <PreviewBranch document={document} blob={blob} />
      </PreviewErrorBoundary>
    </div>
  );
}

function PreviewBranch({ document, blob }: { document: Document; blob: Blob }) {
  const fileType = (document.fileType ?? "").toLowerCase();
  switch (fileType) {
    case "md":
      return <MarkdownPreview document={document} blob={blob} />;
    case "docx":
      return <DocxPreview document={document} blob={blob} />;
    case "xlsx":
      return <XlsxPreview document={document} blob={blob} />;
    default:
      return <IframePreview document={document} blob={blob} />;
  }
}

/* ---------- pdf / txt / 未知类型：iframe 原生渲染 ---------- */

function IframePreview({ document, blob }: { document: Document; blob: Blob }) {
  const url = useMemo(() => URL.createObjectURL(blob), [blob]);
  useEffect(() => () => URL.revokeObjectURL(url), [url]);
  return (
    <iframe
      title={`${document.filename} 预览`}
      src={url}
      className="min-h-0 w-full flex-1 border-0 bg-white"
    />
  );
}

/* ---------- md：marked 渲染 + DOMPurify 净化 ---------- */

function MarkdownPreview({
  document,
  blob,
}: {
  document: Document;
  blob: Blob;
}) {
  const [html, setHtml] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setHtml(null);
    setFailed(false);
    (async () => {
      try {
        const { marked } = await import("marked");
        const { default: DOMPurify } = await import("dompurify");
        const raw = await blob.text();
        if (cancelled) return;
        // 上传内容不可信：必须净化后再渲染，防止 md 内嵌脚本 XSS
        setHtml(DOMPurify.sanitize(await marked.parse(raw)));
      } catch {
        if (!cancelled) setFailed(true);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [blob]);

  if (failed) {
    return <PreviewFallback document={document} blob={blob} message="Markdown 解析失败" />;
  }
  if (html === null) {
    return <PreviewLoading />;
  }
  return (
    <div className="min-h-0 w-full flex-1 overflow-auto bg-white p-6">
      {/* mx-auto max-w-4xl：内容列限宽并水平居中，白底仍铺满预览区 */}
      <div
        className="markdown-preview mx-auto max-w-4xl"
        // 内容已过 DOMPurify 净化，此处安全
        dangerouslySetInnerHTML={{ __html: html }}
      />
    </div>
  );
}

/* ---------- docx：docx-preview 可视化渲染 ---------- */

function DocxPreview({ document, blob }: { document: Document; blob: Blob }) {
  const containerRef = useRef<HTMLDivElement>(null);
  const styleRef = useRef<HTMLDivElement>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setFailed(false);
    (async () => {
      try {
        const docxModule = await import("docx-preview");
        const buffer = await blob.arrayBuffer();
        if (cancelled || !containerRef.current || !styleRef.current) return;
        containerRef.current.innerHTML = "";
        // styleContainer 单独挂载，避免 docx 内部样式泄漏到全局页面
        await docxModule.renderAsync(buffer, containerRef.current, styleRef.current);
      } catch {
        if (!cancelled) setFailed(true);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [blob]);

  if (failed) {
    return <PreviewFallback document={document} blob={blob} message="Word 文档解析失败" />;
  }
  return (
    <div className="min-h-0 w-full flex-1 overflow-auto bg-white">
      <div ref={styleRef} />
      <div ref={containerRef} />
    </div>
  );
}

/* ---------- xlsx：SheetJS 解析为 HTML 表格 ---------- */

function XlsxPreview({ document, blob }: { document: Document; blob: Blob }) {
  const workbookRef = useRef<import("xlsx").WorkBook | null>(null);
  const [sheetNames, setSheetNames] = useState<string[] | null>(null);
  const [active, setActive] = useState(0);
  const [table, setTable] = useState<{
    rows: string[][];
    truncated: boolean;
    total: number;
  } | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setFailed(false);
    (async () => {
      try {
        const XLSX = await import("xlsx");
        const buffer = await blob.arrayBuffer();
        const workbook = XLSX.read(buffer, { type: "array" });
        if (cancelled) return;
        workbookRef.current = workbook;
        setSheetNames(workbook.SheetNames);
        setActive(0);
      } catch {
        if (!cancelled) setFailed(true);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [blob]);

  useEffect(() => {
    const workbook = workbookRef.current;
    if (!sheetNames || !workbook || !sheetNames[active]) return;
    let cancelled = false;
    (async () => {
      try {
        // 模块已被缓存，二次动态导入无额外开销
        const XLSX = await import("xlsx");
        const rows = XLSX.utils.sheet_to_json<string[]>(
          workbook.Sheets[sheetNames[active]],
          { header: 1, raw: false, defval: "", blankrows: false }
        );
        if (cancelled) return;
        setTable({
          rows: rows
            .slice(0, XLSX_MAX_ROWS)
            .map((row) => row.map((cell) => String(cell ?? ""))),
          truncated: rows.length > XLSX_MAX_ROWS,
          total: rows.length,
        });
      } catch {
        if (!cancelled) setFailed(true);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [active, sheetNames]);

  if (failed) {
    return <PreviewFallback document={document} blob={blob} message="表格文件解析失败" />;
  }
  if (!sheetNames || !table) {
    return <PreviewLoading />;
  }
  return (
    <div className="flex min-h-0 w-full flex-1 flex-col bg-white">
      {sheetNames.length > 1 && (
        <div className="flex shrink-0 gap-1 overflow-x-auto border-b border-gray-200 px-3 py-1.5">
          {sheetNames.map((name, index) => (
            <button
              key={name}
              type="button"
              onClick={() => setActive(index)}
              className={`shrink-0 rounded px-2.5 py-1 text-xs font-medium transition ${
                index === active
                  ? "bg-blue-50 text-blue-700"
                  : "text-gray-500 hover:bg-gray-100"
              }`}
            >
              {name}
            </button>
          ))}
        </div>
      )}
      <div className="min-h-0 flex-1 overflow-auto p-2">
        <table className="border-collapse text-xs">
          <tbody>
            {table.rows.map((row, rowIndex) => (
              <tr key={rowIndex} className={rowIndex === 0 ? "bg-gray-50 font-semibold" : ""}>
                {row.map((cell, cellIndex) => (
                  <td
                    key={cellIndex}
                    className="max-w-[240px] truncate border border-gray-200 px-2.5 py-1 text-gray-700"
                    title={cell}
                  >
                    {cell}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {table.truncated && (
        <div className="shrink-0 border-t border-gray-200 px-3 py-1.5 text-[0.7rem] text-gray-500">
          内容较多，仅显示前 {XLSX_MAX_ROWS} 行（共 {table.total} 行）
        </div>
      )}
    </div>
  );
}

/* ---------- 通用状态组件 ---------- */

function PreviewLoading() {
  return (
    <div className="flex min-h-0 flex-1 items-center justify-center bg-white">
      <Loader2 className="h-5 w-5 animate-spin text-gray-400" />
    </div>
  );
}

function PreviewFallback({
  document,
  blob,
  message,
}: {
  document: Document;
  blob: Blob;
  message?: string;
}) {
  const url = useMemo(() => URL.createObjectURL(blob), [blob]);
  useEffect(() => () => URL.revokeObjectURL(url), [url]);
  return (
    <div className="flex min-h-0 flex-1 flex-col items-center justify-center gap-3 bg-white">
      <TriangleAlert className="h-8 w-8 text-gray-300" />
      <p className="text-sm text-gray-500">{message ?? "该文件类型暂不支持在线预览"}</p>
      <a
        href={url}
        download={document.filename}
        className="inline-flex items-center gap-1.5 rounded-lg border border-blue-200 bg-blue-50 px-4 py-2 text-xs font-semibold text-blue-700 transition hover:bg-blue-100"
      >
        <Download className="h-3.5 w-3.5" />
        下载查看
      </a>
    </div>
  );
}

/* ---------- 渲染库崩溃兜底 ---------- */

class PreviewErrorBoundary extends Component<
  { document: Document; blob: Blob; children: ReactNode },
  { hasError: boolean }
> {
  state = { hasError: false };

  static getDerivedStateFromError() {
    return { hasError: true };
  }

  render() {
    if (this.state.hasError) {
      return (
        <PreviewFallback
          document={this.props.document}
          blob={this.props.blob}
          message="预览渲染出错"
        />
      );
    }
    return this.props.children;
  }
}
