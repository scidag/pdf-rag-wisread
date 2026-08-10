package com.wisread.repository;

import com.wisread.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    Optional<Conversation> findByUserIdAndId(Long userId, Long conversationId);

    List<Conversation> findByUserIdAndDocumentIdOrderByUpdatedAtDesc(Long userId, Long documentId);
}
