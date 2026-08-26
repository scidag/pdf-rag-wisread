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

@Service
public class PdfParsingServiceImpl implements PdfParsingService {

    public List<PageText> extractPages(byte[] pdfBytes) {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            if (document.isEncrypted()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "encrypted PDF is not supported");
            }

            List<PageText> pages = new ArrayList<>();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document);
                pages.add(new PageText(page, text == null ? "" : text));
            }
            return pages;
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "corrupted or invalid PDF");
        }
    }
}
