package com.dch.smartrecruters.client;

public class ExternalSystemException extends RuntimeException {

    private final FailureType failureType;

    public ExternalSystemException(String operation, FailureType failureType, Throwable cause) {
        super(operation + " failed (" + failureType + "): " + cause.getMessage(), cause);
        this.failureType = failureType;
    }

    public FailureType failureType() {
        return failureType;
    }

    public boolean isTransient() {
        return failureType == FailureType.TRANSIENT;
    }
}
