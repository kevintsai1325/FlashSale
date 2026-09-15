package com.flashsale.common.exception;

public class UnauthorizedException extends DomainException {
    public UnauthorizedException(String code, String detail) {
        super(code, detail);
    }
}
