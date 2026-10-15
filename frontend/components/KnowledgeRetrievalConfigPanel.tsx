"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { AlertTriangle, Loader2, Save } from "lucide-react";
import {
  getKnowledgeRetrievalConfig,
  updateKnowledgeRetrievalConfig
} from "@/lib/knowledge-base";
import type { KnowledgeRetrievalConfig, KnowledgeRetrievalConfigUpdate } from "@/lib/knowledge-base";

const TOPK_MIN = 1;
const TOPK_MAX = 50;
const THRESHOLD_STEP = 0.05;
const SAVED_FLASH_MS = 2600;

interface KnowledgeRetrievalConfigPanelProps {
  knowledgeBaseId: number;
}

// 表单草稿（topK / rerankK 用字符串承接输入框中间态）
interface Draft {
  topK: string;
  threshold: number;
  rerankEnabled: boolean;
  rerankK: string;
}

// 将服务端配置解析为表单基线（后端字段可为 null，前端用默认值兜底保证表单可编辑）
function toBaseline(config: KnowledgeRetrievalConfig) {
  return {
    topK: config.topK ?? 10,
    threshold: config.similarityThreshold ?? 0.5,
    rerankEnabled: config.rerankEnabled ?? false,
    rerankK: config.rerankK ?? 3
  };
}

function toDraft(config: KnowledgeRetrievalConfig): Draft {
  const baseline = toBaseline(config);
  return {
    topK: String(baseline.topK),
    threshold: baseline.threshold,
    rerankEnabled: baseline.rerankEnabled,
    rerankK: String(baseline.rerankK)
  };
}

// 滑块已填充进度（对齐原型 --fill 渐变）
function rangeBackground(value: number, min: number, max: number) {
  const pct = ((value - min) / (max - min)) * 100;
  return {
    background: `linear-gradient(to right, #3a7fff ${pct}%, rgba(255,255,255,0.1) ${pct}%)`
  };
}

const rangeThumbClasses =
  "h-1 w-full cursor-pointer appearance-none rounded-full outline-none [&::-moz-range-thumb]:h-4 [&::-moz-range-thumb]:w-4 [&::-moz-range-thumb]:rounded-full [&::-moz-range-thumb]:border-[3px] [&::-moz-range-thumb]:border-[#3a7fff] [&::-moz-range-thumb]:bg-white [&::-webkit-slider-thumb]:h-4 [&::-webkit-slider-thumb]:w-4 [&::-webkit-slider-thumb]:appearance-none [&::-webkit-slider-thumb]:rounded-full [&::-webkit-slider-thumb]:border-[3px] [&::-webkit-slider-thumb]:border-[#3a7fff] [&::-webkit-slider-thumb]:bg-white [&::-webkit-slider-thumb]:shadow-[0_2px_10px_rgba(0,0,0,0.55)] [&::-webkit-slider-thumb]:transition-transform hover:[&::-webkit-slider-thumb]:scale-110";

