package com.fasttrack.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Error responses are part of the API contract, and they must never leak
 * internals. Each branch is asserted on shape, status and message.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("a domain exception keeps its status, code and message")
    void apiExceptionPassesThrough() {
        var response = handler.handleApi(new NotFoundException("Application"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("not_found");
        assertThat(response.getBody().message()).isEqualTo("Application not found");
        assertThat(response.getBody().timestamp()).isNotNull();
    }

    @Test
    @DisplayName("rate limiting answers 429 and tells the client when to retry")
    void rateLimitAddsRetryAfter() {
        var response = handler.handleApi(new RateLimitedException("Too many syncs", 300));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("300");
        assertThat(response.getBody().code()).isEqualTo("rate_limited");
    }

    @Test
    @DisplayName("a missing integration answers 503 with the not_configured code")
    void notConfiguredIsNotAnError() {
        NotConfiguredException ex = new NotConfiguredException("gmail", "Gmail is not connected.");
        var response = handler.handleApi(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().code()).isEqualTo("not_configured");
        assertThat(ex.getFeature()).isEqualTo("gmail");
    }

    @Test
    @DisplayName("validation failures list every offending field")
    void validationErrorsAreItemised() throws Exception {
        var target = new Object();
        var binding = new BeanPropertyBindingResult(target, "request");
        binding.addError(new FieldError("request", "company", "must not be blank"));
        binding.addError(new FieldError("request", "appliedAt", null, false, null, null, null));
        var ex = new MethodArgumentNotValidException(
                new org.springframework.core.MethodParameter(
                        GlobalExceptionHandlerTest.class.getDeclaredMethod("dummy", String.class), 0),
                binding);

        var response = handler.handleValidation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("validation_failed");
        assertThat(response.getBody().fieldErrors())
                .extracting(ApiError.FieldError::field)
                .containsExactly("company", "appliedAt");
        assertThat(response.getBody().fieldErrors())
                .extracting(ApiError.FieldError::message)
                .containsExactly("must not be blank", "is invalid");
    }

    @SuppressWarnings("unused")
    private void dummy(String value) {
        // target for the MethodParameter above
    }

    @Test
    @DisplayName("an unparseable body is a 400, not a 500")
    void malformedBody() {
        var response = handler.handleUnreadable(new HttpMessageNotReadableException("boom",
                new org.springframework.mock.http.MockHttpInputMessage(new byte[0])));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("malformed_request");
    }

    @Test
    @DisplayName("an oversized upload is refused with the 2 MB limit stated")
    void oversizedUpload() {
        var response = handler.handleTooLarge(new MaxUploadSizeExceededException(2_097_152));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody().message()).contains("2 MB");
    }

    @Test
    @DisplayName("Spring Security's access denial maps to the same forbidden shape")
    void accessDenied() {
        var response = handler.handleAccessDenied(new AccessDeniedException("nope"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().code()).isEqualTo("forbidden");
    }

    @Test
    @DisplayName("an unexpected exception never leaks its message to the client")
    void unexpectedIsOpaque() {
        var response = handler.handleUnexpected(
                new IllegalStateException("jdbc://user:password@db - internal detail"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().code()).isEqualTo("internal_error");
        assertThat(response.getBody().message()).doesNotContain("password");
    }

    @Test
    @DisplayName("the error record exposes an empty field list by default")
    void apiErrorDefaults() {
        ApiError error = ApiError.of("x", "y");
        assertThat(error.fieldErrors()).isEmpty();
        assertThat(ApiError.of("x", "y", List.of(new ApiError.FieldError("a", "b"))).fieldErrors()).hasSize(1);
        assertThat(new BadRequestException("bad").getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(new ForbiddenException("no").getCode()).isEqualTo("forbidden");
    }
}
