package com.wisread.service;

import com.wisread.entity.Message;

import java.util.List;

public interface QueryRewriteService {

    String rewrite(String question, List<Message> history, Long userId);
}
