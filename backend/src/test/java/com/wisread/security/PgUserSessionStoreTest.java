package com.wisread.security;

import com.wisread.config.WisreadJwtProperties;
import com.wisread.entity.UserSession;
import com.wisread.repository.UserSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PgUserSessionStoreTest {

    @Mock
    private UserSessionRepository repository;

    private PgUserSessionStore store;

    @BeforeEach
    void setUp() {
        WisreadJwtProperties jwtProperties = new WisreadJwtProperties();
        jwtProperties.setRefreshTokenTtl(Duration.ofHours(24));
        store = new PgUserSessionStore(repository, jwtProperties);
    }

    @Test
    void locateFindsCurrentSession() {
        String hash = UserSessionStore.hashToken("token");
        UserSession row = session(hash, null, Instant.now().plusSeconds(3600));
        when(repository.findByRefreshTokenHashAndExpiresAtAfter(eq(hash), any()))
                .thenReturn(Optional.of(row));

        UserSessionStore.LocateResult result = store.locate("token");

        assertThat(result).isInstanceOf(UserSessionStore.LocateResult.Found.class);
        assertThat(((UserSessionStore.LocateResult.Found) result).session().userId()).isEqualTo(1L);
    }

    @Test
    void locateDetectsReplayOnPreviousHash() {
        String oldHash = UserSessionStore.hashToken("old-token");
        String newHash = UserSessionStore.hashToken("new-token");
        when(repository.findByRefreshTokenHashAndExpiresAtAfter(any(), any()))
                .thenReturn(Optional.empty());
        when(repository.findByPreviousRefreshTokenHashAndExpiresAtAfter(eq(oldHash), any()))
                .thenReturn(Optional.of(session(newHash, oldHash,
                        Instant.now().plusSeconds(3600))));

        UserSessionStore.LocateResult result = store.locate("old-token");

        assertThat(result).isInstanceOf(UserSessionStore.LocateResult.Replay.class);
        assertThat(((UserSessionStore.LocateResult.Replay) result).userId()).isEqualTo(1L);
    }

    @Test
    void rotateKeepsAbsoluteExpiryFromCreationTime() {
        // FR-5：轮换后 expiresAt = createdAt + 24h（固定，不随刷新后移）
        Instant createdAt = Instant.now().minus(Duration.ofHours(10));
        String oldHash = UserSessionStore.hashToken("old-token");
        UserSession row = session(oldHash, null, createdAt.plus(Duration.ofHours(24)));
        when(repository.findByRefreshTokenHashAndExpiresAtAfter(eq(oldHash), any()))
                .thenReturn(Optional.of(row));

        UserSessionStore.SessionSnapshot snapshot = new UserSessionStore.SessionSnapshot(
                1L, "Chrome", "127.0.0.1", createdAt, createdAt.plus(Duration.ofHours(24)));
        UserSessionStore.RotateOutcome outcome =
                store.rotate(snapshot, "old-token", "new-token", "Chrome", "127.0.0.1");

        assertThat(outcome).isInstanceOf(UserSessionStore.RotateOutcome.Found.class);
        ArgumentCaptor<UserSession> captor = ArgumentCaptor.forClass(UserSession.class);
        verify(repository).updateById(captor.capture());
        UserSession updated = captor.getValue();
        assertThat(updated.getRefreshTokenHash()).isEqualTo(UserSessionStore.hashToken("new-token"));
        assertThat(updated.getPreviousRefreshTokenHash()).isEqualTo(oldHash);
        // 绝对到期固定在 createdAt + 24h，而不是轮换时刻 + 24h
        assertThat(updated.getExpiresAt()).isEqualTo(createdAt.plus(Duration.ofHours(24)));
    }

    @Test
    void migrateClampsAbsoluteExpiryToTwentyFourHours() {
        // redis-migrate 迁移钳制：旧行 expiresAt 超 24h 绝对期时收紧
        Instant createdAt = Instant.now().minus(Duration.ofHours(48));
        String oldHash = UserSessionStore.hashToken("old-token");
        UserSession row = new UserSession();
        row.setUserId(1L);
        row.setRefreshTokenHash(oldHash);
        row.setDevice("Chrome");
        row.setIpAddress("127.0.0.1");
        row.setExpiresAt(createdAt.plus(Duration.ofDays(7)));
        ReflectionTestUtils.setField(row, "id", 100L);
        ReflectionTestUtils.setField(row, "createdAt", createdAt);
        when(repository.findByRefreshTokenHash(eq(oldHash))).thenReturn(Optional.of(row));

        Optional<UserSessionStore.SessionSnapshot> snapshot =
                store.findCurrentIncludingExpired("old-token");

        assertThat(snapshot).isPresent();
        assertThat(snapshot.get().maxExpireAt())
                .isEqualTo(createdAt.plus(Duration.ofHours(24)));
    }

    private UserSession session(String hash, String prevHash, Instant expiresAt) {
        UserSession row = new UserSession();
        row.setUserId(1L);
        row.setRefreshTokenHash(hash);
        row.setPreviousRefreshTokenHash(prevHash);
        row.setDevice("Chrome");
        row.setIpAddress("127.0.0.1");
        row.setExpiresAt(expiresAt);
        ReflectionTestUtils.setField(row, "id", 100L);
        ReflectionTestUtils.setField(row, "createdAt", expiresAt.minus(Duration.ofHours(24)));
        return row;
    }
}
