package com.studyflow.agent.context;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.EncodingType;

import org.springframework.stereotype.Component;

/**
 * O200K budget accounting; other providers remain estimates, supplier usage is reported separately.
 */
@Component
public class TokenCounter {
    private final com.knuddels.jtokkit.api.Encoding encoding =
            Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.O200K_BASE);

    public int count(String value) {
        return value == null ? 0 : encoding.countTokensOrdinary(value);
    }

    public String prefix(String value, int tokens) {
        if (value == null || tokens <= 0) return "";
        if (count(value) <= tokens) return value;
        int low = 0, high = value.length();
        while (low < high) {
            int mid = (low + high + 1) / 2;
            if (count(value.substring(0, mid)) <= tokens) low = mid;
            else high = mid - 1;
        }
        if (low > 0 && Character.isHighSurrogate(value.charAt(low - 1))) low--;
        return value.substring(0, low);
    }
}
