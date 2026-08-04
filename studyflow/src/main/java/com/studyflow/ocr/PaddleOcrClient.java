package com.studyflow.ocr;

import com.studyflow.common.BusinessException;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
public class PaddleOcrClient implements OcrClient {

    private final OcrProperties properties;
    private final RestClient restClient;

    public PaddleOcrClient(OcrProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(Math.max(properties.getConnectTimeoutSeconds(), 1)));
        requestFactory.setReadTimeout(Duration.ofSeconds(Math.max(properties.getReadTimeoutSeconds(), 1)));
        this.restClient = RestClient.builder()
                .baseUrl(stripTrailingSlash(properties.getBaseUrl()))
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public OcrResult recognize(String fileName, String fileType, byte[] bytes, List<Integer> pageNumbers) {
        if (!properties.isEnabled()) {
            throw new BusinessException("OCR 未启用，请设置 STUDYFLOW_OCR_ENABLED=true");
        }
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException("OCR 输入文件不能为空");
        }

        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", namedResource(fileName, bytes)).contentType(contentType(fileType));
        if (pageNumbers != null && !pageNumbers.isEmpty()) {
            body.part("pages", pageNumbers.stream()
                    .filter(Objects::nonNull)
                    .distinct()
                    .sorted()
                    .map(String::valueOf)
                    .collect(Collectors.joining(",")));
        }

        try {
            ServiceResponse response = restClient.post()
                    .uri("/v1/ocr")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body.build())
                    .retrieve()
                    .body(ServiceResponse.class);
            if (response == null) {
                throw new BusinessException("PaddleOCR 服务返回空响应");
            }
            List<OcrPageResult> pages = response.pages() == null
                    ? List.of()
                    : response.pages().stream()
                    .filter(Objects::nonNull)
                    .map(page -> new OcrPageResult(page.pageNumber(), page.text(), page.confidence()))
                    .toList();
            return new OcrResult(response.engine(), response.pageCount(), pages, response.elapsedMs());
        } catch (RestClientResponseException ex) {
            throw new BusinessException(502, "PaddleOCR 调用失败：HTTP " + ex.getStatusCode().value()
                    + "，" + limit(ex.getResponseBodyAsString(), 300));
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(502, "PaddleOCR 服务不可用：" + ex.getMessage());
        }
    }

    private ByteArrayResource namedResource(String fileName, byte[] bytes) {
        return new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return fileName == null || fileName.isBlank() ? "document" : fileName;
            }
        };
    }

    private MediaType contentType(String fileType) {
        String type = fileType == null ? "" : fileType.toLowerCase();
        return switch (type) {
            case "pdf" -> MediaType.APPLICATION_PDF;
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG;
            case "png" -> MediaType.IMAGE_PNG;
            case "webp" -> MediaType.parseMediaType("image/webp");
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }

    private String stripTrailingSlash(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String limit(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "...";
    }

    private record ServiceResponse(String engine, Integer pageCount, List<ServicePage> pages, Long elapsedMs) {
    }

    private record ServicePage(Integer pageNumber, String text, Double confidence) {
    }
}
