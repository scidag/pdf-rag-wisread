package com.wisread.service;

public interface UsageLogService {

    void log(Long userId, String model, int inputTokens, int outputTokens);
}
