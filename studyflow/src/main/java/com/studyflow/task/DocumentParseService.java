package com.studyflow.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.studyflow.ai.AiClient;
import com.studyflow.ai.AiResult;
import com.studyflow.document.AiSummaryEntity;
import com.studyflow.document.AiSummaryMapper;
import com.studyflow.document.DocumentChunkEntity;
import com.studyflow.document.DocumentChunkMapper;
import com.studyflow.document.DocumentEntity;
import com.studyflow.document.DocumentMapper;
import com.studyflow.document.DocumentStatus;
import com.studyflow.document.parser.DocumentTextParser;
import com.studyflow.document.parser.ParsedDocument;
import com.studyflow.document.parser.TextChunker;
import com.studyflow.embedding.EmbeddingClient;
import com.studyflow.embedding.EmbeddingProperties;
import com.studyflow.embedding.EmbeddingVectorCodec;
import com.studyflow.search.SearchService;
import com.studyflow.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentParseService {

    private final ParseTaskMapper parseTaskMapper;
    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final AiSummaryMapper aiSummaryMapper;
    private final StorageService storageService;
    private final DocumentTextParser documentTextParser;
    private final TextChunker textChunker;
    private final AiClient aiClient;
    private final EmbeddingClient embeddingClient;
    private final EmbeddingProperties embeddingProperties;
    private final EmbeddingVectorCodec embeddingVectorCodec;
    private final SearchService searchService;
    private final RedissonClient redissonClient;

    @Transactional
    public ParseTaskResult parse(Long taskId) {
        ParseTaskEntity task = parseTaskMapper.selectById(taskId);
        if (task == null) {
            log.warn("Parse task not found: {}", taskId);
            return new ParseTaskResult(taskId, TaskStatus.FAILED);
        }
        if (!List.of(TaskStatus.PENDING, TaskStatus.RETRYING).contains(task.getStatus())) {
            log.info("Skip parse task {}, status={}", taskId, task.getStatus());
            return new ParseTaskResult(taskId, task.getStatus());
        }

        try {
            markProcessing(task);
            DocumentEntity document = documentMapper.selectById(task.getDocumentId());
            if (document == null) {
                throw new IllegalStateException("文档不存在：" + task.getDocumentId());
            }

            RLock lock = redissonClient.getLock("studyflow:parse:md5:" + document.getFileMd5());
            if (!lock.tryLock(10, 120, TimeUnit.SECONDS)) {
                throw new IllegalStateException("相同文件正在解析，请稍后重试");
            }
            try {
                DocumentEntity reusable = findReusableParsedDocument(document);
                if (reusable != null) {
                    copyParsedResult(document, task, reusable);
                    return new ParseTaskResult(taskId, task.getStatus());
                }

                byte[] bytes = storageService.getObject(document.getObjectKey());
                ParsedDocument parsedDocument = documentTextParser.parseDocument(
                        document.getOriginalFileName(), document.getFileType(), bytes);
                List<TextChunker.ParsedChunk> parsedChunks = textChunker.chunkDocument(parsedDocument);
                if (parsedChunks.isEmpty()) {
                    throw new IllegalStateException("未解析到有效文本分片");
                }
                List<String> chunkContents = parsedChunks.stream()
                        .map(TextChunker.ParsedChunk::content)
                        .toList();
                List<List<Double>> embeddings = createEmbeddings(chunkContents);
                List<DocumentChunkEntity> savedChunks = refreshChunks(document, parsedChunks, embeddings);

                AiResult aiResult = aiClient.summarize(document.getOriginalFileName(), String.join("\n", chunkContents));
                saveSummary(document, aiResult);

                document.setParseStatus(DocumentStatus.SUCCESS);
                document.setParserType(parsedDocument.parserType());
                document.setPageCount(parsedDocument.pageCount());
                document.setOcrPageCount(parsedDocument.ocrPageCount());
                document.setOcrElapsedMs(parsedDocument.ocrElapsedMs());
                document.setSummary(aiResult.summary());
                documentMapper.updateById(document);
                searchService.indexChunks(document, savedChunks);

                task.setStatus(TaskStatus.SUCCESS);
                task.setErrorMessage(null);
                task.setFinishedTime(LocalDateTime.now());
                parseTaskMapper.updateById(task);
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        } catch (Exception ex) {
            markFailed(task, ex);
        }
        return new ParseTaskResult(taskId, task.getStatus());
    }

    private void markProcessing(ParseTaskEntity task) {
        task.setStatus(TaskStatus.PROCESSING);
        task.setStartedTime(LocalDateTime.now());
        task.setErrorMessage(null);
        parseTaskMapper.updateById(task);

        DocumentEntity document = documentMapper.selectById(task.getDocumentId());
        if (document != null) {
            document.setParseStatus(DocumentStatus.PROCESSING);
            documentMapper.updateById(document);
        }
    }

    private List<DocumentChunkEntity> refreshChunks(
            DocumentEntity document,
            List<TextChunker.ParsedChunk> chunks,
            List<List<Double>> embeddings
    ) {
        documentChunkMapper.delete(Wrappers.<DocumentChunkEntity>lambdaQuery()
                .eq(DocumentChunkEntity::getDocumentId, document.getId()));
        List<DocumentChunkEntity> savedChunks = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunkEntity chunk = new DocumentChunkEntity();
            chunk.setDocumentId(document.getId());
            chunk.setKnowledgeBaseId(document.getKnowledgeBaseId());
            chunk.setUserId(document.getUserId());
            chunk.setChunkIndex(i);
            TextChunker.ParsedChunk parsedChunk = chunks.get(i);
            chunk.setContent(parsedChunk.content());
            chunk.setCharCount(parsedChunk.content().length());
            chunk.setPageNumber(parsedChunk.pageNumber());
            chunk.setParserType(parsedChunk.parserType());
            chunk.setOcrConfidence(parsedChunk.ocrConfidence());
            applyEmbedding(chunk, embeddings, i);
            documentChunkMapper.insert(chunk);
            savedChunks.add(chunk);
        }
        return savedChunks;
    }

    private List<List<Double>> createEmbeddings(List<String> chunks) {
        if (!embeddingProperties.isEnabled() || chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<List<Double>> embeddings = embeddingClient.embedBatch(chunks);
        if (embeddings.size() != chunks.size()) {
            throw new IllegalStateException("Embedding count mismatch: expected "
                    + chunks.size() + ", got " + embeddings.size());
        }
        return embeddings;
    }

    private void applyEmbedding(DocumentChunkEntity chunk, List<List<Double>> embeddings, int index) {
        if (embeddings == null || embeddings.isEmpty() || index >= embeddings.size()) {
            return;
        }
        List<Double> vector = embeddings.get(index);
        if (vector == null || vector.isEmpty()) {
            return;
        }
        chunk.setEmbedding(embeddingVectorCodec.encode(vector));
        chunk.setEmbeddingModel(embeddingClient.model());
        chunk.setEmbeddingDim(vector.size());
    }

    private DocumentEntity findReusableParsedDocument(DocumentEntity current) {
        return documentMapper.selectOne(Wrappers.<DocumentEntity>lambdaQuery()
                .eq(DocumentEntity::getFileMd5, current.getFileMd5())
                .eq(DocumentEntity::getParseStatus, DocumentStatus.SUCCESS)
                .ne(DocumentEntity::getId, current.getId())
                .orderByDesc(DocumentEntity::getCreateTime)
                .last("LIMIT 1"));
    }

    private void copyParsedResult(DocumentEntity document, ParseTaskEntity task, DocumentEntity reusable) {
        List<DocumentChunkEntity> copiedChunks = documentChunkMapper.selectList(Wrappers.<DocumentChunkEntity>lambdaQuery()
                        .eq(DocumentChunkEntity::getDocumentId, reusable.getId())
                        .orderByAsc(DocumentChunkEntity::getChunkIndex))
                .stream()
                .map(sourceChunk -> {
                    DocumentChunkEntity targetChunk = new DocumentChunkEntity();
                    targetChunk.setDocumentId(document.getId());
                    targetChunk.setKnowledgeBaseId(document.getKnowledgeBaseId());
                    targetChunk.setUserId(document.getUserId());
                    targetChunk.setChunkIndex(sourceChunk.getChunkIndex());
                    targetChunk.setContent(sourceChunk.getContent());
                    targetChunk.setCharCount(sourceChunk.getCharCount());
                    targetChunk.setPageNumber(sourceChunk.getPageNumber());
                    targetChunk.setParserType(sourceChunk.getParserType());
                    targetChunk.setOcrConfidence(sourceChunk.getOcrConfidence());
                    targetChunk.setEmbedding(sourceChunk.getEmbedding());
                    targetChunk.setEmbeddingModel(sourceChunk.getEmbeddingModel());
                    targetChunk.setEmbeddingDim(sourceChunk.getEmbeddingDim());
                    documentChunkMapper.insert(targetChunk);
                    return targetChunk;
                })
                .toList();

        AiSummaryEntity sourceSummary = aiSummaryMapper.selectOne(Wrappers.<AiSummaryEntity>lambdaQuery()
                .eq(AiSummaryEntity::getDocumentId, reusable.getId()));
        if (sourceSummary != null) {
            AiSummaryEntity targetSummary = new AiSummaryEntity();
            targetSummary.setDocumentId(document.getId());
            targetSummary.setUserId(document.getUserId());
            targetSummary.setSummary(sourceSummary.getSummary());
            targetSummary.setKeywords(sourceSummary.getKeywords());
            targetSummary.setProvider(sourceSummary.getProvider());
            aiSummaryMapper.insert(targetSummary);
        }

        document.setParseStatus(DocumentStatus.SUCCESS);
        document.setParserType(reusable.getParserType());
        document.setPageCount(reusable.getPageCount());
        document.setOcrPageCount(reusable.getOcrPageCount());
        document.setOcrElapsedMs(reusable.getOcrElapsedMs());
        document.setSummary(sourceSummary == null ? reusable.getSummary() : sourceSummary.getSummary());
        documentMapper.updateById(document);
        searchService.indexChunks(document, copiedChunks);

        task.setStatus(TaskStatus.SUCCESS);
        task.setErrorMessage(null);
        task.setFinishedTime(LocalDateTime.now());
        parseTaskMapper.updateById(task);
    }

    private void saveSummary(DocumentEntity document, AiResult aiResult) {
        AiSummaryEntity old = aiSummaryMapper.selectOne(Wrappers.<AiSummaryEntity>lambdaQuery()
                .eq(AiSummaryEntity::getDocumentId, document.getId()));
        if (old == null) {
            AiSummaryEntity summary = new AiSummaryEntity();
            summary.setDocumentId(document.getId());
            summary.setUserId(document.getUserId());
            summary.setSummary(aiResult.summary());
            summary.setKeywords(aiResult.keywords());
            summary.setProvider("mock");
            aiSummaryMapper.insert(summary);
        } else {
            old.setSummary(aiResult.summary());
            old.setKeywords(aiResult.keywords());
            old.setProvider("mock");
            aiSummaryMapper.updateById(old);
        }
    }

    private void markFailed(ParseTaskEntity task, Exception ex) {
        log.error("Parse task failed, taskId={}", task.getId(), ex);
        int nextRetry = task.getRetryCount() == null ? 1 : task.getRetryCount() + 1;
        task.setRetryCount(nextRetry);
        task.setStatus(nextRetry < task.getMaxRetryCount() ? TaskStatus.RETRYING : TaskStatus.FAILED);
        task.setErrorMessage(ex.getMessage());
        task.setFinishedTime(LocalDateTime.now());
        parseTaskMapper.updateById(task);

        DocumentEntity document = documentMapper.selectById(task.getDocumentId());
        if (document != null) {
            document.setParseStatus(TaskStatus.FAILED.equals(task.getStatus()) ? DocumentStatus.FAILED : DocumentStatus.PENDING);
            documentMapper.updateById(document);
        }
    }
}
