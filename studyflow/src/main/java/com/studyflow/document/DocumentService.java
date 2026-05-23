package com.studyflow.document;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.studyflow.common.BusinessException;
import com.studyflow.kb.KnowledgeBaseEntity;
import com.studyflow.kb.KnowledgeBaseService;
import com.studyflow.search.SearchService;
import com.studyflow.storage.StorageService;
import com.studyflow.task.ParseTaskEntity;
import com.studyflow.task.ParseTaskMapper;
import com.studyflow.task.TaskPublisher;
import com.studyflow.task.TaskStatus;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class DocumentService {

    private final KnowledgeBaseService knowledgeBaseService;
    private final StorageService storageService;
    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final AiSummaryMapper aiSummaryMapper;
    private final ParseTaskMapper parseTaskMapper;
    private final TaskPublisher taskPublisher;
    private final RedissonClient redissonClient;
    private final SearchService searchService;

    @Transactional
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
            if (!lock.tryLock(5, 30, TimeUnit.SECONDS)) {
                throw new BusinessException("相同文件正在上传或解析，请稍后再试");
            }
            DocumentEntity reusable = findReusableParsedDocument(md5);
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
                        .orderByAsc(DocumentChunkEntity::getChunkIndex))
                .stream()
                .map(DocumentChunkResponse::from)
                .toList();
    }

    public DocumentEntity getOwnedDocument(Long userId, Long documentId) {
        DocumentEntity document = documentMapper.selectOne(Wrappers.<DocumentEntity>lambdaQuery()
                .eq(DocumentEntity::getId, documentId)
                .eq(DocumentEntity::getUserId, userId));
        if (document == null) {
            throw new BusinessException("文档不存在或无权限访问");
        }
        return document;
    }

    private DocumentUploadResponse createNewDocumentAndTask(
            Long userId,
            Long knowledgeBaseId,
            MultipartFile file,
            byte[] bytes,
            String originalFileName,
            String fileType,
            String md5
    ) {
        String objectKey = buildObjectKey(userId, knowledgeBaseId, md5, fileType);
        storageService.putObject(objectKey, bytes, contentType(fileType, file.getContentType()));

        DocumentEntity document = new DocumentEntity();
        document.setUserId(userId);
        document.setKnowledgeBaseId(knowledgeBaseId);
        document.setOriginalFileName(originalFileName);
        document.setFileType(fileType);
        document.setFileSize(file.getSize());
        document.setFileMd5(md5);
        document.setBucket(storageService.bucket());
        document.setObjectKey(objectKey);
        document.setParseStatus(DocumentStatus.PENDING);
        documentMapper.insert(document);

        ParseTaskEntity task = new ParseTaskEntity();
        task.setTaskNo("PT" + UUID.randomUUID().toString().replace("-", ""));
        task.setDocumentId(document.getId());
        task.setUserId(userId);
        task.setStatus(TaskStatus.PENDING);
        task.setRetryCount(0);
        task.setMaxRetryCount(3);
        parseTaskMapper.insert(task);

        publishAfterCommit(task.getId());
        return new DocumentUploadResponse(document.getId(), task.getId(), document.getParseStatus());
    }

    private DocumentUploadResponse copyParsedDocument(
            Long userId,
            Long knowledgeBaseId,
            String originalFileName,
            long fileSize,
            DocumentEntity reusable
    ) {
        DocumentEntity document = new DocumentEntity();
        document.setUserId(userId);
        document.setKnowledgeBaseId(knowledgeBaseId);
        document.setOriginalFileName(originalFileName);
        document.setFileType(reusable.getFileType());
        document.setFileSize(fileSize);
        document.setFileMd5(reusable.getFileMd5());
        document.setBucket(reusable.getBucket());
        document.setObjectKey(reusable.getObjectKey());
        document.setParseStatus(DocumentStatus.SUCCESS);
        document.setSummary(reusable.getSummary());
        documentMapper.insert(document);

        List<DocumentChunkEntity> copiedChunks = copyChunks(reusable, document);
        copySummary(reusable, document);
        searchService.indexChunks(document, copiedChunks);

        ParseTaskEntity task = new ParseTaskEntity();
        task.setTaskNo("PT" + UUID.randomUUID().toString().replace("-", ""));
        task.setDocumentId(document.getId());
        task.setUserId(userId);
        task.setStatus(TaskStatus.SUCCESS);
        task.setRetryCount(0);
        task.setMaxRetryCount(3);
        task.setStartedTime(LocalDateTime.now());
        task.setFinishedTime(LocalDateTime.now());
        parseTaskMapper.insert(task);
        return new DocumentUploadResponse(document.getId(), task.getId(), DocumentStatus.SUCCESS);
    }

    private List<DocumentChunkEntity> copyChunks(DocumentEntity source, DocumentEntity target) {
        return documentChunkMapper.selectList(Wrappers.<DocumentChunkEntity>lambdaQuery()
                        .eq(DocumentChunkEntity::getDocumentId, source.getId())
                        .orderByAsc(DocumentChunkEntity::getChunkIndex))
                .stream()
                .map(sourceChunk -> {
                    DocumentChunkEntity targetChunk = new DocumentChunkEntity();
                    targetChunk.setDocumentId(target.getId());
                    targetChunk.setKnowledgeBaseId(target.getKnowledgeBaseId());
                    targetChunk.setUserId(target.getUserId());
                    targetChunk.setChunkIndex(sourceChunk.getChunkIndex());
                    targetChunk.setContent(sourceChunk.getContent());
                    targetChunk.setCharCount(sourceChunk.getCharCount());
                    documentChunkMapper.insert(targetChunk);
                    return targetChunk;
                })
                .toList();
    }

    private void copySummary(DocumentEntity source, DocumentEntity target) {
        AiSummaryEntity sourceSummary = aiSummaryMapper.selectOne(Wrappers.<AiSummaryEntity>lambdaQuery()
                .eq(AiSummaryEntity::getDocumentId, source.getId()));
        if (sourceSummary == null && target.getSummary() == null) {
            return;
        }
        AiSummaryEntity targetSummary = new AiSummaryEntity();
        targetSummary.setDocumentId(target.getId());
        targetSummary.setUserId(target.getUserId());
        targetSummary.setSummary(sourceSummary == null ? target.getSummary() : sourceSummary.getSummary());
        targetSummary.setKeywords(sourceSummary == null ? null : sourceSummary.getKeywords());
        targetSummary.setProvider(sourceSummary == null ? "mock" : sourceSummary.getProvider());
        aiSummaryMapper.insert(targetSummary);
    }

    private DocumentEntity findReusableParsedDocument(String md5) {
        return documentMapper.selectOne(Wrappers.<DocumentEntity>lambdaQuery()
                .eq(DocumentEntity::getFileMd5, md5)
                .eq(DocumentEntity::getParseStatus, DocumentStatus.SUCCESS)
                .orderByDesc(DocumentEntity::getCreateTime)
                .last("LIMIT 1"));
    }

    private void publishAfterCommit(Long taskId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    taskPublisher.publishParseTask(taskId);
                }
            });
        } else {
            taskPublisher.publishParseTask(taskId);
        }
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
        if (!List.of("pdf", "txt", "md", "markdown").contains(fileType)) {
            throw new BusinessException("V1 仅支持 PDF、TXT、Markdown 文件");
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
            case "md", "markdown" -> "text/markdown;charset=utf-8";
            default -> "text/plain;charset=utf-8";
        };
    }
}
