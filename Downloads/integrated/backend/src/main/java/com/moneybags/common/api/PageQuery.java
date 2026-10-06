package com.moneybags.common.api;
import jakarta.validation.constraints.*;
public record PageQuery(@Min(0) int page,@Min(1) @Max(100) int size) {
    public long offset() { return (long)page*size; }
}
