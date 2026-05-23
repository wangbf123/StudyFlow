package com.studyflow.storage;

public interface StorageService {

    String bucket();

    void putObject(String objectKey, byte[] bytes, String contentType);

    byte[] getObject(String objectKey);
}
