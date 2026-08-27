package com.wisread.service;

import com.wisread.model.PageText;

import java.util.List;

/**
 * PDF 文本抽取服务接口。
 * 职责：将一份 PDF 文件解析为按页组织的纯文本，作为后续分块、向量化的原始输入。
 * 实现需说明对加密/损坏 PDF 的处理策略（通常直接判为解析失败）。
 */
public interface PdfParsingService {

    /**
     * 从 PDF 字节流中抽取每一页的文本内容。
     *
     * <p>做什么：逐页读取 PDF，返回与页码对应的 {@link PageText} 列表。</p>
     *
     * <p>为什么：RAG 流程要求“按页”切分与定位引用，因此解析阶段就必须保留页码信息，
     * 使下游分块与最终引用来源都能精确回溯到原文档的具体页。</p>
     *
     * @param pdfBytes 完整的 PDF 文件字节内容
     * @return 按页码升序排列的页面文本列表
     */
    List<PageText> extractPages(byte[] pdfBytes);
}
