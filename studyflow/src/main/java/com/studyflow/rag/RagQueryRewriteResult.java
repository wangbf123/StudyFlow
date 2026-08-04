package com.studyflow.rag;

public record RagQueryRewriteResult(
        String originalQuestion,
        String rewrittenQuery,
        boolean rewritten
) {

    public static RagQueryRewriteResult unchanged(String question) {
        String normalized = normalize(question);
        return new RagQueryRewriteResult(normalized, normalized, false);
    }

    public String searchQuery() {
        String query = normalize(rewrittenQuery);
        return query.isBlank() ? normalize(originalQuestion) : query;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }
}
