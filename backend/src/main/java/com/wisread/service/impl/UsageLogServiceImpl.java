package com.wisread.service.impl;

import com.wisread.service.UsageLogService;

import com.wisread.entity.UsageLog;
import com.wisread.repository.UsageLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用量计费日志服务的实现。
 * 实现要点：使用 {@code REQUIRES_NEW} 独立事务写入日志，确保即使主业务事务回滚，
 * 用量记录仍被持久化；同时把写入异常吞掉并告警，绝不让计费日志拖累 AI 主流程。
 */
@Service
public class UsageLogServiceImpl implements UsageLogService {

    private static final Logger log = LoggerFactory.getLogger(UsageLogServiceImpl.class);

    private final UsageLogRepository usageLogRepository;

    public UsageLogServiceImpl(UsageLogRepository usageLogRepository) {
        this.usageLogRepository = usageLogRepository;
    }

    /**
     * 记录一条用量日志。
     * 为什么用独立事务：用量统计与主业务（如问答）解耦，主流程成功或失败都应保留计费数据；
     * 为什么吞异常：日志是“旁路”数据，写入失败绝不能导致用户提问失败。
     */
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
