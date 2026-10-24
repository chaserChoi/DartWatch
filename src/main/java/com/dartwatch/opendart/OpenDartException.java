package com.dartwatch.opendart;

public class OpenDartException extends RuntimeException {
    public enum Kind {
        CONFIGURATION, AUTHENTICATION, RATE_LIMIT, INVALID_REQUEST, UPSTREAM_SYSTEM, TIMEOUT,
        HTTP, HTTP_SERVER, NETWORK, INTERRUPTED, INVALID_RESPONSE, API_REJECTED
    }

    private final Kind kind;
    private final String status;

    public OpenDartException(Kind kind) { this(kind, null); }

    public OpenDartException(Kind kind, String status) {
        // Do not attach upstream exceptions: their messages may contain the authenticated request URI.
        super("OpenDART request failed: " + kind.name());
        this.kind = kind;
        this.status = status != null && status.matches("[0-9]{3}") ? status : null;
    }

    public Kind getKind() { return kind; }
    public String getStatus() { return status; }

    public static boolean isEmpty(String status, String message) {
        if (status == null || message == null || message.isBlank()) {
            throw new OpenDartException(Kind.INVALID_RESPONSE);
        }
        switch (status) {
            case "000": return false;
            case "013": return true;
            case "010", "011", "012", "901": throw new OpenDartException(Kind.AUTHENTICATION, status);
            case "020": throw new OpenDartException(Kind.RATE_LIMIT, status);
            case "021", "100": throw new OpenDartException(Kind.INVALID_REQUEST, status);
            case "800", "900": throw new OpenDartException(Kind.UPSTREAM_SYSTEM, status);
            default: throw new OpenDartException(Kind.API_REJECTED, status);
        }
    }
}
