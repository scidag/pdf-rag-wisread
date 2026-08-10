package com.wisread.repository;

import com.wisread.entity.AnswerSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AnswerSourceRepository extends JpaRepository<AnswerSource, Long> {

    List<AnswerSource> findByMessageIdOrderById(Long messageId);
}
