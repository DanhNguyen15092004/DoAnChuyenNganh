package com.example.doanchuyennganh.exam;

import java.time.Instant;

public class AttemptConflictException extends RuntimeException {
    final String code;
    final Instant serverTime;
    AttemptConflictException(String code, String detail, Instant serverTime) {
        super(detail); this.code = code; this.serverTime = serverTime;
    }
}
