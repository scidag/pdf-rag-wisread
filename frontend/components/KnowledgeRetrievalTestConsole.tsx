"use client";

import { useEffect, useRef, useState } from "react";
import type { ReactNode } from "react";
import { AlertTriangle, Loader2, Play } from "lucide-react";
import { runKnowledgeRetrievalTest } from "@/lib/knowledge-base";
import type { KnowledgeRetrievalTestResult } from "@/lib/knowledge-base";
import { formatSourceLocation } from "@/lib/document-format";

const STAGE_INTERVAL_MS = 450; // 运行动画：阶段逐个点亮的间隔

// 分阶段管线（对齐原型 01 改写 → 02 召回 → 03 重排 → 04 答案）
const STAGES = [
  { num: "01", title: "查询改写" },
  { num: "02", title: "向量召回" },
  { num: "03", title: "Rerank 重排" },
  { num: "04", title: "最终答案" }
] as const;

/* ---------- 简单 markdown 渲染（先转义防注入，再支持加粗/行内代码/换行/引用上标） ---------- */

const CITE_SUP_STYLE =
  "color:#3a7fff;font-size:10px;vertical-align:super;padding:0 3px;font-family:'IBM Plex Mono',monospace;";

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function renderInline(text: string): string {
  return text
    .replace(/\*\*([^*\n]+)\*\*/g, "<strong>$1</strong>")
    .replace(/`([^`\n]+)`/g, "<code>$1</code>")
    .replace(/\[(\d{1,2})\]/g, `<sup style="${CITE_SUP_STYLE}">[$1]</sup>`);
}

function renderAnswerHtml(raw: string): string {
  return escapeHtml(raw)
    .split(/\n{2,}/)
    .map((para) => `<p>${renderInline(para).replace(/\n/g, "<br />")}</p>`)
    .join("");
}

/* ---------- used_config 回显的小件 ---------- */

function ConfigKey({ children }: { children: ReactNode }) {
  return <span className="text-[#8ab4ff]">{children}</span>;
}

function ConfigValue({ children }: { children: ReactNode }) {
  return <span className="text-[#4ade80]">{children}</span>;
}

export default function KnowledgeRetrievalTestConsole({
  knowledgeBaseId
}: {
  knowledgeBaseId: number;
}) {
  const [question, setQuestion] = useState("");
  const [running, setRunning] = useState(false);
  const [stage, setStage] = useState(-1); // -1 未运行；0~3 已点亮的最新阶段；4 = 全部完成
  const [result, setResult] = useState<KnowledgeRetrievalTestResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const timerRef = useRef<number | null>(null);

  // 卸载时清理运行动画定时器
  useEffect(() => {
    return () => {
      if (timerRef.current !== null) {
        window.clearInterval(timerRef.current);
      }
    };
  }, []);

  async function handleRun() {
    const trimmed = question.trim();
    if (!trimmed) {
      setError("请输入测试问题");
      return;
    }
    if (timerRef.current !== null) {
      window.clearInterval(timerRef.current);
    }
    setError(null);
    setResult(null);
    setRunning(true);
    setStage(0);
    timerRef.current = window.setInterval(() => {
      setStage((s) => Math.min(s + 1, STAGES.length - 1));
    }, STAGE_INTERVAL_MS);
    try {
      const res = await runKnowledgeRetrievalTest(knowledgeBaseId, trimmed);
      setResult(res);
      setStage(STAGES.length);
    } catch (err) {
      setError(err instanceof Error ? err.message : "测试运行失败，请重试");
      setStage(-1);
    } finally {
      if (timerRef.current !== null) {
        window.clearInterval(timerRef.current);
        timerRef.current = null;
      }
      setRunning(false);
    }
  }

  const timings = result?.timings ?? null;
  const totalMs = timings
    ? timings.rewriteMs + timings.recallMs + timings.rerankMs + timings.answerMs
    : null;
  const stageLatencies = timings
    ? [`${timings.rewriteMs}ms`, `${timings.recallMs}ms`, `${timings.rerankMs}ms`, `${timings.answerMs}ms`]
    : null;

  return (
    <div className="rounded-[14px] border border-border bg-panel p-[24px_26px] backdrop-blur-[8px]">
      {/* 区块头 */}
      <div className="mb-4">
        <div className="font-mono text-[0.66rem] tracking-[0.22em] text-[rgba(160,185,225,0.6)]">
          05 · RAG DEBUG
        </div>
        <h2 className="mt-1.5 font-serif text-base font-bold text-[rgba(220,235,255,0.92)]">
          RAG 测试
        </h2>
        <p className="mt-1.5 text-[0.76rem] leading-[1.7] text-[rgba(170,200,240,0.66)]">
          完整回放一次问答的四个阶段：查询改写 → 向量召回 → Rerank 重排 → 最终答案，配置与命中结果全部透明。
        </p>
      </div>

      {/* 问题输入 + 运行 */}
      <div className="mb-6 flex flex-wrap gap-3">
        <input
          type="text"
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !running) handleRun();
          }}
          placeholder="输入测试问题，例如：如何部署生产环境？"
          disabled={running}
          className="min-w-0 flex-1 rounded-[10px] border border-[rgba(255,255,255,0.12)] bg-[rgba(255,255,255,0.03)] px-[14px] py-[9px] text-[0.84rem] text-[#e8f0ff] outline-none transition placeholder:text-[rgba(160,185,225,0.45)] focus:border-[rgba(58,127,255,0.55)] disabled:opacity-60"
        />
        <button
          type="button"
          onClick={handleRun}
          disabled={running}
          className="inline-flex h-[38px] items-center gap-2 rounded-[10px] bg-accent px-4 text-[0.8rem] font-semibold text-white transition hover:bg-[#2860d6] hover:shadow-[0_4px_22px_rgba(58,127,255,0.38)] disabled:cursor-not-allowed disabled:opacity-60 disabled:shadow-none"
        >
          {running ? (
            <Loader2 className="h-3.5 w-3.5 animate-spin" />
          ) : (
            <Play className="h-3 w-3 fill-current" />
          )}
          {running ? "运行中…" : "运行"}
        </button>
      </div>

      {error && (
        <p
          role="alert"
          className="mb-5 rounded-[10px] border border-[rgba(255,80,100,0.3)] bg-[rgba(255,80,100,0.08)] px-4 py-3 text-[0.8rem] text-[rgba(255,175,190,0.95)]"
        >
          {error}
        </p>
      )}

      {/* 分阶段管线 */}
      {stage < 0 ? (
        <div className="rounded-[12px] border border-dashed border-[rgba(255,255,255,0.1)] bg-[rgba(255,255,255,0.015)] px-5 py-10 text-center text-[0.8rem] leading-[1.8] text-[rgba(170,200,240,0.66)]">
          输入问题并点击「运行」，按当前检索配置演练
          <span className="mx-1 font-mono text-[rgba(138,180,255,0.9)]">改写 → 召回 → 重排 → 答案</span>
          全链路，查看各阶段命中明细与耗时。
        </div>
      ) : (
        <div>
          {STAGES.map((meta, index) => {
            const done = stage > index;
            const active = stage === index;
            const hasResult = stage >= STAGES.length;
            const latency = hasResult && stageLatencies ? stageLatencies[index] : null;
            return (
              <div
                key={meta.num}
                className={`relative pb-9 pl-[62px] last:pb-1 ${index < STAGES.length - 1 ? "before:absolute before:top-[46px] before:bottom-1.5 before:left-[21px] before:w-px before:bg-gradient-to-b before:from-[rgba(255,255,255,0.12)] before:to-[rgba(255,255,255,0.03)] before:content-['']" : ""}`}
              >
                <div
                  className={`absolute top-0 left-0 flex h-[42px] w-[42px] items-center justify-center rounded-[12px] border font-mono text-[0.94rem] font-semibold transition-colors duration-300 ${
                    active && running
                      ? "animate-pulse border-[rgba(58,127,255,0.6)] bg-[rgba(58,127,255,0.14)] text-[#8ab4ff]"
                      : done || hasResult
                        ? "border-[rgba(58,127,255,0.4)] bg-[rgba(58,127,255,0.08)] text-[#8ab4ff]"
                        : "border-[rgba(255,255,255,0.12)] bg-[#060c1a] text-[#3a7fff]"
                  }`}
                >
                  {meta.num}
                </div>
                <div className="flex flex-wrap items-baseline gap-3">
                  <span className="text-[0.9rem] font-bold text-[#e8f0ff]">{meta.title}</span>
                  {latency !== null ? (
                    <span className="font-mono text-[0.69rem] tracking-[0.04em] text-[rgba(160,185,225,0.6)]">
                      {latency}
                    </span>
                  ) : active && running ? (
                    <span className="inline-flex items-center gap-1.5 font-mono text-[0.69rem] text-[rgba(138,180,255,0.85)]">
                      <Loader2 className="h-3 w-3 animate-spin" />
                      执行中…
                    </span>
                  ) : null}
                  {index === 2 && result?.rerank?.strategy === "placeholder" && hasResult && (
                    <span className="inline-flex items-center gap-1.5 rounded-full border border-[rgba(245,177,78,0.35)] bg-[rgba(245,177,78,0.1)] px-2.5 py-[2px] font-mono text-[0.62rem] font-semibold tracking-[0.08em] text-[rgba(245,177,78,0.95)]">
                      <span className="h-1.5 w-1.5 rounded-full bg-[rgba(245,177,78,0.9)]" />
                      占位实现
                    </span>
                  )}
                </div>
                {hasResult && result && (
                  <div className="mt-3.5">{renderStageBody(index, result)}</div>
                )}
              </div>
            );
          })}
        </div>
      )}

      {/* 总耗时 */}
      {result && totalMs !== null && (
        <div className="mt-5 flex flex-wrap gap-2.5 border-t border-[rgba(255,255,255,0.07)] pt-4">
          <span className="rounded-[8px] border border-[rgba(255,255,255,0.12)] bg-[rgba(255,255,255,0.03)] px-3 py-[6px] font-mono text-[0.7rem] text-[rgba(200,220,250,0.82)]">
            总耗时 <b className="font-semibold text-[#8ab4ff]">{totalMs}ms</b>
          </span>
          <span className="rounded-[8px] border border-[rgba(255,255,255,0.12)] bg-[rgba(255,255,255,0.03)] px-3 py-[6px] font-mono text-[0.7rem] text-[rgba(200,220,250,0.82)]">
            引用 <b className="font-semibold text-[#8ab4ff]">{result.citations?.length ?? 0}</b> 条
          </span>
        </div>
      )}
    </div>
  );
}

/* ---------- 各阶段正文 ---------- */

function renderStageBody(index: number, result: KnowledgeRetrievalTestResult) {
  switch (index) {
    case 0:
      return <RewriteBody result={result} />;
    case 1:
      return <RecallBody result={result} />;
    case 2:
      return <RerankBody result={result} />;
    case 3:
      return <AnswerBody result={result} />;
    default:
      return null;
  }
}

function RewriteBody({ result }: { result: KnowledgeRetrievalTestResult }) {
  const rewrite = result.rewrite;
  if (!rewrite) {
    return <p className="text-[0.78rem] text-[rgba(170,200,240,0.66)]">无改写信息。</p>;
  }
  return (
    <div className="flex flex-col gap-2">
      <span className="inline-flex items-center gap-2 self-start rounded-[8px] border border-[rgba(255,255,255,0.12)] bg-[rgba(255,255,255,0.03)] px-3 py-[7px] font-mono text-[0.75rem] text-[rgba(200,220,250,0.82)]">
        <span className="text-[0.62rem] tracking-[0.1em] text-[rgba(160,185,225,0.6)]">RAW</span>
        {rewrite.originalQuestion || "—"}
      </span>
      <span className="inline-flex items-center gap-2 self-start rounded-[8px] border border-[rgba(255,255,255,0.12)] bg-[rgba(255,255,255,0.03)] px-3 py-[7px] font-mono text-[0.75rem] text-[rgba(200,220,250,0.82)]">
        <span className="text-[0.62rem] tracking-[0.1em] text-[rgba(160,185,225,0.6)]">
          {rewrite.rewritten ? "REWRITE" : "PASSTHROUGH"}
        </span>
        {rewrite.rewrittenQuestion || "—"}
      </span>
      {!rewrite.rewritten && (
        <span className="text-[0.72rem] text-[rgba(170,200,240,0.66)]">
          检索测试无多轮历史，原问题直接透传进入向量检索。
        </span>
      )}
    </div>
  );
}

function RecallBody({ result }: { result: KnowledgeRetrievalTestResult }) {
  const recall = result.recall ?? [];
  const usedConfig = result.usedConfig;
  return (
    <div>
      <p className="mb-3 font-mono text-[0.72rem] text-[rgba(200,220,250,0.82)]">
        命中 <b className="font-semibold text-[#8ab4ff]">{recall.length}</b> 条
        {usedConfig?.topK != null && <span className="ml-2">top_k={usedConfig.topK}</span>}
        {usedConfig?.similarityThreshold != null && (
          <span className="ml-2">阈值={usedConfig.similarityThreshold}</span>
        )}
      </p>
      {recall.length === 0 ? (
        <p className="text-[0.78rem] text-[rgba(170,200,240,0.66)]">
          未召回任何切片（命中为空或全部低于相似度阈值）。
        </p>
      ) : (
        <div className="flex flex-col gap-2">
          {recall.map((item, i) => (
            <div
              key={item.chunkId ?? `recall-${i}`}
              className="grid grid-cols-[34px_minmax(0,1fr)_72px] items-baseline gap-3.5 rounded-[10px] border border-[rgba(255,255,255,0.07)] bg-[rgba(255,255,255,0.03)] px-3.5 py-[11px]"
            >
              <span className="font-mono text-[0.69rem] text-[rgba(160,185,225,0.6)]">
                #{String(i + 1).padStart(2, "0")}
              </span>
              <div className="min-w-0">
                <div className="truncate text-[0.78rem] font-semibold text-[#e8f0ff]" title={item.filename ?? undefined}>
                  {item.filename || "未知文档"}
                  <span className="ml-2 font-mono text-[0.69rem] font-normal text-[rgba(160,185,225,0.6)]">
                    {formatSourceLocation(item)}
                  </span>
                </div>
                {item.snippet && (
                  <p className="mt-1 line-clamp-1 text-[0.75rem] leading-[1.65] text-[rgba(170,200,240,0.66)]">
                    {item.snippet}
                  </p>
                )}
              </div>
              <span className="text-right font-mono text-[0.7rem] text-[rgba(200,220,250,0.82)]">
                dist {item.distance.toFixed(3)}
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function RerankBody({ result }: { result: KnowledgeRetrievalTestResult }) {
  const rerank = result.rerank;
  const recallCount = result.recall?.length ?? 0;
  if (recallCount === 0) {
    return <p className="text-[0.78rem] text-[rgba(170,200,240,0.66)]">召回为空，未执行重排。</p>;
  }
  if (!rerank?.enabled) {
    return (
      <p className="text-[0.78rem] text-[rgba(170,200,240,0.66)]">
        本次未启用重排，召回结果按向量距离升序直接截断。
      </p>
    );
  }
  return (
    <div className="rounded-[10px] border border-[rgba(245,177,78,0.2)] bg-[rgba(245,177,78,0.07)] px-[13px] py-[10px] text-[0.78rem] leading-[1.65] text-[rgba(245,177,78,0.95)]">
      {rerank.note || "重排为占位实现：未接入重排模型，仅按向量距离排序并按 rerank_k 截断。"}
    </div>
  );
}

function AnswerBody({ result }: { result: KnowledgeRetrievalTestResult }) {
  if (result.rejected) {
    return (
      <div className="grid grid-cols-1 items-start gap-5 lg:grid-cols-[minmax(0,1fr)_280px]">
        <div className="flex items-start gap-3 rounded-[10px] border border-[rgba(245,177,78,0.25)] bg-[rgba(245,177,78,0.07)] px-4 py-4">
          <AlertTriangle className="mt-[3px] h-4 w-4 shrink-0 text-[rgba(245,177,78,0.95)]" />
          <div>
            <p className="text-[0.86rem] font-bold text-[rgba(245,177,78,0.98)]">
              未在知识库中找到与问题相关的内容
            </p>
            {result.answer && (
              <p className="mt-1.5 text-[0.78rem] leading-[1.7] text-[rgba(200,220,250,0.78)]">
                {result.answer}
              </p>
            )}
          </div>
        </div>
        <UsedConfigPanel usedConfig={result.usedConfig} />
      </div>
    );
  }
  return (
    <div className="grid grid-cols-1 items-start gap-5 lg:grid-cols-[minmax(0,1fr)_280px]">
      <div className="min-w-0">
        {result.answer && (
          <div
            className="text-[0.84rem] leading-[1.95] text-[#e8f0ff] [&_code]:rounded-[6px] [&_code]:bg-[rgba(255,255,255,0.06)] [&_code]:px-1.5 [&_code]:py-0.5 [&_p+p]:mt-2.5"
            dangerouslySetInnerHTML={{ __html: renderAnswerHtml(result.answer) }}
          />
        )}
        {result.citations && result.citations.length > 0 && (
          <div className="mt-4">
            <div className="mb-2 font-mono text-[0.66rem] tracking-[0.14em] text-[rgba(160,185,225,0.6)]">
              CITATIONS · 引用来源
            </div>
            <div className="flex flex-col gap-2">
              {result.citations.map((citation) => (
                <div
                  key={citation.index}
                  className="rounded-[10px] border border-[rgba(58,127,255,0.22)] bg-[rgba(58,127,255,0.06)] px-3 py-2.5"
                >
                  <div className="mb-1 truncate font-mono text-[0.68rem] font-semibold text-[#8ab4ff]">
                    [{citation.index}] {citation.filename || "未知文档"}
                    <span className="ml-2 font-normal text-[rgba(138,180,255,0.75)]">
                      {formatSourceLocation(citation)}
                    </span>
                  </div>
                  {citation.snippet && (
                    <p className="line-clamp-2 text-[0.72rem] leading-[1.65] text-[rgba(170,200,240,0.72)]">
                      {citation.snippet}
                    </p>
                  )}
                </div>
              ))}
            </div>
          </div>
        )}
      </div>
      {/* used_config 回显（本次测试实际使用的检索配置快照） */}
      <UsedConfigPanel usedConfig={result.usedConfig} />
    </div>
  );
}

function UsedConfigPanel({
  usedConfig
}: {
  usedConfig: KnowledgeRetrievalTestResult["usedConfig"];
}) {
  return (
    <div className="overflow-hidden rounded-[12px] border border-[rgba(255,255,255,0.12)] bg-[#060c1a]">
      <div className="border-b border-[rgba(255,255,255,0.07)] bg-[rgba(255,255,255,0.03)] px-3.5 py-2.5 font-mono text-[0.64rem] tracking-[0.14em] text-[rgba(160,185,225,0.6)]">
        USED_CONFIG
      </div>
      <pre className="overflow-x-auto px-4 py-3.5 font-mono text-[0.72rem] leading-[1.9] text-[rgba(200,220,250,0.82)]">
        {usedConfig ? (
          <>
            {"{"}
            {"\n"}  <ConfigKey>"top_k"</ConfigKey>: {usedConfig.topK ?? "null"}
            {",\n"}  <ConfigKey>"similarity_threshold"</ConfigKey>:{" "}
            {usedConfig.similarityThreshold ?? "null"}
            {",\n"}  <ConfigKey>"rerank_enabled"</ConfigKey>:{" "}
            <ConfigValue>{String(usedConfig.rerankEnabled ?? false)}</ConfigValue>
            {",\n"}  <ConfigKey>"rerank_k"</ConfigKey>: {usedConfig.rerankK ?? "null"}
            {"\n"}{"}"}
          </>
        ) : (
          "—"
        )}
      </pre>
    </div>
  );
}
