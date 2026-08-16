package com.wisread.repository;

import com.wisread.entity.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface UserSessionRepository extends JpaRepository<UserSession, Long> {

    Optional<UserSession> findByRefreshTokenHashAndExpiresAtAfter(String refreshTokenHash, Instant now);

    Optional<UserSession> findByPreviousRefreshTokenHashAndExpiresAtAfter(String previousRefreshTokenHash, Instant now);

    void deleteByUserId(Long userId);
}
