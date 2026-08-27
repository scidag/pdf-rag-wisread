package com.wisread.service.impl;

import com.wisread.service.PdfParsingService;

import com.wisread.exception.ApiException;
import com.wisread.model.PageText;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF 文本抽取服务的实现。
 * 实现要点：依赖 Apache PDFBox 库完成解析（{@code Loader.loadPDF} 加载文档，
 * {@code PDFTextStripper} 逐页抽取纯文本），无 OCR 能力，仅提取原生文本层。
 * 抽取失败含义：若 PDF 加密（无法读取内容）或文件损坏/格式非法，会抛出 422 异常，
 * 由上层终止解析流程并提示用户，而不是返回残缺数据污染后续分块与检索。
 */
@Service
public class PdfParsingServiceImpl implements PdfParsingService {

    /**
     * 逐页抽取 PDF 文本。
     * 为什么逐页而非整本抽取：需要把文本与页码一一绑定，以便下游分块与最终引用来源
     * 都能精确定位到原文档页面；同时逐页可避免超大文档一次性占用过多内存。
     */
    public List<PageText> extractPages(byte[] pdfBytes) {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            // 加密 PDF 没有文本层权限，直接判为不支持
            if (document.isEncrypted()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "encrypted PDF is not supported");
            }

            List<PageText> pages = new ArrayList<>();
            // 逐页设置起止页并抽取，保证每页文本独立成块、页码准确
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document);
                pages.add(new PageText(page, text == null ? "" : text));
            }
            return pages;
        } catch (IOException exception) {
            // 文件损坏或不是合法 PDF：解析失败即视为无法处理
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "corrupted or invalid PDF");
        }
    }
}
