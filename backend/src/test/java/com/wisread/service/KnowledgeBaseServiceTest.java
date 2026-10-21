package com.wisread.service;

import com.wisread.dto.KnowledgeBaseCreateRequest;
import com.wisread.dto.KnowledgeBaseUpdateRequest;
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

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseServiceTest {

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    private KnowledgeBaseService knowledgeBaseService;

    @BeforeEach
    void setUp() {
        knowledgeBaseService = new KnowledgeBaseServiceImpl(knowledgeBaseRepository);
    }

    // 构造指定 id 与状态的未删除知识库实体
    private KnowledgeBase kb(Long id, String status) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        ReflectionTestUtils.setField(knowledgeBase, "id", id);
        knowledgeBase.setStatus(status);
        return knowledgeBase;
    }

    @Test
    void createUsesTrimmedNameAndDraftDefaults() {
        when(knowledgeBaseRepository.insert(any(KnowledgeBase.class))).thenReturn(1);

        KnowledgeBase knowledgeBase = knowledgeBaseService.create(1L, new KnowledgeBaseCreateRequest("  新知识库  ", "描述"));

        assertThat(knowledgeBase.getName()).isEqualTo("新知识库");
        assertThat(knowledgeBase.getUserId()).isEqualTo(1L);
        assertThat(knowledgeBase.getStatus()).isEqualTo(KnowledgeBase.STATUS_DRAFT);
        assertThat(knowledgeBase.getTopK()).isEqualTo(10);
        assertThat(knowledgeBase.getSimilarityThreshold()).isEqualTo(0.65);
        assertThat(knowledgeBase.getRerankEnabled()).isTrue();
        assertThat(knowledgeBase.getRerankK()).isEqualTo(3);
    }

    @Test
    void createRejectsBlankName() {
        assertThatThrownBy(() -> knowledgeBaseService.create(1L, new KnowledgeBaseCreateRequest("   ", null)))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(knowledgeBaseRepository, never()).insert(any(KnowledgeBase.class));
    }

    @Test
    void findOwnedKnowledgeBaseRejectsAnotherUsersKnowledgeBase() {
        when(knowledgeBaseRepository.findByUserIdAndIdAndDeletedAtIsNull(1L, 99L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> knowledgeBaseService.findOwnedKnowledgeBase(1L, 99L))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void findOwnedKnowledgeBaseReturnsOwnDeletedKnowledgeBaseAs404() {
        // 自己的库已软删除：报 404 引导回收站恢复，与切库口径一致，不再误报 403 越权
        when(knowledgeBaseRepository.findByUserIdAndIdAndDeletedAtIsNull(1L, 7L))
                .thenReturn(Optional.empty());
        when(knowledgeBaseRepository.findByUserIdAndIdAndDeletedAtIsNotNull(1L, 7L))
                .thenReturn(Optional.of(kb(7L, KnowledgeBase.STATUS_ACTIVE)));

        assertThatThrownBy(() -> knowledgeBaseService.findOwnedKnowledgeBase(1L, 7L))
                .isInstanceOf(ApiException.class)
                .extracting("status", "message")
                .containsExactly(HttpStatus.NOT_FOUND, "知识库不存在或已删除");
    }

    @Test
    void updateAppliesOnlyProvidedFields() {
        KnowledgeBase knowledgeBase = kb(7L, KnowledgeBase.STATUS_DRAFT);
        knowledgeBase.setDescription("原描述");
        when(knowledgeBaseRepository.findByUserIdAndIdAndDeletedAtIsNull(1L, 7L))
                .thenReturn(Optional.of(knowledgeBase));

        KnowledgeBase updated = knowledgeBaseService.update(
                1L, 7L, new KnowledgeBaseUpdateRequest("  新名称  ", null, 5, null, null, 2, null));

        assertThat(updated.getName()).isEqualTo("新名称");
        assertThat(updated.getDescription()).isEqualTo("原描述");
        assertThat(updated.getTopK()).isEqualTo(5);
        assertThat(updated.getSimilarityThreshold()).isEqualTo(0.65);
        assertThat(updated.getRerankEnabled()).isTrue();
        assertThat(updated.getRerankK()).isEqualTo(2);
        verify(knowledgeBaseRepository).updateById(knowledgeBase);
    }

    @Test
    void publishActivatesDraft() {
        KnowledgeBase knowledgeBase = kb(7L, KnowledgeBase.STATUS_DRAFT);
        when(knowledgeBaseRepository.findByUserIdAndIdAndDeletedAtIsNull(1L, 7L))
                .thenReturn(Optional.of(knowledgeBase));

        KnowledgeBase published = knowledgeBaseService.publish(1L, 7L);

        assertThat(published.getStatus()).isEqualTo(KnowledgeBase.STATUS_ACTIVE);
        verify(knowledgeBaseRepository).updateById(knowledgeBase);
    }

    @Test
    void publishIsIdempotentWhenAlreadyActive() {
        KnowledgeBase knowledgeBase = kb(7L, KnowledgeBase.STATUS_ACTIVE);
        when(knowledgeBaseRepository.findByUserIdAndIdAndDeletedAtIsNull(1L, 7L))
                .thenReturn(Optional.of(knowledgeBase));

        KnowledgeBase published = knowledgeBaseService.publish(1L, 7L);

        assertThat(published.getStatus()).isEqualTo(KnowledgeBase.STATUS_ACTIVE);
        verify(knowledgeBaseRepository, never()).updateById(any(KnowledgeBase.class));
    }

    @Test
    void publishRejectsArchived() {
        KnowledgeBase knowledgeBase = kb(7L, KnowledgeBase.STATUS_ARCHIVED);
        when(knowledgeBaseRepository.findByUserIdAndIdAndDeletedAtIsNull(1L, 7L))
                .thenReturn(Optional.of(knowledgeBase));

        assertThatThrownBy(() -> knowledgeBaseService.publish(1L, 7L))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void archiveArchivesDraftAndRejectsProcessing() {
        KnowledgeBase draft = kb(7L, KnowledgeBase.STATUS_DRAFT);
        when(knowledgeBaseRepository.findByUserIdAndIdAndDeletedAtIsNull(1L, 7L))
                .thenReturn(Optional.of(draft));
        assertThat(knowledgeBaseService.archive(1L, 7L).getStatus()).isEqualTo(KnowledgeBase.STATUS_ARCHIVED);

        KnowledgeBase processing = kb(8L, KnowledgeBase.STATUS_PROCESSING);
        when(knowledgeBaseRepository.findByUserIdAndIdAndDeletedAtIsNull(1L, 8L))
                .thenReturn(Optional.of(processing));
        assertThatThrownBy(() -> knowledgeBaseService.archive(1L, 8L))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(processing.getStatus()).isEqualTo(KnowledgeBase.STATUS_PROCESSING);
    }

    @Test
    void unarchiveActivatesArchived() {
        KnowledgeBase knowledgeBase = kb(7L, KnowledgeBase.STATUS_ARCHIVED);
        when(knowledgeBaseRepository.findByUserIdAndIdAndDeletedAtIsNull(1L, 7L))
                .thenReturn(Optional.of(knowledgeBase));

        KnowledgeBase unarchived = knowledgeBaseService.unarchive(1L, 7L);

        assertThat(unarchived.getStatus()).isEqualTo(KnowledgeBase.STATUS_ACTIVE);
        verify(knowledgeBaseRepository).updateById(knowledgeBase);
    }

    @Test
    void deleteMarksKnowledgeBaseAsDeleted() {
        KnowledgeBase knowledgeBase = kb(7L, KnowledgeBase.STATUS_ACTIVE);
        when(knowledgeBaseRepository.findByUserIdAndIdAndDeletedAtIsNull(1L, 7L))
                .thenReturn(Optional.of(knowledgeBase));

        knowledgeBaseService.delete(1L, 7L);

        assertThat(knowledgeBase.getDeletedAt()).isNotNull();
        verify(knowledgeBaseRepository).updateById(knowledgeBase);
    }

    @Test
    void restoreClearsDeletedAt() {
        KnowledgeBase knowledgeBase = kb(7L, KnowledgeBase.STATUS_ACTIVE);
        knowledgeBase.setDeletedAt(Instant.now());
        when(knowledgeBaseRepository.findByUserIdAndIdAndDeletedAtIsNotNull(1L, 7L))
                .thenReturn(Optional.of(knowledgeBase));

        KnowledgeBase restored = knowledgeBaseService.restore(1L, 7L);

        assertThat(restored.getDeletedAt()).isNull();
        verify(knowledgeBaseRepository).updateById(knowledgeBase);
    }

    @Test
    void deleteBatchRejectsOverLimit() {
        List<Long> tooManyIds = Collections.nCopies(101, 9L);

        assertThatThrownBy(() -> knowledgeBaseService.deleteBatch(1L, tooManyIds))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(knowledgeBaseRepository, never()).updateById(any(KnowledgeBase.class));
    }

    @Test
    void refreshDerivedStatusSetsProcessingWhenDocumentsProcessing() {
        KnowledgeBase knowledgeBase = kb(7L, KnowledgeBase.STATUS_DRAFT);
        when(knowledgeBaseRepository.findById(7L)).thenReturn(Optional.of(knowledgeBase));
        when(knowledgeBaseRepository.countDocumentStatusByKnowledgeBaseId(7L)).thenReturn(List.of(
                Map.of("status", "INDEXED", "cnt", 2L),
                Map.of("status", "PROCESSING", "cnt", 1L)
        ));

        KnowledgeBase refreshed = knowledgeBaseService.refreshDerivedStatus(7L);

        assertThat(refreshed.getStatus()).isEqualTo(KnowledgeBase.STATUS_PROCESSING);
        verify(knowledgeBaseRepository).updateById(knowledgeBase);
    }

    @Test
    void refreshDerivedStatusActivatesDraftWhenAllDocumentsIndexed() {
        KnowledgeBase knowledgeBase = kb(7L, KnowledgeBase.STATUS_DRAFT);
        when(knowledgeBaseRepository.findById(7L)).thenReturn(Optional.of(knowledgeBase));
        when(knowledgeBaseRepository.countDocumentStatusByKnowledgeBaseId(7L)).thenReturn(List.of(
                Map.of("status", "INDEXED", "cnt", 3L)
        ));

        KnowledgeBase refreshed = knowledgeBaseService.refreshDerivedStatus(7L);

        assertThat(refreshed.getStatus()).isEqualTo(KnowledgeBase.STATUS_ACTIVE);
        verify(knowledgeBaseRepository).updateById(knowledgeBase);
    }

    @Test
    void refreshDerivedStatusCompletesProcessingWhenAllDocumentsIndexed() {
        // 处理中的库在全部文档索引完成后必须流转到 ACTIVE，否则永久卡死在 PROCESSING
        KnowledgeBase knowledgeBase = kb(7L, KnowledgeBase.STATUS_PROCESSING);
        when(knowledgeBaseRepository.findById(7L)).thenReturn(Optional.of(knowledgeBase));
        when(knowledgeBaseRepository.countDocumentStatusByKnowledgeBaseId(7L)).thenReturn(List.of(
                Map.of("status", "INDEXED", "cnt", 2L)
        ));

        KnowledgeBase refreshed = knowledgeBaseService.refreshDerivedStatus(7L);

        assertThat(refreshed.getStatus()).isEqualTo(KnowledgeBase.STATUS_ACTIVE);
        verify(knowledgeBaseRepository).updateById(knowledgeBase);
    }

    @Test
    void refreshDerivedStatusKeepsDraftWhenNoDocuments() {
        KnowledgeBase knowledgeBase = kb(7L, KnowledgeBase.STATUS_DRAFT);
        when(knowledgeBaseRepository.findById(7L)).thenReturn(Optional.of(knowledgeBase));
        when(knowledgeBaseRepository.countDocumentStatusByKnowledgeBaseId(7L))
                .thenReturn(Collections.emptyList());

        KnowledgeBase refreshed = knowledgeBaseService.refreshDerivedStatus(7L);

        assertThat(refreshed.getStatus()).isEqualTo(KnowledgeBase.STATUS_DRAFT);
        verify(knowledgeBaseRepository, never()).updateById(any(KnowledgeBase.class));
    }

    @Test
    void refreshDerivedStatusKeepsArchivedUnchanged() {
        KnowledgeBase knowledgeBase = kb(7L, KnowledgeBase.STATUS_ARCHIVED);
        when(knowledgeBaseRepository.findById(7L)).thenReturn(Optional.of(knowledgeBase));

        KnowledgeBase refreshed = knowledgeBaseService.refreshDerivedStatus(7L);

        assertThat(refreshed.getStatus()).isEqualTo(KnowledgeBase.STATUS_ARCHIVED);
        verify(knowledgeBaseRepository, never()).countDocumentStatusByKnowledgeBaseId(7L);
        verify(knowledgeBaseRepository, never()).updateById(any(KnowledgeBase.class));
    }
}
