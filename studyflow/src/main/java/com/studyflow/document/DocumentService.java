package com.studyflow.document;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.studyflow.common.BusinessException;
import com.studyflow.kb.KnowledgeBaseEntity;
import com.studyflow.kb.KnowledgeBaseService;
import com.studyflow.storage.StorageService;
import com.studyflow.task.ParseTaskEntity;
import com.studyflow.task.PipelineStore;
import com.studyflow.task.PipelineStage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class DocumentService {

    private final KnowledgeBaseService knowledgeBaseService;
    private final StorageService storageService;
    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final PipelineStore pipeline;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final RedissonClient redissonClient;

    public DocumentUploadResponse upload(Long userId, Long knowledgeBaseId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件不能为空");
        }
        KnowledgeBaseEntity knowledgeBase = knowledgeBaseService.getOwnedActive(userId, knowledgeBaseId);
        byte[] bytes = readFileBytes(file);
        String originalFileName = file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename();
        String fileType = extensionOf(originalFileName);
        validateFileType(fileType);
        String md5 = md5(bytes);

        RLock lock = redissonClient.getLock("studyflow:upload:md5:" + md5);
        try {
            if (!lock.tryLock(5, TimeUnit.SECONDS)) {
                throw new BusinessException("相同文件正在上传或解析，请稍后再试");
            }
            DocumentEntity reusable = findReusableParsedDocument(userId, md5);
            if (reusable != null) {
                return copyParsedDocument(userId, knowledgeBase.getId(), originalFileName, file.getSize(), reusable);
            }
            return createNewDocumentAndTask(
                    userId,
                    knowledgeBase.getId(),
                    file,
                    bytes,
                    originalFileName,
                    fileType,
                    md5
            );
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException("获取文件上传锁被中断");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    public List<DocumentResponse> listByKnowledgeBase(Long userId, Long knowledgeBaseId) {
        knowledgeBaseService.getOwnedActive(userId, knowledgeBaseId);
        return documentMapper.selectList(Wrappers.<DocumentEntity>lambdaQuery()
                        .eq(DocumentEntity::getUserId, userId)
                        .eq(DocumentEntity::getKnowledgeBaseId, knowledgeBaseId)
                        .ne(DocumentEntity::getParseStatus, DocumentStatus.DELETED)
                        .orderByDesc(DocumentEntity::getCreateTime))
                .stream()
                .map(DocumentResponse::from)
                .toList();
    }

    public DocumentResponse get(Long userId, Long documentId) {
        return DocumentResponse.from(getOwnedDocument(userId, documentId));
    }

    public List<DocumentChunkResponse> chunks(Long userId, Long documentId) {
        DocumentEntity document = getOwnedDocument(userId, documentId);
        return documentChunkMapper.selectList(Wrappers.<DocumentChunkEntity>lambdaQuery()
                        .eq(DocumentChunkEntity::getDocumentId, document.getId())
                        .eq(DocumentChunkEntity::getGeneration, document.getGeneration())
                        .orderByAsc(DocumentChunkEntity::getChunkIndex))
                .stream()
                .map(DocumentChunkResponse::from)
                .toList();
    }

    public DocumentEntity getOwnedDocument(Long userId, Long documentId) {
        DocumentEntity document = documentMapper.selectOne(Wrappers.<DocumentEntity>lambdaQuery()
                .eq(DocumentEntity::getId, documentId)
                .eq(DocumentEntity::getUserId, userId)
                .ne(DocumentEntity::getParseStatus, DocumentStatus.DELETED));
        if (document == null) {
            throw new BusinessException("文档不存在或无权限访问");
        }
        knowledgeBaseService.getOwnedActive(userId, document.getKnowledgeBaseId());
        return document;
    }

    public String normalizeFileType(String fileName) {
        String fileType = extensionOf(fileName);
        validateFileType(fileType);
        return fileType;
    }

    public String buildStoredObjectKey(Long userId, Long knowledgeBaseId, String md5, String fileType) {
        return buildObjectKey(userId, knowledgeBaseId, md5, fileType);
    }

    public String detectContentType(String fileType, String requestContentType) {
        return contentType(fileType, requestContentType);
    }

    @Transactional
    public DocumentUploadResponse reuseParsedDocument(
            Long userId,
            Long knowledgeBaseId,
            String originalFileName,
            long fileSize,
            String md5
    ) {
        knowledgeBaseService.getOwnedActive(userId, knowledgeBaseId);
        DocumentEntity reusable = findReusableParsedDocument(userId, md5);
        if (reusable == null) {
            return null;
        }
        return copyParsedDocument(userId, knowledgeBaseId, originalFileName, fileSize, reusable);
    }

    @Transactional
    public DocumentUploadResponse createStoredDocumentAndTask(
            Long userId,
            Long knowledgeBaseId,
            String originalFileName,
            String fileType,
            long fileSize,
            String md5,
            String objectKey
    ) {
        knowledgeBaseService.getOwnedActive(userId, knowledgeBaseId);
        return transactions.execute(status -> persistDocumentAndTask(userId, knowledgeBaseId, originalFileName,
                fileType, fileSize, md5, storageService.bucket(), objectKey));
    }

    private DocumentUploadResponse createNewDocumentAndTask(
            Long userId, Long knowledgeBaseId, MultipartFile file, byte[] bytes,
            String originalFileName, String fileType, String md5) {
        String objectKey = buildObjectKey(userId, knowledgeBaseId, md5, fileType);
        storageService.putObject(objectKey, bytes, contentType(fileType, file.getContentType()));
        return transactions.execute(status -> persistDocumentAndTask(userId, knowledgeBaseId,
                originalFileName, fileType, file.getSize(), md5, storageService.bucket(), objectKey));
    }

    private DocumentUploadResponse persistDocumentAndTask(Long userId, Long knowledgeBaseId,
            String originalFileName, String fileType, long fileSize, String md5, String bucket, String objectKey) {
        // Recheck ownership after object upload, since a knowledge base may have been deleted meanwhile.
        knowledgeBaseService.getOwnedActive(userId, knowledgeBaseId);
        DocumentEntity document = new DocumentEntity();
        document.setUserId(userId);
        document.setKnowledgeBaseId(knowledgeBaseId);
        document.setOriginalFileName(originalFileName);
        document.setFileType(fileType);
        document.setFileSize(fileSize);
        document.setFileMd5(md5);
        document.setBucket(bucket);
        document.setObjectKey(objectKey);
        document.setParseStatus(DocumentStatus.PENDING);
        document.setGeneration(1L);
        documentMapper.insert(document);
        ParseTaskEntity task = pipeline.create(document, PipelineStage.PARSE);
        return new DocumentUploadResponse(document.getId(), task.getId(), document.getParseStatus());
    }

    private DocumentUploadResponse copyParsedDocument(Long userId, Long knowledgeBaseId,
            String originalFileName, long fileSize, DocumentEntity reusable) {
        // Reuse only the owned immutable object. Every new document has its own durable pipeline.
        return transactions.execute(status -> persistDocumentAndTask(userId, knowledgeBaseId,
                originalFileName, reusable.getFileType(), fileSize, reusable.getFileMd5(),
                reusable.getBucket(), reusable.getObjectKey()));
    }

    private DocumentEntity findReusableParsedDocument(Long userId, String md5) {
        DocumentEntity reusable = documentMapper.selectOne(Wrappers.<DocumentEntity>lambdaQuery()
                .eq(DocumentEntity::getUserId, userId)
                .eq(DocumentEntity::getFileMd5, md5)
                .eq(DocumentEntity::getParseStatus, DocumentStatus.SUCCESS)
                .orderByDesc(DocumentEntity::getCreateTime).last("LIMIT 1"));
        if (reusable != null) {
            try { knowledgeBaseService.getOwnedActive(userId, reusable.getKnowledgeBaseId()); }
            catch (BusinessException ignored) { return null; }
        }
        return reusable;
    }

    @Transactional
    public DocumentUploadResponse rebuild(Long userId, Long documentId) {
        jdbc.queryForList("SELECT id FROM document WHERE id=? AND user_id=? FOR UPDATE", Long.class, documentId, userId);
        DocumentEntity document = getOwnedDocument(userId, documentId);
        cancelTasks(documentId);
        jdbc.update("UPDATE document SET summary=NULL,summary_status='PENDING',summary_input_chars=NULL,summary_truncated=FALSE WHERE id=?", documentId);
        document.setSummary(null);
        document.setSummaryStatus("PENDING");
        document.setSummaryInputChars(null);
        document.setSummaryTruncated(false);
        document.setGeneration(document.getGeneration() + 1);
        document.setParseStatus(DocumentStatus.PENDING);
        documentMapper.updateById(document);
        ParseTaskEntity task = pipeline.create(document, PipelineStage.PARSE);
        return new DocumentUploadResponse(document.getId(), task.getId(), document.getParseStatus());
    }

    @Transactional
    public void delete(Long userId, Long documentId) {
        jdbc.queryForList("SELECT id FROM document WHERE id=? AND user_id=? FOR UPDATE", Long.class, documentId, userId);
        DocumentEntity document = getOwnedDocument(userId, documentId);
        cancelTasks(documentId);
        document.setParseStatus(DocumentStatus.DELETED);
        document.setSummaryStatus("CANCELLED");
        documentMapper.updateById(document);
        pipeline.create(document, PipelineStage.CLEANUP);
        // Objects may be shared by same-owner duplicate uploads; retention cleanup is separate.
    }

    private void cancelTasks(Long documentId) {
        jdbc.update("UPDATE parse_task SET status='CANCELLED',claim_token=NULL,lease_until=NULL,finished_time=NOW(),update_time=NOW() "
                + "WHERE document_id=? AND status IN ('PENDING','RETRYING','PROCESSING')", documentId);
    }

    private byte[] readFileBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (Exception ex) {
            throw new BusinessException("读取上传文件失败：" + ex.getMessage());
        }
    }

    private String extensionOf(String fileName) {
        int index = fileName.lastIndexOf('.');
        if (index < 0 || index == fileName.length() - 1) {
            return "txt";
        }
        return fileName.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    private void validateFileType(String fileType) {
        if (!List.of("pdf", "txt", "md", "markdown", "jpg", "jpeg", "png", "webp").contains(fileType)) {
            throw new BusinessException("支持 PDF、TXT、Markdown、JPG、PNG、WebP 文件");
        }
    }

    private String md5(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception ex) {
            throw new BusinessException("计算文件 MD5 失败：" + ex.getMessage());
        }
    }

    private String buildObjectKey(Long userId, Long knowledgeBaseId, String md5, String fileType) {
        return "u" + userId + "/kb" + knowledgeBaseId + "/" + LocalDate.now() + "/" + md5 + "." + fileType;
    }

    private String contentType(String fileType, String requestContentType) {
        if (requestContentType != null && !requestContentType.isBlank()) {
            return requestContentType;
        }
        return switch (fileType) {
            case "pdf" -> "application/pdf";
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            case "md", "markdown" -> "text/markdown;charset=utf-8";
            default -> "text/plain;charset=utf-8";
        };
    }
}
