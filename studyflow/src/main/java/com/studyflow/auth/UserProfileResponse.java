package com.studyflow.auth;

public record UserProfileResponse(
        Long userId,
        String username,
        String nickname,
        String role
) {
}
