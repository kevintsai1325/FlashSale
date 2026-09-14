package com.flashsale.purchase.exception;

public class ConflictException extends DomainException {
    public ConflictException(String code, String detail) {
        super(code, detail);
    }
}
