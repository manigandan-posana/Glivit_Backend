package com.glivt.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/** Consistent success/error envelope for every REST response. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        T data,
        ApiError error,
        String correlationId,
        Instant timestamp) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, RequestContext.getCorrelationId(), Instant.now());
    }

    public static <T> ApiResponse<T> fail(ApiError error) {
        return new ApiResponse<>(false, null, error, RequestContext.getCorrelationId(), Instant.now());
    }
}
