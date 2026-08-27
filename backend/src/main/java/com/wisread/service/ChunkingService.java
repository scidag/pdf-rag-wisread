package com.wisread.service;

import com.wisread.model.PageText;
import com.wisread.model.TextChunk;

import java.util.List;

/**
 * 文档分块服务接口。
 * 职责：将 PDF 每一页抽取出的纯文本切分为适合向量化与检索的语义片段（chunk），
 * 在“尽量保留完整语义”与“控制单块 token 上限以适配 Embedding 模型上下文”之间取得平衡。
 */
public interface ChunkingService {

    /**
     * 将一页文本切分为多个文本块。
     *
     * <p>做什么：根据 token 数量阈值（约 800~1200 token）把页面文本按句子边界切分，
     * 并对相邻块保留一定尾部重叠以保留上下文连续性。</p>
     *
     * <p>为什么：分块过大会超出 Embedding 模型的最大上下文长度并稀释检索精度；
     * 分块过小会割裂语义、丢失段落整体含义。重叠设计可避免句子被硬切断后，
     * 跨块的关键信息在新块的起始处仍能被模型感知，从而提升召回质量。</p>
     *
     * @param page          已抽取的单页文本（含页码）
     * @param nextChunkIndex 全局分块序号起点，用于保证跨页分块时索引连续
     * @return 本页产生的文本块列表（页面无文本时返回空列表）
     */
    List<TextChunk> splitPage(PageText page, int nextChunkIndex);
}
