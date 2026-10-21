package com.wisread.model;

/**
 * 补检改写结果（Agentic RAG，内存模型）。
 * 状态机（spec 4.2.5）：杜绝"返回原字符串被当成功改写再查一遍"——
 * 只有 REWRITTEN 携带有效新检索词并允许继续补检；PASSTHROUGH 与 INVALID 都直接结束补检。
 */
public record RetryRewriteResult(
        // 结果类型
        Type type,
        // 改写后的检索词（仅 REWRITTEN 非空）
        String text
) {

    /**
     * 结果类型枚举。
     */
    public enum Type {
        /** 有效新检索词（非空且 ≠ 输入），允许继续补检 */
        REWRITTEN,
        /** LLM 调用失败/超时/异常，直接结束补检（使用首轮结果，不回退原问题重查） */
        PASSTHROUGH,
        /** LLM 返回空白或与输入相同，直接结束补检（不重复执行相同查询） */
        INVALID
    }

    /**
     * 便捷工厂：REWRITTEN 结果。
     */
    public static RetryRewriteResult rewritten(String text) {
        return new RetryRewriteResult(Type.REWRITTEN, text);
    }

    /**
     * 便捷工厂：PASSTHROUGH / INVALID（无文本）。
     */
    public static RetryRewriteResult of(Type type) {
        return new RetryRewriteResult(type, null);
    }
}
