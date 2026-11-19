package com.example.aftersales.platform;

import org.springframework.http.HttpStatus;

/** 这里只保存可以安全返回给客户的错误信息，不保存 SQL、凭证或上游响应体。 */
public class ServiceApiException extends RuntimeException{
    private final HttpStatus status;
    private final String code;

    public ServiceApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
