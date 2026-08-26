package com.wisread.service;

import com.wisread.dto.CreateProjectRequest;
import com.wisread.entity.Project;
import com.wisread.exception.ApiException;
import com.wisread.repository.ConversationRepository;
import com.wisread.repository.DocumentRepository;
import com.wisread.repository.ProjectRepository;
import com.wisread.service.impl.ProjectServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private ConversationRepository conversationRepository;

    private ProjectService projectService;

    @BeforeEach
    void setUp() {
        projectService = new ProjectServiceImpl(
                projectRepository,
                documentRepository,
                conversationRepository
        );
    }

    @Test
    void createUsesTrimmedName() {
        when(projectRepository.insert(org.mockito.ArgumentMatchers.any(Project.class)))
                .thenReturn(1);

        Project project = projectService.create(1L, new CreateProjectRequest("  新项目  ", null));

        assertThat(project.getName()).isEqualTo("新项目");
        assertThat(project.getUserId()).isEqualTo(1L);
    }

    @Test
    void findOwnedProjectRejectsAnotherUsersProject() {
        when(projectRepository.findByUserIdAndIdAndDeletedAtIsNull(1L, 99L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectService.findOwnedProject(1L, 99L))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listReturnsOnlyActiveProjects() {
        when(projectRepository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(1L))
                .thenReturn(List.of(new Project()));

        assertThat(projectService.list(1L)).hasSize(1);
        verify(projectRepository).findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(1L);
    }

    @Test
    void deleteMarksProjectAsDeleted() {
        Project project = new Project();
        ReflectionTestUtils.setField(project, "id", 7L);
        when(projectRepository.findByUserIdAndIdAndDeletedAtIsNull(1L, 7L))
                .thenReturn(Optional.of(project));

        projectService.delete(1L, 7L);

        assertThat(project.getDeletedAt()).isNotNull();
        verify(projectRepository).updateById(project);
    }

    @Test
    void deleteBatchDeletesEveryProject() {
        Project first = new Project();
        ReflectionTestUtils.setField(first, "id", 1L);
        Project second = new Project();
        ReflectionTestUtils.setField(second, "id", 2L);
        when(projectRepository.findByUserIdAndIdAndDeletedAtIsNull(1L, 1L))
                .thenReturn(Optional.of(first));
        when(projectRepository.findByUserIdAndIdAndDeletedAtIsNull(1L, 2L))
                .thenReturn(Optional.of(second));

        projectService.deleteBatch(1L, List.of(1L, 2L, 1L));

        assertThat(first.getDeletedAt()).isNotNull();
        assertThat(second.getDeletedAt()).isNotNull();
        verify(projectRepository).updateById(first);
        verify(projectRepository).updateById(second);
    }

    @Test
    void restoreClearsDeletedAt() {
        Project project = new Project();
        ReflectionTestUtils.setField(project, "id", 7L);
        project.setDeletedAt(java.time.Instant.now());
        when(projectRepository.findByUserIdAndIdAndDeletedAtIsNotNull(1L, 7L))
                .thenReturn(Optional.of(project));

        projectService.restore(1L, 7L);

        assertThat(project.getDeletedAt()).isNull();
        verify(projectRepository).updateById(project);
    }
}
