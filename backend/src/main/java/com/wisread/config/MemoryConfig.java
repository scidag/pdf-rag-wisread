package com.wisread.config;

import com.alibaba.cloud.ai.memory.redis.RedisChatMemoryRepository;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * L1 短期会话记忆装配。
 *
 * <p>做什么：用 Spring AI 的 {@link ChatMemory} 抽象 + Redis 仓库承担“模型上下文窗口”
 * （热数据，含最近 20 条问答），按 conversationId 隔离；messages 表继续作为业务档案
 * （前端历史展示、answer_sources 引用关联、审计），两者职责分离。
 *
 * <p>为什么 Redis：多轮问答对窗口读取频繁、允许过期丢失，独立于 PG 档案写入路径；
 * 窗口裁剪（MessageWindowChatMemory）与读写全部由框架托管，替代原手写历史拼接。
 *
 * <p>已知限制：RedisChatMemoryRepository 1.0.0.2 不支持 TTL 配置，窗口键长期驻留
 * （每键最多 20 条消息，体量小），由 Redis 淘汰策略兜底。
 */
@Configuration
public class MemoryConfig {

    @Bean(destroyMethod = "close")
    public RedisChatMemoryRepository redisChatMemoryRepository(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.password:}") String password
    ) {
        RedisChatMemoryRepository.RedisBuilder builder = RedisChatMemoryRepository.builder()
                .host(host)
                .port(port)
                .timeout(3000);
        if (password != null && !password.isBlank()) {
            builder.password(password);
        }
        return builder.build();
    }

    @Bean
    public ChatMemory chatMemory(RedisChatMemoryRepository redisChatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(redisChatMemoryRepository)
                // 窗口 20 条（10 轮问答），与原 messages 表 top10 取数口径一致
                .maxMessages(20)
                .build();
    }
}
