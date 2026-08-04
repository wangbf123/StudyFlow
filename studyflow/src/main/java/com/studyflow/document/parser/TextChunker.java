package com.studyflow.document.parser;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class TextChunker {

    private final int chunkSize;
    private final int chunkOverlap;

    public TextChunker(
            @Value("${studyflow.document.chunk-size:1000}") int chunkSize,
            @Value("${studyflow.document.chunk-overlap:120}") int chunkOverlap
    ) {
        this.chunkSize = Math.max(chunkSize, 200);
        this.chunkOverlap = Math.max(0, Math.min(chunkOverlap, this.chunkSize / 3));
    }

    public List<String> chunk(String text) {
        String cleaned = clean(text);
        if (cleaned.isBlank()) {
            return List.of();
        }

        return chunkCleaned(cleaned);
    }

    public List<ParsedChunk> chunkDocument(ParsedDocument document) {
        if (document == null || document.pages().isEmpty()) {
            return List.of();
        }
        List<ParsedChunk> result = new ArrayList<>();
        for (ParsedDocument.ParsedPage page : document.pages()) {
            List<String> pageChunks = chunk(page.text());
            for (String content : pageChunks) {
                result.add(new ParsedChunk(
                        content,
                        page.pageNumber(),
                        page.parserType(),
                        page.ocrConfidence()
                ));
            }
        }
        return result;
    }

    private List<String> chunkCleaned(String cleaned) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < cleaned.length()) {
            int end = Math.min(cleaned.length(), start + chunkSize);
            if (end < cleaned.length()) {
                int paragraphBreak = cleaned.lastIndexOf('\n', end);
                if (paragraphBreak > start + chunkSize / 2) {
                    end = paragraphBreak;
                }
            }
            String chunk = cleaned.substring(start, end).trim();
            if (!chunk.isBlank()) {
                chunks.add(chunk);
            }
            if (end >= cleaned.length()) {
                break;
            }
            start = Math.max(end - chunkOverlap, start + 1);
        }
        return chunks;
    }

    private String clean(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[ \\t\\x0B\\f]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    public record ParsedChunk(
            String content,
            Integer pageNumber,
            String parserType,
            Double ocrConfidence
    ) {
    }
}
