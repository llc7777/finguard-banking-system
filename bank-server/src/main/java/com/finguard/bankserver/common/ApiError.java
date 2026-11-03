package com.finguard.bankserver.common;

import java.time.Instant;

public record ApiError(Instant timestamp, int status, String code, String message) {
}
