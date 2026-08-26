package com.wisread.service;

import com.wisread.dto.AuthResponse;
import com.wisread.dto.LoginRequest;
import com.wisread.dto.RegisterRequest;
import com.wisread.dto.UserResponse;

public interface AuthService {

    AuthResponse register(RegisterRequest request);

    AuthResponse login(LoginRequest request, String device, String ipAddress);

    AuthResponse refresh(String refreshToken, String device, String ipAddress);

    void logout(String refreshToken);

    UserResponse getCurrentUser(Long userId);
}
