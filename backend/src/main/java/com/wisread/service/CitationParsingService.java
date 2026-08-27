package com.wisread.service;

import com.wisread.dto.SourceResponse;
import com.wisread.model.ChunkSearchResult;

import java.util.List;

/**
 * 引用解析与校验服务接口。
 * 职责：从大模型生成的答案文本中解析形如 [1]、[2] 的引用标记，
 * 并将其映射回本次检索返回的真实文档片段，防止模型“编造”不存在的引用编号，
 * 为前端展示可追溯的引用来源（来源列表）提供数据。
 */
public interface CitationParsingService {

    /**
     * 解析答案中的引用编号并校验其是否落在本次检索到的 chunks 范围内。
     *
     * <p>做什么：用正则提取答案里所有 [数字] 标记，只保留那些编号确实对应
     * 检索结果（编号 i 对应第 i 个 chunk，即 1-based 下标）的来源，
     * 生成 {@link SourceResponse} 列表（含文档名、页码、内容摘要）。</p>
     *
     * <p>为什么：大模型可能生成虚构的引用编号，若直接展示会误导用户。
     * 通过“编号 → 实际检索片段”的强绑定校验，保证引用的每个来源都真实存在。</p>
     *
     * @param answer           大模型生成的答案文本
     * @param retrievedChunks  本次查询检索到的文档片段（按相关性排序，1-based 对应引用编号）
     * @return 经过校验、可在前端展示的引用来源列表
     */
    List<SourceResponse> parseAndValidate(String answer, List<ChunkSearchResult> retrievedChunks);
}
