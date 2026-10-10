package com.wisread.model;

/**
 * 文档定位片段（内存数据模型，不持久化）。
 * 将一份文档按定位单位切分为若干文本块，例如 PDF 的一页、DOCX 的一段、
 * TXT/MD 的若干行、XLSX 的一张工作表中的若干行。后续统一交给分块服务处理。
 */
public record PageText(
        int page, // 定位起始序号，从 1 开始；对 PDF 是页码，对 DOCX 是段号，对 TXT/MD 是行号
        int pageEnd, // 定位结束序号（含），与起始序号共同表达一个定位区间
        String locationLabel, // 定位单位标签，例如“页”“段落”“行”“工作表 Sheet1”
        String text // 该定位片段提取出的纯文本内容
) {
    /**
     * 兼容旧的“单页文本”构造方式，默认定位区间为单页，单位为“页”。
     */
    public PageText(int page, String text) {
        this(page, page, "页", text);
    }
}
