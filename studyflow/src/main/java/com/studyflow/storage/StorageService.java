package com.studyflow.storage;

import java.io.InputStream;
import java.util.List;

public interface StorageService {

    String bucket();

    void putObject(String objectKey, byte[] bytes, String contentType);

    void putObject(String objectKey, InputStream inputStream, long size, String contentType);

    byte[] getObject(String objectKey);

    void composeObject(List<String> sourceObjectKeys, String targetObjectKey, String contentType);

    void copyObject(String sourceObjectKey, String targetObjectKey, String contentType);

    void removeObject(String objectKey);
}
