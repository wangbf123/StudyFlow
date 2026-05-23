package com.studyflow.document.parser;

import com.studyflow.common.BusinessException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

@Component
public class DocumentTextParser {

    public String parse(String fileName, String fileType, byte[] bytes) {
        String normalizedType = fileType == null ? "" : fileType.toLowerCase();
        try {
            return switch (normalizedType) {
                case "pdf" -> parsePdf(bytes);
                case "txt", "md", "markdown" -> new String(bytes, StandardCharsets.UTF_8);
                default -> throw new BusinessException("暂不支持该文件类型：" + fileType);
            };
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("文档解析失败：" + fileName + "，原因：" + ex.getMessage());
        }
    }

    private String parsePdf(byte[] bytes) throws Exception {
        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(bytes))) {
            return new PDFTextStripper().getText(document);
        }
    }
}
