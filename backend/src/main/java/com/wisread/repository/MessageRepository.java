package com.wisread.repository;

import com.wisread.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {

    List<Message> findByConversationIdOrderByCreatedAtAsc(Long conversationId);

    List<Message> findTop10ByConversationIdOrderByCreatedAtDesc(Long conversationId);
}
