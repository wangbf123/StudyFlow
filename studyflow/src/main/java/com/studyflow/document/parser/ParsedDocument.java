package com.studyflow.document.parser;

import java.util.List;

public record ParsedDocument(
        String parserType,
        List<ParsedPage> pages,
        Integer ocrPageCount,
        Long ocrElapsedMs
) {
    public ParsedDocument {
        pages = pages == null ? List.of() : List.copyOf(pages);
        ocrPageCount = ocrPageCount == null ? 0 : ocrPageCount;
    }

    public int pageCount() {
        return pages.size();
    }

    public String fullText() {
        return pages.stream()
                .map(ParsedPage::text)
                .filter(text -> text != null && !text.isBlank())
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");
    }

    public record ParsedPage(
            Integer pageNumber,
            String text,
            String parserType,
            Double ocrConfidence
    ) {
    }
}
