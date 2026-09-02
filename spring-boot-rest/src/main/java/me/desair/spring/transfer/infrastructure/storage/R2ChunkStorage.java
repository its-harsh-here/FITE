package me.desair.spring.transfer.infrastructure.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(name = "storage.type", havingValue = "r2")
public class R2ChunkStorage implements ChunkStorage {

    private final S3Client s3Client;
    private final String bucketName;

    public R2ChunkStorage(
            @Value("${r2.bucket}") String bucketName,
            @Value("${r2.endpoint}") String endpoint,
            @Value("${r2.access-key}") String accessKey,
            @Value("${r2.secret-key}") String secretKey) {
            
        this.bucketName = bucketName;
        this.s3Client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .region(Region.US_EAST_1) // R2 accepts us-east-1 as the standard sigv4 region
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
        if (transferId == null || transferId.contains("/") || transferId.contains("\\") || transferId.contains(".")) {
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
            throw new StorageException("Failed to upload chunk to R2", e);
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
            throw new StorageFileNotFoundException("Chunk not found in R2");
        } catch (Exception e) {
            throw new StorageException("Failed to read chunk from R2", e);
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
            // Treat other failures conservatively as non-existent or surface error if needed
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
        } catch (Exception e) {
            throw new StorageException("Failed to delete chunk from R2", e);
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
            throw new StorageException("Failed to delete transfer chunks from R2", e);
        }
    }
}
