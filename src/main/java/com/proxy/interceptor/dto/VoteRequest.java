package com.proxy.interceptor.dto;

import jakarta.validation.constraints.NotNull;

public record VoteRequest(@NotNull Long id,
                          @NotNull String vote,
                          String nonce,
                          String timestamp
) {}