export default function KnowledgeRetrievalConfigPanel({
  knowledgeBaseId
}: KnowledgeRetrievalConfigPanelProps) {
  const [baseline, setBaseline] = useState<{ topK: number; threshold: number; rerankEnabled: boolean; rerankK: number } | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [savedFlash, setSavedFlash] = useState(false);
  const savedTimerRef = useRef<number | null>(null);

  const applyConfig = (config: KnowledgeRetrievalConfig) => {
    setBaseline(toBaseline(config));
    setDraft(toDraft(config));
  };

  // 加载当前配置回显
  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    setSavedFlash(false);
    getKnowledgeRetrievalConfig(knowledgeBaseId)
      .then((config) => {
        if (!cancelled) applyConfig(config);
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : "检索配置加载失败，请重试");
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
      if (savedTimerRef.current !== null) {
        window.clearTimeout(savedTimerRef.current);
      }
    };
  }, [knowledgeBaseId]);

  const parsedTopK = draft ? Number.parseInt(draft.topK, 10) : NaN;
  const parsedRerankK = draft ? Number.parseInt(draft.rerankK, 10) : NaN;
  const topKValid = Number.isInteger(parsedTopK) && parsedTopK >= TOPK_MIN && parsedTopK <= TOPK_MAX;
  const rerankKValid =
    !draft?.rerankEnabled ||
    (Number.isInteger(parsedRerankK) &&
      parsedRerankK >= 1 &&
      Number.isInteger(parsedTopK) &&
      parsedRerankK <= parsedTopK);

  const dirty = useMemo(() => {
    if (!baseline || !draft) return false;
    return (
      parsedTopK !== baseline.topK ||
      draft.threshold !== baseline.threshold ||
      draft.rerankEnabled !== baseline.rerankEnabled ||
      parsedRerankK !== baseline.rerankK
    );
  }, [baseline, draft, parsedTopK, parsedRerankK]);

  // 脏状态：拦截页面关闭/刷新
  useEffect(() => {
    if (!dirty) return;
    const handler = (e: BeforeUnloadEvent) => {
      e.preventDefault();
      e.returnValue = "";
    };
    window.addEventListener("beforeunload", handler);
    return () => window.removeEventListener("beforeunload", handler);
  }, [dirty]);

  if (loading) {
    return (
      <div className="rounded-[14px] border border-border bg-panel p-6 backdrop-blur-[8px]">
        <SectionHeader />
        <div className="flex items-center justify-center gap-3 py-14 text-[0.82rem] text-[rgba(170,200,240,0.66)]">
          <Loader2 className="h-4 w-4 animate-spin text-[#3a7fff]" />
          正在加载检索配置…
        </div>
      </div>
    );
  }

  if (error && !draft) {
    return (
      <div className="rounded-[14px] border border-border bg-panel p-6 backdrop-blur-[8px]">
        <SectionHeader />
        <p
          role="alert"
          className="rounded-[10px] border border-[rgba(255,80,100,0.3)] bg-[rgba(255,80,100,0.08)] px-4 py-3 text-[0.8rem] text-[rgba(255,175,190,0.95)]"
        >
          {error}
        </p>
      </div>
    );
  }

  if (!draft || !baseline) {
    return null;
  }

  function handleTopKChange(value: string) {
    setDraft((d) => (d ? { ...d, topK: value } : d));
  }

  // 数字输入与滑块联动：滑块以合法钳制值呈现；下调 topK 时 rerank-k 实时联动收敛到 ≤ topK
  function handleTopKSlider(value: number) {
    setDraft((d) => {
      if (!d) return d;
      const next: Draft = { ...d, topK: String(value) };
      const rerankK = Number.parseInt(d.rerankK, 10);
      if (d.rerankEnabled && Number.isInteger(rerankK) && rerankK > value) {
        next.rerankK = String(value);
      }
      return next;
    });
  }

  function handleRerankToggle() {
    setDraft((d) => (d ? { ...d, rerankEnabled: !d.rerankEnabled } : d));
  }

  function handleReset() {
    if (!baseline || !draft) return;
    if (dirty && !window.confirm("有未保存的检索配置更改，确定放弃修改？")) {
      return;
    }
    setError(null);
    setSavedFlash(false);
    setDraft({
      topK: String(baseline.topK),
      threshold: baseline.threshold,
      rerankEnabled: baseline.rerankEnabled,
      rerankK: String(baseline.rerankK)
    });
  }

  async function handleSave() {
    if (!baseline || !draft) return;
    if (!dirty || saving || !topKValid || !rerankKValid) return;
    setSaving(true);
    setError(null);
    setSavedFlash(false);
    // 仅提交发生变更的字段（PATCH 部分更新语义）
    const payload: KnowledgeRetrievalConfigUpdate = {};
    if (parsedTopK !== baseline.topK) payload.topK = parsedTopK;
    if (draft.threshold !== baseline.threshold) payload.similarityThreshold = draft.threshold;
    if (draft.rerankEnabled !== baseline.rerankEnabled) payload.rerankEnabled = draft.rerankEnabled;
    if (parsedRerankK !== baseline.rerankK) payload.rerankK = parsedRerankK;
    try {
      const updated = await updateKnowledgeRetrievalConfig(knowledgeBaseId, payload);
      applyConfig(updated);
      setSavedFlash(true);
      if (savedTimerRef.current !== null) {
        window.clearTimeout(savedTimerRef.current);
      }
      savedTimerRef.current = window.setTimeout(() => setSavedFlash(false), SAVED_FLASH_MS);
    } catch (err) {
      setError(err instanceof Error ? err.message : "配置保存失败，请重试");
    } finally {
      setSaving(false);
    }
  }

  const topKSliderValue = Number.isInteger(parsedTopK)
    ? Math.min(TOPK_MAX, Math.max(TOPK_MIN, parsedTopK))
    : TOPK_MIN;

  return (
    <div className="rounded-[14px] border border-border bg-panel p-[24px_26px] backdrop-blur-[8px]">
      <SectionHeader />

      {error && (
        <p
          role="alert"
          className="mb-4 rounded-[10px] border border-[rgba(255,80,100,0.3)] bg-[rgba(255,80,100,0.08)] px-4 py-3 text-[0.8rem] text-[rgba(255,175,190,0.95)]"
        >
          {error}
        </p>
      )}

      {/* TopK 召回数量：数字输入 + 滑块联动 */}
      <div className="border-b border-[rgba(255,255,255,0.07)] py-5 first:pt-1">
        <div className="mb-3.5 flex items-center justify-between gap-4">
          <div className="text-[0.88rem] font-bold text-[#e8f0ff]">
            TopK 召回数量
            <span className="mt-[3px] block text-[0.72rem] font-normal text-[rgba(170,200,240,0.66)]">
              每次向量检索返回的切片数量上限，越大召回越全、耗时越高
            </span>
          </div>
          <input
            type="number"
            min={TOPK_MIN}
            max={TOPK_MAX}
            value={draft.topK}
            onChange={(e) => handleTopKChange(e.target.value)}
            aria-label="TopK 召回数量"
            className="w-[76px] rounded-[10px] border border-[rgba(255,255,255,0.12)] bg-[rgba(255,255,255,0.03)] px-3 py-[7px] text-center font-mono text-[0.82rem] text-[#e8f0ff] outline-none transition focus:border-[rgba(58,127,255,0.55)]"
          />
        </div>
        <input
          type="range"
          min={TOPK_MIN}
          max={TOPK_MAX}
          step={1}
          value={topKSliderValue}
          onChange={(e) => handleTopKSlider(Number(e.target.value))}
          aria-label="TopK 召回数量滑块"
          className={rangeThumbClasses}
          style={rangeBackground(topKSliderValue, TOPK_MIN, TOPK_MAX)}
        />
        <div className="mt-2 flex justify-between font-mono text-[0.66rem] text-[rgba(160,185,225,0.6)]">
          <span>1</span>
          <span>25</span>
          <span>50</span>
        </div>
        {!topKValid && (
          <p className="mt-2 text-[0.74rem] font-semibold text-[rgba(255,150,165,0.95)]">
            top_k 取值范围为 {TOPK_MIN}~{TOPK_MAX}
          </p>
        )}
      </div>

      {/* 相似度阈值：滑块 0~1 */}
      <div className="border-b border-[rgba(255,255,255,0.07)] py-5">
        <div className="mb-3.5 flex items-center justify-between gap-4">
          <div className="text-[0.88rem] font-bold text-[#e8f0ff]">
            相似度阈值
            <span className="mt-[3px] block text-[0.72rem] font-normal text-[rgba(170,200,240,0.66)]">
              低于该距离的切片将被过滤，不进入重排与生成
            </span>
          </div>
          <span className="min-w-[52px] text-right font-mono text-[1.25rem] font-semibold text-[#8ab4ff]">
            {draft.threshold.toFixed(2)}
          </span>
        </div>
        <input
          type="range"
          min={0}
          max={1}
          step={THRESHOLD_STEP}
          value={draft.threshold}
          onChange={(e) =>
            setDraft((d) => (d ? { ...d, threshold: Number(e.target.value) } : d))
          }
          aria-label="相似度阈值"
          className={rangeThumbClasses}
          style={rangeBackground(draft.threshold, 0, 1)}
        />
        <div className="mt-2 flex justify-between font-mono text-[0.66rem] text-[rgba(160,185,225,0.6)]">
          <span>0.00</span>
          <span>0.50</span>
          <span>1.00</span>
        </div>
      </div>

      {/* Rerank 重排开关 + rerank-k */}
      <div className="py-5">
        <div className="flex items-center justify-between gap-4">
          <div className="text-[0.88rem] font-bold text-[#e8f0ff]">
            启用 Rerank 重排
            <span className="mt-[3px] block text-[0.72rem] font-normal text-[rgba(170,200,240,0.66)]">
              对召回结果二次精排，提升引用相关度
            </span>
          </div>
          <button
            type="button"
            role="switch"
            aria-checked={draft.rerankEnabled}
            aria-label="启用 Rerank 重排"
            onClick={handleRerankToggle}
            className="relative h-[23px] w-[42px] shrink-0"
          >
            <span
              className={`absolute inset-0 rounded-full border transition-colors duration-200 ${
                draft.rerankEnabled
                  ? "border-[rgba(58,127,255,0.5)] bg-[rgba(58,127,255,0.35)]"
                  : "border-[rgba(255,255,255,0.12)] bg-[rgba(255,255,255,0.12)]"
              }`}
            />
            <span
              className={`absolute top-[2.5px] left-[3px] h-4 w-4 rounded-full transition-transform duration-200 ${
                draft.rerankEnabled
                  ? "translate-x-[18px] bg-white"
                  : "bg-[rgba(170,200,240,0.66)]"
              }`}
            />
          </button>
        </div>
        <div className="mt-3 flex items-start gap-2 rounded-[9px] border border-[rgba(245,177,78,0.2)] bg-[rgba(245,177,78,0.07)] px-[13px] py-[10px] text-[0.75rem] leading-[1.65] text-[rgba(245,177,78,0.95)]">
          <AlertTriangle className="mt-[2px] h-3 w-3 shrink-0" />
          <span>当前为占位实现（仅按向量距离排序并截断），接入真实重排模型后生效</span>
        </div>
        <div className="mt-3.5 flex items-center gap-2.5">
          <label htmlFor="kb-rerank-k" className="text-[0.78rem] text-[rgba(200,220,250,0.82)]">
            rerank-k
          </label>
          <input
            id="kb-rerank-k"
            type="number"
            min={1}
            max={Number.isInteger(parsedTopK) ? parsedTopK : TOPK_MAX}
            value={draft.rerankK}
            disabled={!draft.rerankEnabled}
            onChange={(e) => setDraft((d) => (d ? { ...d, rerankK: e.target.value } : d))}
            className="w-[76px] rounded-[10px] border border-[rgba(255,255,255,0.12)] bg-[rgba(255,255,255,0.03)] px-3 py-[7px] text-center font-mono text-[0.82rem] text-[#e8f0ff] outline-none transition focus:border-[rgba(58,127,255,0.55)] disabled:cursor-not-allowed disabled:opacity-35"
          />
          {!rerankKValid && (
            <p className="text-[0.74rem] font-semibold text-[rgba(255,150,165,0.95)]">
              {Number.isInteger(parsedRerankK) && parsedRerankK < 1
                ? "rerank_k 取值范围最小为 1"
                : "rerank_k 不能大于 top_k"}
            </p>
          )}
        </div>
      </div>

      {/* 保存栏 */}
      <div className="mt-1.5 flex items-center justify-between gap-4 border-t border-[rgba(255,255,255,0.07)] pt-5">
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={handleSave}
            disabled={!dirty || saving || !topKValid || !rerankKValid}
            className="inline-flex h-[36px] items-center gap-2 rounded-[10px] bg-accent px-4 text-[0.8rem] font-semibold text-white transition hover:bg-[#2860d6] hover:shadow-[0_4px_22px_rgba(58,127,255,0.38)] disabled:cursor-not-allowed disabled:opacity-45 disabled:shadow-none"
          >
            {saving ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Save className="h-3.5 w-3.5" />}
            保存配置
          </button>
          <button
            type="button"
            onClick={handleReset}
            disabled={!dirty || saving}
            className="inline-flex h-[36px] items-center rounded-[10px] border border-[rgba(255,255,255,0.12)] bg-[rgba(255,255,255,0.03)] px-[14px] text-[0.8rem] font-semibold text-[rgba(225,238,255,0.9)] transition hover:border-[rgba(58,127,255,0.42)] hover:bg-[rgba(58,127,255,0.08)] disabled:cursor-not-allowed disabled:opacity-40"
          >
            重置
          </button>
        </div>
        <span
          className={`text-[0.72rem] font-semibold ${
            savedFlash
              ? "text-[rgba(148,220,170,0.95)]"
              : dirty
                ? "text-[rgba(245,177,78,0.95)]"
                : "text-[rgba(160,185,225,0.6)]"
          }`}
          role="status"
        >
          {savedFlash ? "配置已保存" : dirty ? "有未保存的更改" : "与当前服务端配置一致"}
        </span>
      </div>
    </div>
  );
}

function SectionHeader() {
  return (
    <div className="mb-4">
      <div className="font-mono text-[0.66rem] tracking-[0.22em] text-[rgba(160,185,225,0.6)]">
        04 · RETRIEVAL
      </div>
      <h2 className="mt-1.5 font-serif text-base font-bold text-[rgba(220,235,255,0.92)]">
        检索配置
      </h2>
      <p className="mt-1.5 text-[0.76rem] leading-[1.7] text-[rgba(170,200,240,0.66)]">
        修改后对新发起的问答立即生效，历史会话不受影响。
      </p>
    </div>
  );
}
