package com.fasttrack.common;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a resource does not exist <em>or</em> does not belong to the
 * caller. Both cases return 404 on purpose: a 403 would confirm that someone
 * else's resource id exists.
 */
public class NotFoundException extends ApiException {

    public NotFoundException(String what) {
        super(HttpStatus.NOT_FOUND, "not_found", what + " not found");
    }
}
