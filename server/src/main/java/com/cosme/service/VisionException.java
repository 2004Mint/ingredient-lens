package com.cosme.service;

/**
 * 视觉识别失败。message 会直接展示给用户，所以只放人能看懂、能行动的话，
 * 上游返回的原始 JSON 只写服务端日志。
 */
public class VisionException extends RuntimeException {

    public VisionException(String message) {
        super(message);
    }

    public VisionException(String message, Throwable cause) {
        super(message, cause);
    }
}
