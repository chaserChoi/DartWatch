package com.dartwatch.kakao;

import java.time.Duration;

public final class KakaoFailure extends RuntimeException {
    public enum Kind { CONFIGURATION, CODE_REJECTED, RECONNECT_REQUIRED, PERMISSION, INVALID_TOKEN,
        RATE_LIMIT, SERVER, TRANSPORT, INVALID_RESPONSE }
    private final Kind kind;
    private final Duration retryAfter;
    public KakaoFailure(Kind kind) { this(kind, null); }
    public KakaoFailure(Kind kind, Duration retryAfter) {
        super("Kakao operation failed: " + kind.name());
        this.kind = kind;
        this.retryAfter = retryAfter;
    }
    public Kind kind() { return kind; }
    public Duration retryAfter() { return retryAfter; }
}
