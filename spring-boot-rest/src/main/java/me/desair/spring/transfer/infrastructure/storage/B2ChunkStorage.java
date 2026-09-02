package me.desair.spring.transfer.infrastructure.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;



@Service
@ConditionalOnProperty(name = "storage.type", havingValue = "b2")
public class B2ChunkStorage implements ChunkStorage {

    private final S3Client s3Client;
    private final String bucketName;

    @Autowired
    public B2ChunkStorage(
            @Value("${b2.bucket}") String bucketName,
            @Value("${b2.endpoint}") String endpoint,
            @Value("${b2.access-key}") String accessKey,
            @Value("${b2.secret-key}") String secretKey,
            @Value("${b2.region}") String region) {
        this(bucketName, createS3Client(endpoint, accessKey, secretKey, region));
    }

    public B2ChunkStorage(String bucketName, S3Client s3Client) {
        this.bucketName = bucketName;
        this.s3Client = s3Client;
    }

    private static S3Client createS3Client(String endpoint, String accessKey, String secretKey, String region) {
        String normalizedEndpoint = endpoint;
        if (normalizedEndpoint != null && !normalizedEndpoint.startsWith("http://") && !normalizedEndpoint.startsWith("https://")) {
            normalizedEndpoint = "https://" + normalizedEndpoint;
        }
        return S3Client.builder()
                .endpointOverride(URI.create(normalizedEndpoint))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .region(Region.of(region))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }

    private String getObjectKey(String transferId, int chunkIndex, String checksum) {
        validateTransferId(transferId);
        if (chunkIndex < 0) {
            throw new StorageException("Chunk index cannot be negative");
        }
        String chunkName = (checksum != null && !checksum.isBlank()) 
            ? String.format("%06d_%s", chunkIndex, checksum.toLowerCase()) 
            : String.format("%06d", chunkIndex);
            
        return "transfers/" + transferId + "/chunks/" + chunkName;
    }

    private String getTransferPrefix(String transferId) {
        validateTransferId(transferId);
        return "transfers/" + transferId + "/chunks/";
    }

    private void validateTransferId(String transferId) {
        if (transferId == null || transferId.isBlank() || transferId.contains("/") || transferId.contains("\\") || transferId.contains("..")) {
            throw new StorageException("Invalid transferId");
        }
    }

    @Override
    public void putChunk(String transferId, int chunkIndex, String checksum, InputStream data, long size) throws StorageException {
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(getObjectKey(transferId, chunkIndex, checksum))
                    .contentLength(size)
                    .build();
            s3Client.putObject(request, RequestBody.fromInputStream(data, size));
        } catch (Exception e) {
            throw new StorageException("Failed to upload chunk to Backblaze B2", e);
        }
    }

    @Override
    public InputStream getChunk(String transferId, int chunkIndex, String checksum) throws StorageFileNotFoundException, StorageException {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(getObjectKey(transferId, chunkIndex, checksum))
                    .build();
            return s3Client.getObject(request);
        } catch (NoSuchKeyException e) {
            throw new StorageFileNotFoundException("Chunk not found in Backblaze B2");
        } catch (Exception e) {
            throw new StorageException("Failed to read chunk from Backblaze B2", e);
        }
    }

    @Override
    public boolean exists(String transferId, int chunkIndex, String checksum) {
        try {
            HeadObjectRequest request = HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(getObjectKey(transferId, chunkIndex, checksum))
                    .build();
            s3Client.headObject(request);
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (Exception e) {
            return false; 
        }
    }

    @Override
    public void deleteChunk(String transferId, int chunkIndex, String checksum) throws StorageException {
        try {
            DeleteObjectRequest request = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(getObjectKey(transferId, chunkIndex, checksum))
                    .build();
            s3Client.deleteObject(request);
        } catch (NoSuchKeyException e) {
            // Idempotent deletion
        } catch (Exception e) {
            throw new StorageException("Failed to delete chunk from Backblaze B2", e);
        }
    }

    @Override
    public void deleteTransfer(String transferId) throws StorageException {
        try {
            String prefix = getTransferPrefix(transferId);
            ListObjectsV2Request listReq = ListObjectsV2Request.builder()
                    .bucket(bucketName)
                    .prefix(prefix)
                    .build();
            
            ListObjectsV2Response listRes = s3Client.listObjectsV2(listReq);
            
            if (listRes.contents().isEmpty()) {
                return;
            }
            
            List<ObjectIdentifier> keysToDelete = listRes.contents().stream()
                    .map(o -> ObjectIdentifier.builder().key(o.key()).build())
                    .collect(Collectors.toList());
                    
            DeleteObjectsRequest delReq = DeleteObjectsRequest.builder()
                    .bucket(bucketName)
                    .delete(Delete.builder().objects(keysToDelete).build())
                    .build();
                    
            s3Client.deleteObjects(delReq);
        } catch (Exception e) {
            throw new StorageException("Failed to delete transfer chunks from Backblaze B2", e);
        }
    }
}
