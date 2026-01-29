package com.proxy.interceptor.dto;

import jakarta.validation.constraints.NotNull;

public record ApprovalRequest(@NotNull Long id, String nonce, String timestamp) {}
