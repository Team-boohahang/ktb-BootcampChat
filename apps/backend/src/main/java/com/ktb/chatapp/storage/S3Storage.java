package com.ktb.chatapp.storage;

import java.io.InputStream;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Slf4j
@Component
@ConditionalOnProperty(name = "file.storage.type", havingValue = "s3")
public class S3Storage implements StoragePort {

    private final S3Client s3Client = S3Client.create();
    private final String bucket;

    public S3Storage(@Value("${aws.s3.bucket}") String bucket) {
        this.bucket = bucket;
    }

    @Override
    public StoredObject put(InputStream content, String key, String contentType, long size) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .contentLength(size)
                .build();
        try {
            s3Client.putObject(request, RequestBody.fromInputStream(content, size));
        } catch (SdkException ex) {
            throw new RuntimeException("파일 저장에 실패했습니다: " + ex.getMessage(), ex);
        }
        return new StoredObject(key, size);
    }

    @Override
    public Optional<Resource> open(String key) {
        try {
            var object = s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
            return Optional.of(new InputStreamResource(object));
        } catch (NoSuchKeyException ex) {
            return Optional.empty();
        }
    }

    @Override
    public void delete(String key) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (SdkException ex) {
            throw new RuntimeException("파일 삭제에 실패했습니다: " + ex.getMessage(), ex);
        }
    }
}
