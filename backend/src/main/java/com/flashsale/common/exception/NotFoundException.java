package com.flashsale.common.exception;

public class NotFoundException extends DomainException {
    public NotFoundException(String code, String detail) {
        super(code, detail);
    }
}
