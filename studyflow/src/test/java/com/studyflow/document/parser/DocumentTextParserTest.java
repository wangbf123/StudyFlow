package com.studyflow.document.parser;

import com.studyflow.ocr.OcrClient;
import com.studyflow.ocr.OcrPageResult;
import com.studyflow.ocr.OcrProperties;
import com.studyflow.ocr.OcrResult;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentTextParserTest {

    private OcrClient ocrClient;
    private OcrProperties properties;
    private DocumentTextParser parser;

    @BeforeEach
    void setUp() {
        ocrClient = mock(OcrClient.class);
        properties = new OcrProperties();
        properties.setEnabled(true);
        properties.setMinTextCharsPerPage(20);
        parser = new DocumentTextParser(ocrClient, properties);
    }

    @Test
    void keepsTextPdfOnPdfBoxFastPath() throws Exception {
        byte[] pdf = textPdf("StudyFlow uses PDFBox for native PDF text extraction.");

        ParsedDocument result = parser.parseDocument("native.pdf", "pdf", pdf);

        assertEquals(DocumentTextParser.PARSER_PDFBOX, result.parserType());
        assertEquals(1, result.pageCount());
        assertEquals(0, result.ocrPageCount());
        assertTrue(result.fullText().contains("StudyFlow uses PDFBox"));
        verify(ocrClient, never()).recognize(any(), any(), any(), any());
    }

    @Test
    void sendsOnlyLowTextPdfPagesToPaddleOcr() throws Exception {
        byte[] pdf = scannedPdf();
        when(ocrClient.recognize(eq("document.pdf"), eq("pdf"), eq(pdf), eq(List.of(1))))
                .thenReturn(new OcrResult("PaddleOCR", 1,
                        List.of(new OcrPageResult(1, "扫描版 Java 学习资料", 0.9632)), 1250L));

        ParsedDocument result = parser.parseDocument("scan.pdf", "pdf", pdf);

        assertEquals(DocumentTextParser.PARSER_PADDLE_OCR, result.parserType());
        assertEquals(1, result.ocrPageCount());
        assertEquals(1250L, result.ocrElapsedMs());
        assertEquals("扫描版 Java 学习资料", result.pages().getFirst().text());
        assertEquals(0.9632, result.pages().getFirst().ocrConfidence());
    }

    @Test
    void recognizesImageDocuments() {
        byte[] image = "fake-image-for-client-contract-test".getBytes(StandardCharsets.UTF_8);
        when(ocrClient.recognize("note.png", "png", image, List.of()))
                .thenReturn(new OcrResult("PaddleOCR", 1,
                        List.of(new OcrPageResult(1, "Redis 缓存与限流", 0.98)), 420L));

        ParsedDocument result = parser.parseDocument("note.png", "png", image);

        assertEquals(DocumentTextParser.PARSER_PADDLE_OCR, result.parserType());
        assertEquals("Redis 缓存与限流", result.fullText());
        assertEquals(1, result.ocrPageCount());
    }

    @Test
    void pageAwareChunkingKeepsOcrMetadata() {
        TextChunker chunker = new TextChunker(200, 20);
        ParsedDocument document = new ParsedDocument(
                DocumentTextParser.PARSER_PADDLE_OCR,
                List.of(new ParsedDocument.ParsedPage(
                        3,
                        "A".repeat(260),
                        DocumentTextParser.PARSER_PADDLE_OCR,
                        0.91)),
                1,
                100L
        );

        List<TextChunker.ParsedChunk> chunks = chunker.chunkDocument(document);

        assertFalse(chunks.isEmpty());
        assertTrue(chunks.size() >= 2);
        assertTrue(chunks.stream().allMatch(chunk -> chunk.pageNumber() == 3));
        assertTrue(chunks.stream().allMatch(chunk -> chunk.parserType().equals("PADDLE_OCR")));
        assertTrue(chunks.stream().allMatch(chunk -> chunk.ocrConfidence().equals(0.91)));
    }

    private byte[] scannedPdf() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
            PDImageXObject pdfImage = LosslessFactory.createFromImage(document, image);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawImage(pdfImage, 72, 600, 200, 120);
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private byte[] textPdf(String text) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(PDType1Font.HELVETICA, 12);
                content.newLineAtOffset(72, 720);
                content.showText(text);
                content.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
