package com.wisread.model;

import java.util.List;

/**
 * 文档解析结果（内存数据模型，不持久化）。
 *
 * @param segments      待分块的定位片段列表，供下游统一切分与向量化。
 * @param locationCount 文档的定位单位总数：PDF 为页数，DOCX 为段落数，
 *                      XLSX 为工作表数，TXT/MD 为行数。
 */
public record DocumentParsingResult(
        List<PageText> segments,
        int locationCount
) {
}
