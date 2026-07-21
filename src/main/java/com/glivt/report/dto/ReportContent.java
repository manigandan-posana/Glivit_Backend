package com.glivt.report.dto;

public record ReportContent(
        String fileName,
        String contentType,
        String content) {
}
