package com.studyflow.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Checks schema and exact evidence provenance; deliberately does not infer semantic support. */
@Component
@RequiredArgsConstructor
public class EvidenceAnswerValidator {
    private final ObjectMapper objectMapper;
    private final EvidenceProperties properties;

    public EvidenceValidation validate(String raw, RagPrompt prompt) {
        if (raw == null || raw.length() > properties.getMaxOutputChars()) return invalid();
        try {
            // Strict JSON: arbitrary legacy prose and fenced output never bypass evidence validation.
            JsonNode root = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(raw);
            if (!fields(root, Set.of("claims")) || !root.path("claims").isArray()
                    || root.path("claims").size() > properties.getMaxClaims()) return invalid();
            List<RetrievedChunk> chunks = prompt == null || prompt.includedChunks() == null
                    ? List.of() : prompt.includedChunks();
            List<GroundedClaim> accepted = new ArrayList<>();
            int rejected = 0;
            int index = 0;
            for (JsonNode claim : root.path("claims")) {
                int ordinal = index++;
                if (!fields(claim, Set.of("text", "evidence")) || !claim.path("text").isTextual()
                        || claim.path("text").asText().isBlank()
                        || claim.path("text").asText().length() > properties.getMaxClaimChars()
                        || !claim.path("evidence").isArray() || claim.path("evidence").isEmpty()
                        || claim.path("evidence").size() > properties.getMaxEvidencePerClaim()) {
                    rejected++;
                    continue;
                }
                List<EvidenceSpan> spans = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                boolean valid = true;
                for (JsonNode evidence : claim.path("evidence")) {
                    if (!fields(evidence, Set.of("source_id", "quote"))
                            || !evidence.path("source_id").isTextual() || !evidence.path("quote").isTextual()) {
                        valid = false;
                        break;
                    }
                    String source = evidence.path("source_id").asText();
                    String quote = evidence.path("quote").asText();
                    if (!source.matches("S[1-9][0-9]{0,3}") || quote.length() < properties.getMinQuoteChars()
                            || quote.length() > properties.getMaxQuoteChars()) { valid = false; break; }
                    int slot = Integer.parseInt(source.substring(1)) - 1;
                    if (slot >= chunks.size()) { valid = false; break; }
                    RetrievedChunk chunk = chunks.get(slot);
                    int start = chunk.content() == null ? -1 : chunk.content().indexOf(quote);
                    if (start < 0 || chunk.chunkId() == null || !seen.add(source + "\u0000" + quote)) {
                        valid = false;
                        break;
                    }
                    spans.add(new EvidenceSpan(chunk, source, quote, start, start + quote.length(),
                            sha256(chunk.content()), null));
                }
                if (valid) accepted.add(new GroundedClaim(ordinal, claim.path("text").asText(), List.copyOf(spans)));
                else rejected++;
            }
            return new EvidenceValidation(List.copyOf(accepted), rejected, true);
        } catch (Exception ignored) { return invalid(); }
    }

    private EvidenceValidation invalid() { return new EvidenceValidation(List.of(), 1, false); }

    private boolean fields(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject() || node.size() != allowed.size()) return false;
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) if (!allowed.contains(names.next())) return false;
        return true;
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
