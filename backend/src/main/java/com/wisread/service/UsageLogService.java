package com.wisread.service;

import com.wisread.entity.UsageLog;
import com.wisread.repository.UsageLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UsageLogService {

    private static final Logger log = LoggerFactory.getLogger(UsageLogService.class);

    private final UsageLogRepository usageLogRepository;

    public UsageLogService(UsageLogRepository usageLogRepository) {
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
