package com.studyflow.upload;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.studyflow.common.BusinessException;
import com.studyflow.document.DocumentEntity;
import com.studyflow.document.DocumentService;
import com.studyflow.document.DocumentUploadResponse;
import com.studyflow.kb.KnowledgeBaseService;
import com.studyflow.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class UploadService {

    private static final long DEFAULT_CHUNK_SIZE = 5L * 1024 * 1024;
    private static final long MIN_COMPOSE_PART_SIZE = 5L * 1024 * 1024;
    private static final long MAX_FILE_SIZE = 1024L * 1024 * 1024;

    private final KnowledgeBaseService knowledgeBaseService;
    private final DocumentService documentService;
    private final StorageService storageService;
    private final UploadSessionMapper uploadSessionMapper;
    private final UploadPartMapper uploadPartMapper;
    private final RedissonClient redissonClient;

    public UploadInitResponse init(Long userId, UploadInitRequest request) {
        knowledgeBaseService.getOwnedActive(userId, request.knowledgeBaseId());
        String fileName = normalizeFileName(request.fileName());
        String fileType = documentService.normalizeFileType(fileName);
        long fileSize = requirePositive(request.fileSize(), "fileSize");
        if (fileSize > MAX_FILE_SIZE) {
            throw new BusinessException("File exceeds max chunk upload size: 1GB");
        }
        long chunkSize = request.chunkSize() == null ? DEFAULT_CHUNK_SIZE : requirePositive(request.chunkSize(), "chunkSize");
        int totalParts = request.totalParts() == null ? totalParts(fileSize, chunkSize) : request.totalParts();
        validateChunkPlan(fileSize, chunkSize, totalParts);

        String requestMd5 = normalizeMd5(request.fileMd5());
        if (requestMd5 != null) {
            DocumentUploadResponse instant = documentService.reuseParsedDocument(
                    userId,
                    request.knowledgeBaseId(),
                    fileName,
                    fileSize,
                    requestMd5
            );
            if (instant != null) {
                return new UploadInitResponse(null, UploadSessionStatus.COMPLETED, chunkSize, totalParts,
                        List.of(), 100, true, instant);
            }
        }

        UploadSessionEntity existing = findResumableSession(userId, request.knowledgeBaseId(), fileName, fileSize);
        if (existing != null) {
            return toInitResponse(existing, false, null);
        }

        UploadSessionEntity session = new UploadSessionEntity();
        session.setUploadId("UP" + UUID.randomUUID().toString().replace("-", ""));
        session.setUserId(userId);
        session.setKnowledgeBaseId(request.knowledgeBaseId());
        session.setFileName(fileName);
        session.setFileType(fileType);
        session.setFileSize(fileSize);
        session.setFileMd5(requestMd5);
        session.setChunkSize(chunkSize);
        session.setTotalParts(totalParts);
        session.setStatus(UploadSessionStatus.INIT);
        uploadSessionMapper.insert(session);
        return toInitResponse(session, false, null);
    }

    public UploadPartResponse uploadPart(Long userId, String uploadId, Integer partNumber, String partMd5, MultipartFile file) {
        UploadSessionEntity session = getOwnedSession(userId, uploadId);
        if (UploadSessionStatus.COMPLETED.equals(session.getStatus())) {
            return toPartResponse(session, partNumber);
        }
        if (UploadSessionStatus.CANCELLED.equals(session.getStatus()) || UploadSessionStatus.MERGING.equals(session.getStatus())) {
            throw new BusinessException("Upload session is not accepting parts: " + session.getStatus());
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException("Upload part cannot be empty");
        }
        validatePartNumber(session, partNumber);
        validatePartSize(session, partNumber, file.getSize());

        String normalizedPartMd5 = normalizeMd5(partMd5);
        UploadPartEntity existing = findPart(uploadId, partNumber);
        if (existing != null && UploadPartStatus.SUCCESS.equals(existing.getStatus())) {
            if (normalizedPartMd5 == null || normalizedPartMd5.equalsIgnoreCase(existing.getPartMd5())) {
                return toPartResponse(session, partNumber);
            }
            storageService.removeObject(existing.getObjectKey());
        }

        String objectKey = tempPartObjectKey(session, partNumber);
        String computedMd5 = putPartAndMd5(objectKey, file);
        if (normalizedPartMd5 != null && !normalizedPartMd5.equalsIgnoreCase(computedMd5)) {
            storageService.removeObject(objectKey);
            throw new BusinessException("Upload part md5 mismatch");
        }

        UploadPartEntity part = existing == null ? new UploadPartEntity() : existing;
        part.setUploadId(uploadId);
        part.setPartNumber(partNumber);
        part.setPartSize(file.getSize());
        part.setPartMd5(computedMd5);
        part.setBucket(storageService.bucket());
        part.setObjectKey(objectKey);
        part.setStatus(UploadPartStatus.SUCCESS);
        if (part.getId() == null) {
            uploadPartMapper.insert(part);
        } else {
            uploadPartMapper.updateById(part);
        }

        if (UploadSessionStatus.INIT.equals(session.getStatus()) || UploadSessionStatus.FAILED.equals(session.getStatus())) {
            session.setStatus(UploadSessionStatus.UPLOADING);
            session.setErrorMessage(null);
            uploadSessionMapper.updateById(session);
        }
        return toPartResponse(session, partNumber);
    }

    public UploadProgressResponse progress(Long userId, String uploadId) {
        return toProgressResponse(getOwnedSession(userId, uploadId));
    }

    public DocumentUploadResponse complete(Long userId, String uploadId) {
        RLock lock = redissonClient.getLock("studyflow:upload:complete:" + uploadId);
        try {
            if (!lock.tryLock(5, 180, TimeUnit.SECONDS)) {
                throw new BusinessException("Upload is already being completed");
            }
            return completeLocked(userId, uploadId);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException("Complete upload lock interrupted");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private DocumentUploadResponse completeLocked(Long userId, String uploadId) {
        UploadSessionEntity session = getOwnedSession(userId, uploadId);
        if (UploadSessionStatus.COMPLETED.equals(session.getStatus()) && session.getDocumentId() != null) {
            DocumentEntity document = documentService.getOwnedDocument(userId, session.getDocumentId());
            return new DocumentUploadResponse(document.getId(), session.getTaskId(), document.getParseStatus());
        }
        if (UploadSessionStatus.CANCELLED.equals(session.getStatus())) {
            throw new BusinessException("Upload session was cancelled");
        }

        List<UploadPartEntity> parts = listParts(uploadId);
        validateCompleteParts(session, parts);
        session.setStatus(UploadSessionStatus.MERGING);
        session.setErrorMessage(null);
        uploadSessionMapper.updateById(session);

        try {
            String md5 = computeFileMd5(parts);
            DocumentUploadResponse reusable = documentService.reuseParsedDocument(
                    userId,
                    session.getKnowledgeBaseId(),
                    session.getFileName(),
                    session.getFileSize(),
                    md5
            );
            if (reusable != null) {
                completeSession(session, md5, null, reusable);
                safeDeleteTempParts(parts);
                return reusable;
            }

            String finalObjectKey = documentService.buildStoredObjectKey(
                    userId,
                    session.getKnowledgeBaseId(),
                    md5,
                    session.getFileType()
            );
            String contentType = documentService.detectContentType(session.getFileType(), null);
            if (parts.size() == 1) {
                storageService.copyObject(parts.getFirst().getObjectKey(), finalObjectKey, contentType);
            } else {
                storageService.composeObject(parts.stream()
                        .map(UploadPartEntity::getObjectKey)
                        .toList(), finalObjectKey, contentType);
            }

            DocumentUploadResponse response = documentService.createStoredDocumentAndTask(
                    userId,
                    session.getKnowledgeBaseId(),
                    session.getFileName(),
                    session.getFileType(),
                    session.getFileSize(),
                    md5,
                    finalObjectKey
            );
            completeSession(session, md5, finalObjectKey, response);
            safeDeleteTempParts(parts);
            return response;
        } catch (Exception ex) {
            session.setStatus(UploadSessionStatus.FAILED);
            session.setErrorMessage(limit(ex.getMessage(), 1000));
            uploadSessionMapper.updateById(session);
            if (ex instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new BusinessException("Complete chunk upload failed: " + ex.getMessage());
        }
    }

    private void completeSession(
            UploadSessionEntity session,
            String md5,
            String finalObjectKey,
            DocumentUploadResponse response
    ) {
        session.setFileMd5(md5);
        session.setStatus(UploadSessionStatus.COMPLETED);
        session.setFinalBucket(storageService.bucket());
        session.setFinalObjectKey(finalObjectKey);
        session.setDocumentId(response.documentId());
        session.setTaskId(response.taskId());
        session.setErrorMessage(null);
        uploadSessionMapper.updateById(session);
    }

    private UploadSessionEntity getOwnedSession(Long userId, String uploadId) {
        UploadSessionEntity session = uploadSessionMapper.selectOne(Wrappers.<UploadSessionEntity>lambdaQuery()
                .eq(UploadSessionEntity::getUploadId, uploadId)
                .eq(UploadSessionEntity::getUserId, userId));
        if (session == null) {
            throw new BusinessException("Upload session not found or access denied");
        }
        return session;
    }

    private UploadSessionEntity findResumableSession(Long userId, Long knowledgeBaseId, String fileName, long fileSize) {
        return uploadSessionMapper.selectOne(Wrappers.<UploadSessionEntity>lambdaQuery()
                .eq(UploadSessionEntity::getUserId, userId)
                .eq(UploadSessionEntity::getKnowledgeBaseId, knowledgeBaseId)
                .eq(UploadSessionEntity::getFileName, fileName)
                .eq(UploadSessionEntity::getFileSize, fileSize)
                .in(UploadSessionEntity::getStatus, List.of(
                        UploadSessionStatus.INIT,
                        UploadSessionStatus.UPLOADING,
                        UploadSessionStatus.FAILED
                ))
                .orderByDesc(UploadSessionEntity::getCreateTime)
                .last("LIMIT 1"));
    }

    private UploadPartEntity findPart(String uploadId, Integer partNumber) {
        return uploadPartMapper.selectOne(Wrappers.<UploadPartEntity>lambdaQuery()
                .eq(UploadPartEntity::getUploadId, uploadId)
                .eq(UploadPartEntity::getPartNumber, partNumber));
    }

    private List<UploadPartEntity> listParts(String uploadId) {
        return uploadPartMapper.selectList(Wrappers.<UploadPartEntity>lambdaQuery()
                        .eq(UploadPartEntity::getUploadId, uploadId)
                        .eq(UploadPartEntity::getStatus, UploadPartStatus.SUCCESS)
                        .orderByAsc(UploadPartEntity::getPartNumber))
                .stream()
                .sorted(Comparator.comparing(UploadPartEntity::getPartNumber))
                .toList();
    }

    private String putPartAndMd5(String objectKey, MultipartFile file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            try (DigestInputStream stream = new DigestInputStream(file.getInputStream(), digest)) {
                storageService.putObject(objectKey, stream, file.getSize(), "application/octet-stream");
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception ex) {
            throw new BusinessException("Upload part failed: " + ex.getMessage());
        }
    }

    private String computeFileMd5(List<UploadPartEntity> parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            for (UploadPartEntity part : parts) {
                digest.update(storageService.getObject(part.getObjectKey()));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception ex) {
            throw new BusinessException("Compute file md5 failed: " + ex.getMessage());
        }
    }

    private void validateCompleteParts(UploadSessionEntity session, List<UploadPartEntity> parts) {
        if (parts.size() != session.getTotalParts()) {
            throw new BusinessException("Upload parts are incomplete");
        }
        for (int i = 0; i < parts.size(); i++) {
            int expected = i + 1;
            UploadPartEntity part = parts.get(i);
            if (!Integer.valueOf(expected).equals(part.getPartNumber())) {
                throw new BusinessException("Upload part is missing: " + expected);
            }
            validatePartSize(session, part.getPartNumber(), part.getPartSize());
        }
    }

    private void validatePartNumber(UploadSessionEntity session, Integer partNumber) {
        if (partNumber == null || partNumber < 1 || partNumber > session.getTotalParts()) {
            throw new BusinessException("Invalid part number");
        }
    }

    private void validatePartSize(UploadSessionEntity session, Integer partNumber, long partSize) {
        if (partSize <= 0) {
            throw new BusinessException("Part size must be positive");
        }
        boolean lastPart = partNumber.equals(session.getTotalParts());
        if (!lastPart && partSize != session.getChunkSize()) {
            throw new BusinessException("Non-last part size must equal chunkSize");
        }
        if (lastPart && partSize > session.getChunkSize()) {
            throw new BusinessException("Last part size exceeds chunkSize");
        }
        if (session.getTotalParts() > 1 && !lastPart && partSize < MIN_COMPOSE_PART_SIZE) {
            throw new BusinessException("Non-last part must be at least 5MB for MinIO compose");
        }
    }

    private void validateChunkPlan(long fileSize, long chunkSize, int totalParts) {
        if (totalParts <= 0) {
            throw new BusinessException("totalParts must be positive");
        }
        int expected = totalParts(fileSize, chunkSize);
        if (totalParts != expected) {
            throw new BusinessException("totalParts does not match fileSize and chunkSize");
        }
        if (totalParts > 1 && chunkSize < MIN_COMPOSE_PART_SIZE) {
            throw new BusinessException("chunkSize must be at least 5MB for chunk upload");
        }
    }

    private int totalParts(long fileSize, long chunkSize) {
        return (int) ((fileSize + chunkSize - 1) / chunkSize);
    }

    private long requirePositive(Long value, String name) {
        if (value == null || value <= 0) {
            throw new BusinessException(name + " must be positive");
        }
        return value;
    }

    private String normalizeMd5(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String md5 = value.trim().toLowerCase(Locale.ROOT);
        if (!md5.matches("[0-9a-f]{32}")) {
            throw new BusinessException("Invalid md5");
        }
        return md5;
    }

    private String normalizeFileName(String value) {
        String fileName = value == null ? "" : value.replace("\\", "/");
        int index = fileName.lastIndexOf('/');
        if (index >= 0) {
            fileName = fileName.substring(index + 1);
        }
        fileName = fileName.trim();
        if (fileName.isBlank()) {
            throw new BusinessException("fileName is required");
        }
        return fileName;
    }

    private String tempPartObjectKey(UploadSessionEntity session, Integer partNumber) {
        return "tmp/u" + session.getUserId()
                + "/upload/" + session.getUploadId()
                + "/part-" + partNumber;
    }

    private UploadInitResponse toInitResponse(
            UploadSessionEntity session,
            boolean instantUploaded,
            DocumentUploadResponse document
    ) {
        List<Integer> uploadedParts = uploadedPartNumbers(session.getUploadId());
        return new UploadInitResponse(
                session.getUploadId(),
                session.getStatus(),
                session.getChunkSize(),
                session.getTotalParts(),
                uploadedParts,
                progress(session, uploadedParts),
                instantUploaded,
                document
        );
    }

    private UploadPartResponse toPartResponse(UploadSessionEntity session, Integer partNumber) {
        List<Integer> uploadedParts = uploadedPartNumbers(session.getUploadId());
        return new UploadPartResponse(session.getUploadId(), partNumber, uploadedParts, progress(session, uploadedParts));
    }

    private UploadProgressResponse toProgressResponse(UploadSessionEntity session) {
        List<Integer> uploadedParts = uploadedPartNumbers(session.getUploadId());
        DocumentUploadResponse document = null;
        if (session.getDocumentId() != null) {
            DocumentEntity entity = documentService.getOwnedDocument(session.getUserId(), session.getDocumentId());
            document = new DocumentUploadResponse(entity.getId(), session.getTaskId(), entity.getParseStatus());
        }
        return new UploadProgressResponse(
                session.getUploadId(),
                session.getFileName(),
                session.getFileSize(),
                session.getChunkSize(),
                session.getTotalParts(),
                uploadedParts,
                progress(session, uploadedParts),
                session.getStatus(),
                document
        );
    }

    private List<Integer> uploadedPartNumbers(String uploadId) {
        return listParts(uploadId).stream()
                .map(UploadPartEntity::getPartNumber)
                .toList();
    }

    private int progress(UploadSessionEntity session, List<Integer> uploadedParts) {
        if (UploadSessionStatus.COMPLETED.equals(session.getStatus())) {
            return 100;
        }
        if (session.getTotalParts() == null || session.getTotalParts() == 0) {
            return 0;
        }
        return Math.min(99, (int) Math.floor(uploadedParts.size() * 100.0 / session.getTotalParts()));
    }

    private void safeDeleteTempParts(List<UploadPartEntity> parts) {
        for (UploadPartEntity part : parts) {
            try {
                storageService.removeObject(part.getObjectKey());
            } catch (Exception ex) {
                log.warn("Remove temp upload part failed, key={}", part.getObjectKey(), ex);
            }
        }
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(maxLength - 3, 1)) + "...";
    }
}
