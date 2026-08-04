package com.studyflow.document.parser;

import com.studyflow.common.BusinessException;
import com.studyflow.ocr.OcrClient;
import com.studyflow.ocr.OcrPageResult;
import com.studyflow.ocr.OcrProperties;
import com.studyflow.ocr.OcrResult;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class DocumentTextParser {

    public static final String PARSER_TEXT = "TEXT";
    public static final String PARSER_MARKDOWN = "MARKDOWN";
    public static final String PARSER_PDFBOX = "PDFBOX";
    public static final String PARSER_PADDLE_OCR = "PADDLE_OCR";
    public static final String PARSER_HYBRID = "HYBRID";

    private final OcrClient ocrClient;
    private final OcrProperties ocrProperties;

    public String parse(String fileName, String fileType, byte[] bytes) {
        return parseDocument(fileName, fileType, bytes).fullText();
    }

    public ParsedDocument parseDocument(String fileName, String fileType, byte[] bytes) {
        String normalizedType = fileType == null ? "" : fileType.toLowerCase();
        try {
            return switch (normalizedType) {
                case "pdf" -> parsePdf(bytes);
                case "txt" -> singlePage(new String(bytes, StandardCharsets.UTF_8), PARSER_TEXT);
                case "md", "markdown" -> singlePage(new String(bytes, StandardCharsets.UTF_8), PARSER_MARKDOWN);
                case "jpg", "jpeg", "png", "webp" -> parseImage(fileName, normalizedType, bytes);
                default -> throw new BusinessException("暂不支持该文件类型：" + fileType);
            };
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("文档解析失败：" + fileName + "，原因：" + ex.getMessage());
        }
    }

    private ParsedDocument singlePage(String text, String parserType) {
        ensureText(text);
        return new ParsedDocument(parserType,
                List.of(new ParsedDocument.ParsedPage(1, text, parserType, null)), 0, null);
    }

    private ParsedDocument parseImage(String fileName, String fileType, byte[] bytes) {
        OcrResult result = ocrClient.recognize(fileName, fileType, bytes, List.of());
        List<ParsedDocument.ParsedPage> pages = toOcrPages(result);
        ensureText(pages.stream().map(ParsedDocument.ParsedPage::text).reduce("", (a, b) -> a + "\n" + b));
        return new ParsedDocument(PARSER_PADDLE_OCR, pages, pages.size(), result.elapsedMs());
    }

    private ParsedDocument parsePdf(byte[] bytes) throws Exception {
        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(bytes))) {
            List<ParsedDocument.ParsedPage> pages = new ArrayList<>();
            List<Integer> ocrPageNumbers = new ArrayList<>();
            for (int pageNumber = 1; pageNumber <= document.getNumberOfPages(); pageNumber++) {
                String text = extractPdfPage(document, pageNumber);
                pages.add(new ParsedDocument.ParsedPage(pageNumber, text, PARSER_PDFBOX, null));
                if (meaningfulCharacterCount(text) < Math.max(ocrProperties.getMinTextCharsPerPage(), 1)
                        && containsRasterImage(document.getPage(pageNumber - 1).getResources(), 0)) {
                    ocrPageNumbers.add(pageNumber);
                }
            }

            if (ocrPageNumbers.isEmpty()) {
                ensureText(pages.stream().map(ParsedDocument.ParsedPage::text).reduce("", (a, b) -> a + b));
                return new ParsedDocument(PARSER_PDFBOX, pages, 0, null);
            }
            if (!ocrProperties.isEnabled()) {
                ensureText(pages.stream().map(ParsedDocument.ParsedPage::text).reduce("", (a, b) -> a + b));
                return new ParsedDocument(PARSER_PDFBOX, pages, 0, null);
            }
            if (ocrPageNumbers.size() > Math.max(ocrProperties.getMaxPages(), 1)) {
                throw new BusinessException("需要 OCR 的 PDF 页数超过限制：" + ocrPageNumbers.size());
            }

            OcrResult ocrResult = ocrClient.recognize("document.pdf", "pdf", bytes, ocrPageNumbers);
            Map<Integer, OcrPageResult> ocrByPage = new LinkedHashMap<>();
            for (OcrPageResult page : ocrResult.pages()) {
                if (page.pageNumber() != null) {
                    ocrByPage.put(page.pageNumber(), page);
                }
            }
            List<ParsedDocument.ParsedPage> merged = pages.stream()
                    .map(page -> mergeOcrPage(page, ocrByPage.get(page.pageNumber())))
                    .toList();
            ensureText(merged.stream().map(ParsedDocument.ParsedPage::text).reduce("", (a, b) -> a + b));
            int ocrPageCount = (int) merged.stream()
                    .filter(page -> PARSER_PADDLE_OCR.equals(page.parserType()))
                    .count();
            String parserType = ocrPageCount == 0
                    ? PARSER_PDFBOX
                    : (ocrPageCount == merged.size() ? PARSER_PADDLE_OCR : PARSER_HYBRID);
            return new ParsedDocument(parserType, merged, ocrPageCount, ocrResult.elapsedMs());
        }
    }

    private String extractPdfPage(PDDocument document, int pageNumber) throws Exception {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        stripper.setStartPage(pageNumber);
        stripper.setEndPage(pageNumber);
        return stripper.getText(document);
    }

    private ParsedDocument.ParsedPage mergeOcrPage(
            ParsedDocument.ParsedPage nativePage,
            OcrPageResult ocrPage
    ) {
        if (ocrPage == null || meaningfulCharacterCount(ocrPage.text()) == 0) {
            return nativePage;
        }
        return new ParsedDocument.ParsedPage(nativePage.pageNumber(), ocrPage.text(),
                PARSER_PADDLE_OCR, ocrPage.confidence());
    }

    private List<ParsedDocument.ParsedPage> toOcrPages(OcrResult result) {
        return result.pages().stream()
                .map(page -> new ParsedDocument.ParsedPage(
                        page.pageNumber() == null ? 1 : page.pageNumber(),
                        page.text(),
                        PARSER_PADDLE_OCR,
                        page.confidence()))
                .toList();
    }

    private long meaningfulCharacterCount(String text) {
        if (text == null) {
            return 0;
        }
        return text.codePoints()
                .filter(Character::isLetterOrDigit)
                .count();
    }

    private boolean containsRasterImage(PDResources resources, int depth) throws IOException {
        if (resources == null || depth > 3) {
            return false;
        }
        for (COSName name : resources.getXObjectNames()) {
            PDXObject object = resources.getXObject(name);
            if (object instanceof PDImageXObject) {
                return true;
            }
            if (object instanceof PDFormXObject form && containsRasterImage(form.getResources(), depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private void ensureText(String text) {
        if (meaningfulCharacterCount(text) == 0) {
            throw new BusinessException("未解析到有效文本内容，请检查文件清晰度或 OCR 服务状态");
        }
    }
}
