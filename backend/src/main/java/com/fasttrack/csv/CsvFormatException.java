package com.fasttrack.csv;

import com.fasttrack.common.BadRequestException;

/** The uploaded file is not usable as CSV. */
public class CsvFormatException extends BadRequestException {

    public CsvFormatException(String message) {
        super(message);
    }
}
