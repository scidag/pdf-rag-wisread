package com.wisread.service;

public interface MinioStorageService {

    void putObject(String key, byte[] content, String contentType);

    void deleteObject(String key);

    byte[] getObject(String key);
}
