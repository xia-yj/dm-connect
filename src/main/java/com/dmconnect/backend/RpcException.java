package com.dmconnect.backend;

public final class RpcException extends RuntimeException {
    private final String code;
    private final Object data;

    public RpcException(String code, String message) {
        this(code, message, null);
    }

    public RpcException(String code, String message, Object data) {
        super(message);
        this.code = code;
        this.data = data;
    }

    public String code() {
        return code;
    }

    public Object data() {
        return data;
    }
}
