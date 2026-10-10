package com.wisread.service;

import com.wisread.model.DocumentParsingResult;

/**
 * 统一文档解析服务接口。
 *
 * <p>职责：根据文档类型把上传文件的字节内容解析为一系列带定位信息的文本片段，
 * 供下游 {@link ChunkingService} 统一切分与向量化。PDF 保留物理页码，
 * DOCX 使用段落范围，XLSX 使用工作表加行范围，TXT/MD 使用行范围。
 */
public interface DocumentParsingService {

    /**
     * 解析文档字节内容。
     *
     * @param bytes    文件完整字节
     * @param fileType 文档类型值，如 pdf/docx/xlsx/txt/md；老数据为 null 时按 PDF 处理
     * @return 解析结果，包含待分块的定位片段和文档定位单位总数
     */
    DocumentParsingResult parse(byte[] bytes, String fileType);
}
