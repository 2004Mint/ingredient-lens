package com.cosme.service;

/** 成分 AI 研究的可读错误。 */
public class ResearchException extends RuntimeException {

    public ResearchException(String message) {
        super(message);
    }

    public ResearchException(String message, Throwable cause) {
        super(message, cause);
    }
}
