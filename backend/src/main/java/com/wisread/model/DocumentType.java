package com.wisread.model;

import java.util.Locale;

/**
 * 文档类型枚举。集中维护系统支持的上传格式、文件扩展名与 MIME 类型，
 * 避免这些常量散落在 Service/Controller 中造成不一致。
 */
public enum DocumentType {

    PDF("pdf", "application/pdf", ".pdf"),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".docx"),
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx"),
    TXT("txt", "text/plain", ".txt"),
    MD("md", "text/markdown", ".md");

    private final String value;
    private final String contentType;
    private final String extension;

    DocumentType(String value, String contentType, String extension) {
        this.value = value;
        this.contentType = contentType;
        this.extension = extension;
    }

    public String value() {
        return value;
    }

    public String contentType() {
        return contentType;
    }

    public String extension() {
        return extension;
    }

    /**
     * 根据文件名的扩展名识别文档类型；不支持或无法识别时返回 null。
     */
    public static DocumentType fromFilename(String filename) {
        if (filename == null || !filename.contains(".")) {
            return null;
        }
        String extension = filename.substring(filename.lastIndexOf('.') + 1)
                .toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "pdf" -> PDF;
            case "docx" -> DOCX;
            case "xlsx" -> XLSX;
            case "txt" -> TXT;
            case "md", "markdown" -> MD;
            default -> null;
        };
    }

    /**
     * 根据持久化的类型值识别文档类型；null 或未知值返回 null。
     */
    public static DocumentType fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (DocumentType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        return null;
    }

    /**
     * 老数据没有 file_type 时按 PDF 兼容处理，保证旧文档仍可预览、引用和重试。
     */
    public static DocumentType fromValueOrDefault(String value) {
        DocumentType type = fromValue(value);
        return type == null ? PDF : type;
    }
}
