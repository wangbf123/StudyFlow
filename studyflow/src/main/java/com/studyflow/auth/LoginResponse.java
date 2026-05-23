package com.studyflow.auth;

public record LoginResponse(
        Long userId,
        String username,
        String nickname,
        String tokenName,
        String tokenValue
) {
}
