package com.wisread.service.impl;

import com.wisread.service.UsageLogService;

import com.wisread.entity.UsageLog;
import com.wisread.repository.UsageLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UsageLogServiceImpl implements UsageLogService {

    private static final Logger log = LoggerFactory.getLogger(UsageLogServiceImpl.class);

    private final UsageLogRepository usageLogRepository;

    public UsageLogServiceImpl(UsageLogRepository usageLogRepository) {
        this.usageLogRepository = usageLogRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(Long userId, String model, int inputTokens, int outputTokens) {
        try {
            UsageLog usageLog = new UsageLog();
            usageLog.setUserId(userId);
            usageLog.setModel(model);
            usageLog.setInputTokens(inputTokens);
            usageLog.setOutputTokens(outputTokens);
            usageLogRepository.insert(usageLog);
        } catch (RuntimeException exception) {
            // 日志写入失败不应影响 AI 主流程
            log.warn("failed to write usage log", exception);
        }
    }
}
