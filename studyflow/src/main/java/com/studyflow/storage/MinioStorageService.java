package com.studyflow.storage;

import com.studyflow.common.BusinessException;
import io.minio.BucketExistsArgs;
import io.minio.ComposeObjectArgs;
import io.minio.ComposeSource;
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MinioStorageService implements StorageService {

    private final MinioClient minioClient;
    private final MinioProperties properties;

    @Override
    public String bucket() {
        return properties.bucket();
    }

    @Override
    public void putObject(String objectKey, byte[] bytes, String contentType) {
        putObject(objectKey, new ByteArrayInputStream(bytes), bytes.length, contentType);
    }

    @Override
    public void putObject(String objectKey, InputStream inputStream, long size, String contentType) {
        try {
            ensureBucket();
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .contentType(contentType)
                    .stream(inputStream, size, -1)
                    .build());
        } catch (Exception ex) {
            throw new BusinessException("Upload object to MinIO failed: " + ex.getMessage());
        }
    }

    @Override
    public byte[] getObject(String objectKey) {
        try (var stream = minioClient.getObject(GetObjectArgs.builder()
                .bucket(properties.bucket())
                .object(objectKey)
                .build())) {
            return stream.readAllBytes();
        } catch (Exception ex) {
            throw new BusinessException("Read object from MinIO failed: " + ex.getMessage());
        }
    }

    @Override
    public void composeObject(List<String> sourceObjectKeys, String targetObjectKey, String contentType) {
        try {
            ensureBucket();
            minioClient.composeObject(ComposeObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(targetObjectKey)
                    .headers(headers(contentType))
                    .sources(sourceObjectKeys.stream()
                            .map(key -> ComposeSource.builder()
                                    .bucket(properties.bucket())
                                    .object(key)
                                    .build())
                            .toList())
                    .build());
        } catch (Exception ex) {
            throw new BusinessException("Merge MinIO parts failed: " + ex.getMessage());
        }
    }

    @Override
    public void copyObject(String sourceObjectKey, String targetObjectKey, String contentType) {
        try {
            ensureBucket();
            minioClient.copyObject(CopyObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(targetObjectKey)
                    .headers(headers(contentType))
                    .source(CopySource.builder()
                            .bucket(properties.bucket())
                            .object(sourceObjectKey)
                            .build())
                    .build());
        } catch (Exception ex) {
            throw new BusinessException("Copy MinIO object failed: " + ex.getMessage());
        }
    }

    @Override
    public void removeObject(String objectKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .build());
        } catch (Exception ex) {
            throw new BusinessException("Remove MinIO object failed: " + ex.getMessage());
        }
    }

    private Map<String, String> headers(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return Map.of();
        }
        return Map.of("Content-Type", contentType);
    }

    private void ensureBucket() throws Exception {
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder()
                .bucket(properties.bucket())
                .build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder()
                    .bucket(properties.bucket())
                    .build());
        }
    }
}
