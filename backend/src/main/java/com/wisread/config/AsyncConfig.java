package com.wisread.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 异步任务线程池配置。
 * 在“智阅”RAG 系统中，文档解析、向量化等耗时操作需要异步执行，
 * 以避免阻塞 HTTP 请求线程。本类提供一个名为 {@code documentTaskExecutor}
 * 的专用线程池，供文档处理相关的 {@code @Async} 方法使用。
 */
@Configuration
public class AsyncConfig {

    /**
     * 创建专用于文档处理的线程池。
     * 隔离文档任务的并发，避免与 Web 请求线程互相影响；
     * 核心线程 2、最大 4、队列 20，命名前缀便于排查线程堆栈。
     */
    @Bean(name = "documentTaskExecutor")
    Executor documentTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 常驻核心线程数，应对稳定的文档处理并发量
        executor.setCorePoolSize(2);
        // 突发流量时允许扩展到的最大线程数
        executor.setMaxPoolSize(4);
        // 线程全忙时任务的排队上限，超过则触发拒绝策略
        executor.setQueueCapacity(20);
        // 线程名前缀，方便在日志/堆栈中识别文档处理线程
        executor.setThreadNamePrefix("document-worker-");
        executor.initialize();
        return executor;
    }
}
