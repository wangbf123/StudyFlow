package com.studyflow.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsBusinessRateLimitCodeToHttp429() {
        ResponseEntity<Result<Void>> response = handler.handleBusinessException(
                new BusinessException(429, "请求过于频繁，请稍后再试")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(429);
    }

    @Test
    void keepsExistingHttpStatusForOtherBusinessExceptions() {
        ResponseEntity<Result<Void>> response = handler.handleBusinessException(
                new BusinessException(400, "参数错误")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(400);
    }
    @Test
    void unexpectedServerErrorsDoNotExposeDatabaseOrProviderMessages() {
        var response=handler.handleException(new IllegalStateException("SQL password=private-api-key"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getMessage()).doesNotContain("password","private-api-key","SQL");
    }

    @Test
    void invalidSearchWindowsReturnClientError() {
        var response=handler.handleArgumentException(new IllegalArgumentException("private details"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getCode()).isEqualTo(400);
    }

}
