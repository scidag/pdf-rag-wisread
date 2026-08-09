package com.wisread.service;

import com.wisread.model.PageText;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PdfParsingServiceTest {

    private final PdfParsingService pdfParsingService = new PdfParsingService();

    @Test
    void extractsTextFromGeneratedPdf() throws Exception {
        byte[] pdfBytes = createSamplePdf();

        List<PageText> pages = pdfParsingService.extractPages(pdfBytes);

        assertThat(pages).hasSize(1);
        assertThat(pages.get(0).text()).contains("Hello Wisread PDF");
    }

    private byte[] createSamplePdf() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.beginText();
                contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                contentStream.newLineAtOffset(72, 720);
                contentStream.showText("Hello Wisread PDF");
                contentStream.endText();
            }
            document.save(output);
        }
        byte[] bytes = output.toByteArray();
        Files.write(Path.of("target", "sample-valid.pdf"), bytes);
        return bytes;
    }
}
