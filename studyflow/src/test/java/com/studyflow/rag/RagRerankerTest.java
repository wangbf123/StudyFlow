package com.studyflow.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RagRerankerTest {

    private final RagReranker reranker = new RagReranker();

    @Test
    void scoresMatchedTermsHigherThanUnrelatedContent() {
        double matched = reranker.score("BM25 向量检索 rerank",
                "本段讲 BM25 召回、向量检索和 rerank 重排如何融合。");
        double unrelated = reranker.score("BM25 向量检索 rerank",
                "本段介绍登录、注册和用户资料管理。");

        assertTrue(matched > unrelated);
    }

    @Test
    void givesPhraseBonusForExactQuery() {
        double phrase = reranker.score("hybrid retrieval pipeline",
                "The system implements a hybrid retrieval pipeline for StudyFlow.");
        double split = reranker.score("hybrid retrieval pipeline",
                "The system has hybrid ranking and retrieval results.");

        assertTrue(phrase > split);
    }
}
