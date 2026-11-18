package com.fasttrack.classify;

/** The model's reply was not valid against the classification schema. */
public class InvalidClassificationException extends RuntimeException {

    public InvalidClassificationException(String message) {
        super(message);
    }
}
