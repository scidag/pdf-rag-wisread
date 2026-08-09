package com.wisread.service;

import com.wisread.config.WisreadJwtProperties;
import com.wisread.dto.AuthResponse;
import com.wisread.dto.LoginRequest;
import com.wisread.dto.RegisterRequest;
import com.wisread.dto.UserResponse;
import com.wisread.entity.User;
import com.wisread.entity.UserSession;
import com.wisread.exception.ApiException;
import com.wisread.repository.UserRepository;
import com.wisread.repository.UserSessionRepository;
import com.wisread.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final UserSessionRepository userSessionRepository;
    private final JwtService jwtService;
    private final WisreadJwtProperties jwtProperties;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthService(
            UserRepository userRepository,
            UserSessionRepository userSessionRepository,
            JwtService jwtService,
            WisreadJwtProperties jwtProperties
    ) {
        this.userRepository = userRepository;
        this.userSessionRepository = userSessionRepository;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new ApiException(HttpStatus.CONFLICT, "username already exists");
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new ApiException(HttpStatus.CONFLICT, "email already exists");
        }

        User user = new User();
        user.setUsername(request.username());
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        userRepository.save(user);

        return issueTokens(user, null, null);
    }

    @Transactional
    public AuthResponse login(LoginRequest request, String device, String ipAddress) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "invalid credentials"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid credentials");
        }
        return issueTokens(user, device, ipAddress);
    }

    @Transactional
    public AuthResponse refresh(String refreshToken, String device, String ipAddress) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "missing refresh token");
        }
        String hash = sha256(refreshToken);
        UserSession session = userSessionRepository
                .findByRefreshTokenHashAndExpiresAtAfter(hash, Instant.now())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "invalid refresh token"));

        User user = userRepository.findById(session.getUserId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "user not found"));

        userSessionRepository.deleteById(session.getId());
        return issueTokens(user, device, ipAddress);
    }

    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            String hash = sha256(refreshToken);
            userSessionRepository
                    .findByRefreshTokenHashAndExpiresAtAfter(hash, Instant.now())
                    .ifPresent(session -> userSessionRepository.deleteById(session.getId()));
        }
    }

    public UserResponse getCurrentUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "user not found"));
        return toResponse(user);
    }

    private AuthResponse issueTokens(User user, String device, String ipAddress) {
        String accessToken = jwtService.createAccessToken(user.getId(), user.getUsername());
        String refreshToken = jwtService.createRefreshToken(user.getId(), user.getUsername());

        UserSession session = new UserSession();
        session.setUserId(user.getId());
        session.setRefreshTokenHash(sha256(refreshToken));
        session.setDevice(device);
        session.setIpAddress(ipAddress);
        session.setExpiresAt(Instant.now().plus(jwtProperties.getRefreshTokenTtl()));
        userSessionRepository.save(session);

        long expiresIn = jwtProperties.getAccessTokenTtl().toSeconds();
        return new AuthResponse(accessToken, refreshToken, expiresIn, toResponse(user));
    }

    private UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getEmail());
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }
}
