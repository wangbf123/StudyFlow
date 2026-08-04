package com.studyflow.rag;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

@Component
public class RagReranker {

    public double score(String question, String content) {
        Set<String> queryTerms = terms(question);
        if (queryTerms.isEmpty()) {
            return 0.0;
        }
        String normalizedContent = normalize(content);
        long matched = queryTerms.stream()
                .filter(normalizedContent::contains)
                .count();
        double coverage = (double) matched / queryTerms.size();
        double phraseBonus = normalizedContent.contains(normalize(question)) ? 0.15 : 0.0;
        return Math.min(1.0, coverage + phraseBonus);
    }

    private Set<String> terms(String value) {
        Set<String> terms = new LinkedHashSet<>();
        String normalized = normalize(value);
        for (String raw : normalized.split("[^\\p{L}\\p{N}]+")) {
            if (raw.length() >= 2) {
                terms.add(raw);
            }
            if (containsHan(raw)) {
                for (int i = 0; i < raw.length(); i++) {
                    terms.add(raw.substring(i, i + 1));
                }
            }
        }
        return terms;
    }

    private String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean containsHan(String value) {
        return value.codePoints()
                .mapToObj(Character.UnicodeScript::of)
                .anyMatch(script -> script == Character.UnicodeScript.HAN);
    }
}
