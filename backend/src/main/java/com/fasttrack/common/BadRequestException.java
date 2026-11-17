package com.fasttrack.common;

import org.springframework.http.HttpStatus;

/** Thrown for client input that passed syntax validation but is not acceptable. */
public class BadRequestException extends ApiException {

    public BadRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, "bad_request", message);
    }
}
