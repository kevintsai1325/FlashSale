package com.flashsale.common.exception;

public class ConflictException extends DomainException {
    public ConflictException(String code, String detail) {
        super(code, detail);
    }
}
