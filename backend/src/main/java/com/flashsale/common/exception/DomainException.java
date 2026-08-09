package com.flashsale.common.exception;

public abstract class DomainException extends RuntimeException {
    private final String code;

    protected DomainException(String code, String detail) {
        super(detail);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
