package com.wisread.service;

import com.wisread.dto.KnowledgeRetrievalConfigResponse;
import com.wisread.dto.KnowledgeRetrievalConfigUpdateRequest;
import com.wisread.entity.KnowledgeBase;
import com.wisread.exception.ApiException;
import com.wisread.repository.KnowledgeBaseRepository;
import com.wisread.service.impl.KnowledgeBaseServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库检索配置端点（GET/PATCH /retrieval-config）服务层单元测试（Mockito，不启动 Spring）：
 * 覆盖读取、部分更新（只覆盖提供的字段）、中文校验消息与越权 403。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseRetrievalConfigTest {

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    private KnowledgeBaseService knowledgeBaseService;

    private static final Long USER_ID = 1L;
    private static final Long KB_ID = 99L;

    @BeforeEach
    void setUp() {
        knowledgeBaseService = new KnowledgeBaseServiceImpl(knowledgeBaseRepository);
    }

    private KnowledgeBase kb(Integer topK, Double threshold, Boolean rerankEnabled, Integer rerankK) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        ReflectionTestUtils.setField(knowledgeBase, "id", KB_ID);
        knowledgeBase.setUserId(USER_ID);
        knowledgeBase.setTopK(topK);
        knowledgeBase.setSimilarityThreshold(threshold);
        knowledgeBase.setRerankEnabled(rerankEnabled);
        knowledgeBase.setRerankK(rerankK);
        return knowledgeBase;
    }

    private void stubOwned(KnowledgeBase knowledgeBase) {
        when(knowledgeBaseRepository.findByUserIdAndIdAndDeletedAtIsNull(USER_ID, KB_ID))
                .thenReturn(Optional.of(knowledgeBase));
    }

    // ===== GET /retrieval-config =====

    @Test
    void getReturnsCurrentConfig() {
        stubOwned(kb(8, 0.7, false, 2));

        KnowledgeRetrievalConfigResponse response = knowledgeBaseService.getRetrievalConfig(USER_ID, KB_ID);

        assertThat(response.topK()).isEqualTo(8);
        assertThat(response.similarityThreshold()).isEqualTo(0.7);
        assertThat(response.rerankEnabled()).isFalse();
        assertThat(response.rerankK()).isEqualTo(2);
    }

    @Test
    void getRejectsForeignUserWith403() {
        when(knowledgeBaseRepository.findByUserIdAndIdAndDeletedAtIsNull(2L, KB_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> knowledgeBaseService.getRetrievalConfig(2L, KB_ID))
                .isInstanceOf(ApiException.class)
                .hasMessage("无权访问该知识库")
                .extracting("status")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ===== PATCH /retrieval-config =====

    @Test
    void patchAppliesOnlyProvidedFields() {
        KnowledgeBase knowledgeBase = kb(8, 0.7, false, 2);
        stubOwned(knowledgeBase);

        // 只提供 topK 与 similarityThreshold：rerank 配置保持不变
        KnowledgeRetrievalConfigResponse response = knowledgeBaseService.updateRetrievalConfig(
                USER_ID, KB_ID, new KnowledgeRetrievalConfigUpdateRequest(5, 0.9, null, null));

        assertThat(response.topK()).isEqualTo(5);
        assertThat(response.similarityThreshold()).isEqualTo(0.9);
        assertThat(response.rerankEnabled()).isFalse();
        assertThat(response.rerankK()).isEqualTo(2);
        assertThat(knowledgeBase.getTopK()).isEqualTo(5);
        verify(knowledgeBaseRepository).updateById(knowledgeBase);
    }

    @Test
    void patchRejectsTopKOutOfRangeWithChineseMessage() {
        stubOwned(kb(8, 0.7, true, 3));

        assertThatThrownBy(() -> knowledgeBaseService.updateRetrievalConfig(
                USER_ID, KB_ID, new KnowledgeRetrievalConfigUpdateRequest(0, null, null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessage("top_k 取值范围为 1~50")
                .extracting("status")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThatThrownBy(() -> knowledgeBaseService.updateRetrievalConfig(
                USER_ID, KB_ID, new KnowledgeRetrievalConfigUpdateRequest(51, null, null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessage("top_k 取值范围为 1~50");
        verify(knowledgeBaseRepository, never()).updateById(any(KnowledgeBase.class));
    }

    @Test
    void patchRejectsThresholdOutOfRangeWithChineseMessage() {
        stubOwned(kb(8, 0.7, true, 3));

        assertThatThrownBy(() -> knowledgeBaseService.updateRetrievalConfig(
                USER_ID, KB_ID, new KnowledgeRetrievalConfigUpdateRequest(null, 1.5, null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessage("similarity_threshold 取值范围为 0~1")
                .extracting("status")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThatThrownBy(() -> knowledgeBaseService.updateRetrievalConfig(
                USER_ID, KB_ID, new KnowledgeRetrievalConfigUpdateRequest(null, -0.1, null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessage("similarity_threshold 取值范围为 0~1");
        verify(knowledgeBaseRepository, never()).updateById(any(KnowledgeBase.class));
    }

    @Test
    void patchRejectsRerankKBelowOne() {
        stubOwned(kb(8, 0.7, true, 3));

        assertThatThrownBy(() -> knowledgeBaseService.updateRetrievalConfig(
                USER_ID, KB_ID, new KnowledgeRetrievalConfigUpdateRequest(null, null, null, 0)))
                .isInstanceOf(ApiException.class)
                .hasMessage("rerank_k 取值范围最小为 1")
                .extracting("status")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(knowledgeBaseRepository, never()).updateById(any(KnowledgeBase.class));
    }

    @Test
    void patchRejectsRerankKGreaterThanMergedTopK() {
        // 场景一：库内 topK=3，只传 rerankK=5 → 合并后 5 > 3 拒绝
        KnowledgeBase smallTopK = kb(3, 0.7, true, 2);
        stubOwned(smallTopK);
        assertThatThrownBy(() -> knowledgeBaseService.updateRetrievalConfig(
                USER_ID, KB_ID, new KnowledgeRetrievalConfigUpdateRequest(null, null, null, 5)))
                .isInstanceOf(ApiException.class)
                .hasMessage("rerank_k 不能大于 top_k")
                .extracting("status")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // 场景二：库内 rerankK=5，只传 topK=2 → 合并后 5 > 2 拒绝
        KnowledgeBase largeRerankK = kb(10, 0.7, true, 5);
        stubOwned(largeRerankK);
        assertThatThrownBy(() -> knowledgeBaseService.updateRetrievalConfig(
                USER_ID, KB_ID, new KnowledgeRetrievalConfigUpdateRequest(2, null, null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessage("rerank_k 不能大于 top_k");
        verify(knowledgeBaseRepository, never()).updateById(any(KnowledgeBase.class));
    }

    @Test
    void patchAllowsEqualRerankKAndTopK() {
        KnowledgeBase knowledgeBase = kb(3, 0.7, true, 2);
        stubOwned(knowledgeBase);

        KnowledgeRetrievalConfigResponse response = knowledgeBaseService.updateRetrievalConfig(
                USER_ID, KB_ID, new KnowledgeRetrievalConfigUpdateRequest(null, null, null, 3));

        assertThat(response.rerankK()).isEqualTo(3);
        assertThat(response.topK()).isEqualTo(3);
        verify(knowledgeBaseRepository).updateById(knowledgeBase);
    }

    @Test
    void patchRejectsForeignUserWith403() {
        when(knowledgeBaseRepository.findByUserIdAndIdAndDeletedAtIsNull(2L, KB_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> knowledgeBaseService.updateRetrievalConfig(
                2L, KB_ID, new KnowledgeRetrievalConfigUpdateRequest(5, null, null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessage("无权访问该知识库")
                .extracting("status")
                .isEqualTo(HttpStatus.FORBIDDEN);
        verify(knowledgeBaseRepository, never()).updateById(any(KnowledgeBase.class));
    }
}
