package com.flashsale.common.exception;

public class ServiceUnavailableException extends DomainException {
    public ServiceUnavailableException(String code, String detail) {
        super(code, detail);
    }
}
