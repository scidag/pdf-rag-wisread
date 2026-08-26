package com.wisread.service;

import com.wisread.model.PageText;

import java.util.List;

public interface PdfParsingService {

    List<PageText> extractPages(byte[] pdfBytes);
}
