package com.studyflow.ocr;

import java.util.List;

public record OcrResult(
        String engine,
        Integer pageCount,
        List<OcrPageResult> pages,
        Long elapsedMs
) {
    public OcrResult {
        pages = pages == null ? List.of() : List.copyOf(pages);
    }
}
