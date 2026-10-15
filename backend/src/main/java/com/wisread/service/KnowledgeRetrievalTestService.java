package com.wisread.service;

import com.wisread.dto.KnowledgeRetrievalTestRequest;
import com.wisread.dto.KnowledgeRetrievalTestResponse;

/**
 * 知识库检索测试服务接口。
 * 对指定知识库执行一次"改写 → 召回 → 重排 → 生成答案"的全链路演练，
 * 返回各阶段明细（含检索配置快照与耗时），供前端调试知识库检索配置效果。
 * 召回口径与 Chat 绑库问答完全一致（共用 KnowledgeRetrievalPipeline）。
 */
public interface KnowledgeRetrievalTestService {

    /**
     * 执行一次检索测试。
     *
     * @param userId          当前用户 ID（仅知识库所有者可测，越权 403）
     * @param knowledgeBaseId 知识库 ID
     * @param request         测试问题
     * @return 分阶段测试结果
     */
    KnowledgeRetrievalTestResponse test(Long userId, Long knowledgeBaseId, KnowledgeRetrievalTestRequest request);
}
