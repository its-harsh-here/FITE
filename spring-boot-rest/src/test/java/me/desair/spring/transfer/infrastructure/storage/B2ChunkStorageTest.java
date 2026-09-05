package me.desair.spring.transfer.infrastructure.storage;

import me.desair.spring.transfer.api.ChunkDownloadUrlResponse;
import me.desair.spring.transfer.api.ChunkUploadUrlResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class B2ChunkStorageTest {

    private S3Client s3Client;
    private S3Presigner s3Presigner;
    private B2ChunkStorage storage;
    private final String bucketName = "fite-production-chunks";

    @BeforeEach
    void setUp() {
        s3Client = mock(S3Client.class);
        s3Presigner = mock(S3Presigner.class);
        storage = new B2ChunkStorage(bucketName, s3Client, s3Presigner);
    }

    @Test
    void testPutChunkUploadsWithCorrectKeyAndLength() throws Exception {
        String transferId = "tf_123";
        int chunkIndex = 0;
        String checksum = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        byte[] data = "test chunk data".getBytes();

        storage.putChunk(transferId, chunkIndex, checksum, new ByteArrayInputStream(data), data.length);

        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(requestCaptor.capture(), any(RequestBody.class));

        PutObjectRequest capturedRequest = requestCaptor.getValue();
        assertEquals(bucketName, capturedRequest.bucket());
        assertEquals("transfers/tf_123/chunks/000000_" + checksum, capturedRequest.key());
        assertEquals((long) data.length, capturedRequest.contentLength());
    }

    @Test
    void testGenerateUploadPresignedUrl() throws Exception {
        String transferId = "tf_123";
        int chunkIndex = 0;
        String checksum = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        String contentMd5 = "Q2hlY2tzdW0=";
        long size = 8388608;

        PresignedPutObjectRequest presigned = mock(PresignedPutObjectRequest.class);
        when(presigned.url()).thenReturn(new URL("https://s3.example.com/test-put-url"));
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presigned);

        ChunkUploadUrlResponse response = storage.generateUploadPresignedUrl(
                transferId, chunkIndex, checksum, contentMd5, size, Duration.ofMinutes(15));

        assertNotNull(response);
        assertEquals("https://s3.example.com/test-put-url", response.getUploadUrl());
        assertEquals("transfers/tf_123/chunks/000000_" + checksum, response.getStorageKey());
        assertEquals(contentMd5, response.getHeaders().get("Content-MD5"));
        assertNotNull(response.getExpiresAt());

        ArgumentCaptor<PutObjectPresignRequest> captor = ArgumentCaptor.forClass(PutObjectPresignRequest.class);
        verify(s3Presigner).presignPutObject(captor.capture());
        assertEquals(size, captor.getValue().putObjectRequest().contentLength());
        assertEquals(contentMd5, captor.getValue().putObjectRequest().contentMD5());
    }

    @Test
    void testGenerateDownloadPresignedUrl() throws Exception {
        String transferId = "tf_123";
        int chunkIndex = 1;
        String checksum = "abc123hash";
        long size = 8388608;

        PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
        when(presigned.url()).thenReturn(new URL("https://s3.example.com/test-get-url"));
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);

        ChunkDownloadUrlResponse response = storage.generateDownloadPresignedUrl(
                transferId, chunkIndex, checksum, size, Duration.ofMinutes(15));

        assertNotNull(response);
        assertEquals(1, response.getChunkIndex());
        assertEquals(size, response.getSize());
        assertEquals(checksum, response.getChecksum());
        assertEquals("https://s3.example.com/test-get-url", response.getDownloadUrl());
        assertNotNull(response.getExpiresAt());
    }

    @Test
    void testVerifyChunkObjectMatches() {
        HeadObjectResponse headResponse = HeadObjectResponse.builder()
                .contentLength(8388608L)
                .eTag("\"098f6bcd4621d373cade4e832627b4f6\"")
                .build();
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(headResponse);

        boolean valid = storage.verifyChunkObject("tf_123", 0, "hash", "098f6bcd4621d373cade4e832627b4f6", 8388608L);
        assertTrue(valid);
    }

    @Test
    void testVerifyChunkObjectSizeMismatch() {
        HeadObjectResponse headResponse = HeadObjectResponse.builder()
                .contentLength(4000L)
                .eTag("\"098f6bcd4621d373cade4e832627b4f6\"")
                .build();
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(headResponse);

        boolean valid = storage.verifyChunkObject("tf_123", 0, "hash", "098f6bcd4621d373cade4e832627b4f6", 8388608L);
        assertFalse(valid);
    }

    @Test
    void testVerifyChunkObjectETagMismatch() {
        HeadObjectResponse headResponse = HeadObjectResponse.builder()
                .contentLength(8388608L)
                .eTag("\"different_etag\"")
                .build();
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(headResponse);

        boolean valid = storage.verifyChunkObject("tf_123", 0, "hash", "098f6bcd4621d373cade4e832627b4f6", 8388608L);
        assertFalse(valid);
    }

    @Test
    void testVerifyChunkObjectMissing() {
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());

        boolean valid = storage.verifyChunkObject("tf_123", 0, "hash", "md5", 8388608L);
        assertFalse(valid);
    }

    @Test
    void testGetChunkReturnsStream() throws Exception {
        String transferId = "tf_123";
        int chunkIndex = 1;
        String checksum = "abc123hash";
        byte[] expectedData = "chunk data".getBytes();

        GetObjectResponse response = GetObjectResponse.builder().build();
        ResponseInputStream<GetObjectResponse> s3Stream = new ResponseInputStream<>(
                response,
                AbortableInputStream.create(new ByteArrayInputStream(expectedData))
        );

        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(s3Stream);

        InputStream resultStream = storage.getChunk(transferId, chunkIndex, checksum);
        assertNotNull(resultStream);
        assertArrayEquals(expectedData, resultStream.readAllBytes());

        ArgumentCaptor<GetObjectRequest> requestCaptor = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client).getObject(requestCaptor.capture());
        assertEquals("transfers/tf_123/chunks/000001_" + checksum, requestCaptor.getValue().key());
    }

    @Test
    void testGetChunkThrowsStorageFileNotFoundOnNoSuchKey() {
        when(s3Client.getObject(any(GetObjectRequest.class))).thenThrow(NoSuchKeyException.builder().message("Key not found").build());

        assertThrows(StorageFileNotFoundException.class, () ->
                storage.getChunk("tf_123", 0, "hash"));
    }

    @Test
    void testGetChunkThrowsStorageExceptionOnGeneralS3Failure() {
        when(s3Client.getObject(any(GetObjectRequest.class))).thenThrow(S3Exception.builder().message("Network timeout").build());

        StorageException ex = assertThrows(StorageException.class, () ->
                storage.getChunk("tf_123", 0, "hash"));
        assertTrue(ex.getMessage().contains("Failed to read chunk from Backblaze B2"));
    }

    @Test
    void testExistsReturnsTrueWhenObjectPresent() {
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().build());

        boolean exists = storage.exists("tf_123", 0, "hash");
        assertTrue(exists);

        ArgumentCaptor<HeadObjectRequest> requestCaptor = ArgumentCaptor.forClass(HeadObjectRequest.class);
        verify(s3Client).headObject(requestCaptor.capture());
        assertEquals("transfers/tf_123/chunks/000000_hash", requestCaptor.getValue().key());
    }

    @Test
    void testExistsReturnsFalseWhenNoSuchKey() {
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());

        boolean exists = storage.exists("tf_123", 0, "hash");
        assertFalse(exists);
    }

    @Test
    void testDeleteChunkSendsDeleteObjectRequest() throws Exception {
        storage.deleteChunk("tf_123", 2, "hash123");

        ArgumentCaptor<DeleteObjectRequest> requestCaptor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(requestCaptor.capture());
        assertEquals("transfers/tf_123/chunks/000002_hash123", requestCaptor.getValue().key());
    }

    @Test
    void testDeleteTransferDeletesAllChunksUnderPrefix() throws Exception {
        String transferId = "tf_cleanup";
        S3Object obj1 = S3Object.builder().key("transfers/tf_cleanup/chunks/000000_h1").build();
        S3Object obj2 = S3Object.builder().key("transfers/tf_cleanup/chunks/000001_h2").build();

        ListObjectsV2Response listResponse = ListObjectsV2Response.builder()
                .contents(List.of(obj1, obj2))
                .build();

        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(listResponse);

        storage.deleteTransfer(transferId);

        ArgumentCaptor<ListObjectsV2Request> listCaptor = ArgumentCaptor.forClass(ListObjectsV2Request.class);
        verify(s3Client).listObjectsV2(listCaptor.capture());
        assertEquals("transfers/tf_cleanup/chunks/", listCaptor.getValue().prefix());

        ArgumentCaptor<DeleteObjectsRequest> delCaptor = ArgumentCaptor.forClass(DeleteObjectsRequest.class);
        verify(s3Client).deleteObjects(delCaptor.capture());
        assertEquals(2, delCaptor.getValue().delete().objects().size());
    }

    @Test
    void testDeleteTransferNoOpWhenEmpty() throws Exception {
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().contents(List.of()).build());

        storage.deleteTransfer("tf_empty");

        verify(s3Client, never()).deleteObjects(any(DeleteObjectsRequest.class));
    }

    @Test
    void testValidationPreventsBadTransferIdAndIndex() {
        byte[] data = new byte[]{1, 2, 3};
        assertThrows(StorageException.class, () ->
                storage.putChunk("../bad", 0, "hash", new ByteArrayInputStream(data), data.length));
        assertThrows(StorageException.class, () ->
                storage.putChunk("tf_1", -1, "hash", new ByteArrayInputStream(data), data.length));
    }
}
