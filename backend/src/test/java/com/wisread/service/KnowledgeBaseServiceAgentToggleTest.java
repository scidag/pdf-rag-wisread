package com.wisread.service;

import com.wisread.dto.KnowledgeBaseUpdateRequest;
import com.wisread.entity.KnowledgeBase;
import com.wisread.repository.KnowledgeBaseRepository;
import com.wisread.service.impl.KnowledgeBaseServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库 agent 开关更新语义单测（spec 4.3 防覆盖）：
 * 更新请求不携带 agentEnabled 时不得覆盖原值；显式携带 false/true 时生效。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseServiceAgentToggleTest {

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    private KnowledgeBaseServiceImpl knowledgeBaseService;

    @BeforeEach
    void setUp() {
        knowledgeBaseService = new KnowledgeBaseServiceImpl(knowledgeBaseRepository);
    }

    // 构造已开启 agent 的知识库实体（模拟库内现状）
    private KnowledgeBase enabledKb() {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        ReflectionTestUtils.setField(knowledgeBase, "id", 7L);
        knowledgeBase.setAgentEnabled(true);
        return knowledgeBase;
    }

    @Test
    void updateWithoutAgentEnabledKeepsOriginalValue() {
        KnowledgeBase knowledgeBase = enabledKb();
        when(knowledgeBaseRepository.findByUserIdAndIdAndDeletedAtIsNull(1L, 7L))
                .thenReturn(Optional.of(knowledgeBase));
        // 全字段为 null（不携带 agentEnabled）
        KnowledgeBaseUpdateRequest request = new KnowledgeBaseUpdateRequest(null, null, null, null, null, null, null);

        KnowledgeBase updated = knowledgeBaseService.update(1L, 7L, request);

        assertThat(updated.getAgentEnabled()).isTrue();
        verify(knowledgeBaseRepository).updateById(any(KnowledgeBase.class));
    }

    @Test
    void updateWithExplicitFalseDisablesAgent() {
        KnowledgeBase knowledgeBase = enabledKb();
        when(knowledgeBaseRepository.findByUserIdAndIdAndDeletedAtIsNull(1L, 7L))
                .thenReturn(Optional.of(knowledgeBase));
        KnowledgeBaseUpdateRequest request = new KnowledgeBaseUpdateRequest(null, null, null, null, null, null, false);

        KnowledgeBase updated = knowledgeBaseService.update(1L, 7L, request);

        assertThat(updated.getAgentEnabled()).isFalse();
    }

    @Test
    void updateWithExplicitTrueEnablesAgent() {
        KnowledgeBase knowledgeBase = enabledKb();
        when(knowledgeBaseRepository.findByUserIdAndIdAndDeletedAtIsNull(1L, 7L))
                .thenReturn(Optional.of(knowledgeBase));
        KnowledgeBaseUpdateRequest request = new KnowledgeBaseUpdateRequest(null, null, null, null, null, null, true);

        KnowledgeBase updated = knowledgeBaseService.update(1L, 7L, request);

        assertThat(updated.getAgentEnabled()).isTrue();
    }
}
