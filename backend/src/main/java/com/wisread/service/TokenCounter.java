package com.wisread.service;

import org.springframework.stereotype.Component;

@Component
public class TokenCounter {

    public int count(String text) {
        if (text == null || text.isBlank()) {
            return 1;
        }
        return Math.max(1, text.length() / 4);
    }
}
