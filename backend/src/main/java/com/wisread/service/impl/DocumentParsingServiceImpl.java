package com.wisread.service.impl;

import com.wisread.exception.ApiException;
import com.wisread.model.DocumentParsingResult;
import com.wisread.model.DocumentType;
import com.wisread.model.PageText;
import com.wisread.service.DocumentParsingService;
import com.wisread.service.PdfParsingService;
import com.wisread.service.TokenCounter;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 统一文档解析服务实现。
 *
 * <p>按文档类型分发到对应解析器，统一输出 {@link PageText} 列表：
 * PDF 用 PDFBox，DOCX/XLSX 用 Apache POI，TXT/MD 直接读取并做 UTF-8/GB18030 兼容解码。
 * 非 PDF 格式会先把段落/行/工作表行聚合成约一个分块大小的定位片段，
 * 避免单个段落或单行文本过碎，降低后续向量检索质量。
 */
@Service
public class DocumentParsingServiceImpl implements DocumentParsingService {

    private static final int TARGET_TOKENS = 350;
    private static final int MAX_UNITS_PER_SEGMENT = 120;

    private final PdfParsingService pdfParsingService;
    private final TokenCounter tokenCounter;

    public DocumentParsingServiceImpl(PdfParsingService pdfParsingService, TokenCounter tokenCounter) {
        this.pdfParsingService = pdfParsingService;
        this.tokenCounter = tokenCounter;
    }

    public DocumentParsingResult parse(byte[] bytes, String fileType) {
        DocumentType type = DocumentType.fromValueOrDefault(fileType);
        return switch (type) {
            case PDF -> parsePdf(bytes);
            case DOCX -> parseDocx(bytes);
            case XLSX -> parseXlsx(bytes);
            case TXT, MD -> parseText(bytes);
        };
    }

    private DocumentParsingResult parsePdf(byte[] bytes) {
        List<PageText> segments = pdfParsingService.extractPages(bytes).stream()
                .map(page -> new PageText(page.page(), page.page(), "页", page.text()))
                .toList();
        return new DocumentParsingResult(segments, segments.size());
    }

    private DocumentParsingResult parseDocx(byte[] bytes) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            List<String> blocks = new ArrayList<>();
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    addBlock(blocks, paragraph.getText());
                } else if (element instanceof XWPFTable table) {
                    for (XWPFTableRow row : table.getRows()) {
                        StringBuilder rowText = new StringBuilder();
                        for (XWPFTableCell cell : row.getTableCells()) {
                            if (rowText.length() > 0) {
                                rowText.append('\t');
                            }
                            rowText.append(cell.getText());
                        }
                        addBlock(blocks, rowText.toString());
                    }
                }
            }
            return new DocumentParsingResult(groupUnits(blocks, "段落"), blocks.size());
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "corrupted or invalid DOCX");
        }
    }

    private DocumentParsingResult parseXlsx(byte[] bytes) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            DataFormatter formatter = new DataFormatter();
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            List<PageText> segments = new ArrayList<>();

            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                String label = "工作表" + sheet.getSheetName();
                StringBuilder block = new StringBuilder();
                int blockTokens = 0;
                int startRow = 1;
                int endRow = 1;

                for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    if (row == null) {
                        continue;
                    }
                    String rowText = formatRow(row, formatter, evaluator);
                    if (rowText.isBlank()) {
                        continue;
                    }

                    int rowNumber = rowIndex + 1;
                    int rowTokens = tokenCounter.count(rowText);
                    if (block.length() > 0 && blockTokens + rowTokens > TARGET_TOKENS) {
                        segments.add(new PageText(startRow, endRow, label, block.toString().trim()));
                        block = new StringBuilder();
                        blockTokens = 0;
                        startRow = rowNumber;
                    }
                    if (block.length() > 0) {
                        block.append('\n');
                    }
                    block.append(rowText);
                    blockTokens += rowTokens;
                    endRow = rowNumber;
                }
                if (block.length() > 0) {
                    segments.add(new PageText(startRow, endRow, label, block.toString().trim()));
                }
            }
            return new DocumentParsingResult(segments, workbook.getNumberOfSheets());
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "corrupted or invalid XLSX");
        }
    }

    private DocumentParsingResult parseText(byte[] bytes) {
        String text = decode(bytes);
        String[] rawLines = text.split("\\r?\\n", -1);
        List<String> lines = new ArrayList<>();
        for (String line : rawLines) {
            if (!line.isBlank()) {
                lines.add(line.strip());
            }
        }
        return new DocumentParsingResult(groupUnits(lines, "行"), lines.size());
    }

    /**
     * 把段落/行等基本单元聚合成目标大小的定位片段，返回定位范围。
     */
    private List<PageText> groupUnits(List<String> units, String label) {
        List<PageText> segments = new ArrayList<>();
        StringBuilder block = new StringBuilder();
        int blockTokens = 0;
        int start = 1;
        int end = 1;

        for (int i = 0; i < units.size(); i++) {
            String unit = units.get(i);
            int unitTokens = tokenCounter.count(unit);
            if (block.length() > 0
                    && (blockTokens + unitTokens > TARGET_TOKENS || i - start + 1 >= MAX_UNITS_PER_SEGMENT)) {
                segments.add(new PageText(start, end, label, block.toString().trim()));
                block = new StringBuilder();
                blockTokens = 0;
                start = i + 1;
            }
            if (block.length() > 0) {
                block.append('\n');
            }
            block.append(unit);
            blockTokens += unitTokens;
            end = i + 1;
        }
        if (block.length() > 0) {
            segments.add(new PageText(start, end, label, block.toString().trim()));
        }
        return segments;
    }

    private void addBlock(List<String> blocks, String text) {
        if (text != null && !text.isBlank()) {
            blocks.add(text.strip());
        }
    }

    private String formatRow(Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
        StringBuilder builder = new StringBuilder();
        for (int cellIndex = 0; cellIndex < row.getLastCellNum(); cellIndex++) {
            Cell cell = row.getCell(cellIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell == null) {
                continue;
            }
            String value = formatter.formatCellValue(cell, evaluator);
            if (value == null || value.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('\t');
            }
            builder.append(value);
        }
        return builder.toString().trim();
    }

    /**
     * 解码文本文件：优先 UTF-8，其次 UTF-16 BOM，最后回退到 GB18030，避免中文 TXT 乱码。
     */
    private String decode(byte[] bytes) {
        if (bytes.length >= 3
                && (bytes[0] & 0xff) == 0xef
                && (bytes[1] & 0xff) == 0xbb
                && (bytes[2] & 0xff) == 0xbf) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xfe) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xfe && (bytes[1] & 0xff) == 0xff) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            return new String(bytes, Charset.forName("GB18030"));
        }
    }
}
