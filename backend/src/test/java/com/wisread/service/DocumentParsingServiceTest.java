package com.wisread.service;

import com.wisread.model.DocumentParsingResult;
import com.wisread.model.PageText;
import com.wisread.service.impl.DocumentParsingServiceImpl;
import com.wisread.service.impl.PdfParsingServiceImpl;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentParsingServiceTest {

    private final DocumentParsingService parsingService =
            new DocumentParsingServiceImpl(new PdfParsingServiceImpl(), new TokenCounter());

    @Test
    void parsesTextFileWithLineLocations() {
        byte[] bytes = "第一行内容\n第二行内容\n第三行内容".getBytes(StandardCharsets.UTF_8);

        DocumentParsingResult result = parsingService.parse(bytes, "txt");

        assertThat(result.locationCount()).isEqualTo(3);
        assertThat(result.segments()).isNotEmpty();
        assertThat(result.segments())
                .extracting(PageText::locationLabel)
                .containsOnly("行");
        assertThat(result.segments().stream().map(PageText::text).toList())
                .anySatisfy(text -> assertThat(text).contains("第一行内容"));
    }

    @Test
    void decodesGb18030TextWithoutMojibake() {
        byte[] bytes = "智阅支持中文文档".getBytes(Charset.forName("GB18030"));

        DocumentParsingResult result = parsingService.parse(bytes, "md");

        assertThat(result.segments().stream().map(PageText::text).toList())
                .anySatisfy(text -> assertThat(text).contains("智阅支持中文文档"));
    }

    @Test
    void parsesDocxWithParagraphLocations() throws Exception {
        byte[] bytes = createDocx("第一段内容", "第二段内容", "第三段内容");

        DocumentParsingResult result = parsingService.parse(bytes, "docx");

        assertThat(result.locationCount()).isEqualTo(3);
        assertThat(result.segments()).isNotEmpty();
        assertThat(result.segments())
                .extracting(PageText::locationLabel)
                .containsOnly("段落");
    }

    @Test
    void parsesXlsxWithSheetAndRowLocations() throws Exception {
        byte[] bytes = createXlsx();

        DocumentParsingResult result = parsingService.parse(bytes, "xlsx");

        assertThat(result.locationCount()).isEqualTo(1);
        assertThat(result.segments()).isNotEmpty();
        PageText first = result.segments().get(0);
        assertThat(first.locationLabel()).startsWith("工作表");
        assertThat(first.page()).isEqualTo(1);
        assertThat(first.pageEnd()).isGreaterThanOrEqualTo(first.page());
        assertThat(first.text()).contains("单元格值");
    }

    private byte[] createDocx(String... paragraphs) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (XWPFDocument document = new XWPFDocument()) {
            for (String paragraph : paragraphs) {
                XWPFParagraph p = document.createParagraph();
                p.createRun().setText(paragraph);
            }
            document.write(output);
        }
        return output.toByteArray();
    }

    private byte[] createXlsx() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("数据");
            var row1 = sheet.createRow(0);
            row1.createCell(0).setCellValue("单元格值");
            var row2 = sheet.createRow(1);
            row2.createCell(1).setCellValue("第二行");
            workbook.write(output);
        }
        return output.toByteArray();
    }
}
