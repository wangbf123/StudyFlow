package com.studyflow.ocr;

public record OcrPageResult(
        Integer pageNumber,
        String text,
        Double confidence
) {
}
