package me.desair.spring.transfer.api;

import me.desair.spring.transfer.infrastructure.persistence.TransferChunkEntity;
import me.desair.spring.transfer.infrastructure.persistence.TransferEntity;
import me.desair.spring.transfer.application.TransferService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import java.util.List;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public ResponseEntity<TransferEntity> createTransfer(@Valid @RequestBody CreateTransferRequest request) {
        TransferEntity transfer = transferService.createTransfer(request.getFileName(), request.getFileSize(), request.getContentType());
        return ResponseEntity.ok(transfer);
    }

    @GetMapping("/{transferId}")
    public ResponseEntity<TransferEntity> getTransferDetails(
            @PathVariable String transferId, 
            @RequestParam(required = false) String token) {
        return ResponseEntity.ok(transferService.getTransfer(transferId, token));
    }

    @GetMapping("/code/{transferCode}")
    public ResponseEntity<TransferEntity> getTransferByCode(@PathVariable String transferCode) {
        return ResponseEntity.ok(transferService.getTransferByCode(transferCode));
    }

    @GetMapping("/{transferId}/chunks")
    public ResponseEntity<List<Integer>> getAvailableChunks(
            @PathVariable String transferId,
            @RequestParam(required = false) String token) {
        return ResponseEntity.ok(transferService.getAvailableChunks(transferId, token));
    }

    @PostMapping("/{transferId}/chunks/{chunkIndex}/upload-url")
    public ResponseEntity<ChunkUploadUrlResponse> getChunkUploadUrl(
            @PathVariable String transferId,
            @PathVariable int chunkIndex,
            @Valid @RequestBody ChunkUploadUrlRequest request) {
        return ResponseEntity.ok(transferService.getChunkUploadUrl(
                transferId, chunkIndex, request.getChecksum(), request.getMd5Checksum(), request.getSize()));
    }

    @PostMapping("/{transferId}/chunks/{chunkIndex}/commit")
    public ResponseEntity<TransferChunkEntity> commitChunk(
            @PathVariable String transferId,
            @PathVariable int chunkIndex,
            @Valid @RequestBody ChunkCommitRequest request) {
        return ResponseEntity.ok(transferService.commitChunk(
                transferId, chunkIndex, request.getChecksum(), request.getMd5Checksum(), request.getSize()));
    }

    @GetMapping("/{transferId}/chunks/{chunkIndex}/download-url")
    public ResponseEntity<ChunkDownloadUrlResponse> getChunkDownloadUrl(
            @PathVariable String transferId,
            @PathVariable int chunkIndex,
            @RequestParam(required = false) String token) {
        return ResponseEntity.ok(transferService.getChunkDownloadUrl(transferId, chunkIndex, token));
    }

    @PutMapping("/{transferId}/chunks/{chunkIndex}")
    public ResponseEntity<Void> uploadChunk(
            @PathVariable String transferId,
            @PathVariable int chunkIndex,
            @RequestHeader(value = "Upload-Checksum", required = false) String uploadChecksum,
            @RequestHeader(value = "X-Checksum-SHA256", required = false) String sha256Checksum,
            @RequestHeader(value = "X-Chunk-Checksum", required = false) String chunkChecksum,
            HttpServletRequest request) throws Exception {
        
        String expectedChecksum = uploadChecksum != null ? uploadChecksum : (sha256Checksum != null ? sha256Checksum : chunkChecksum);
        transferService.uploadChunk(transferId, chunkIndex, expectedChecksum, request.getInputStream(), request.getContentLength());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{transferId}/chunks/{chunkIndex}")
    public ResponseEntity<InputStreamResource> downloadChunk(
            @PathVariable String transferId,
            @PathVariable int chunkIndex,
            @RequestParam(required = false) String token) throws Exception {
            
        TransferChunkEntity chunkInfo = transferService.getChunkInfo(transferId, chunkIndex, token);
        InputStreamResource resource = new InputStreamResource(transferService.getChunkStream(transferId, chunkIndex, token));
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(chunkInfo.getSize()))
            .header("Upload-Checksum", chunkInfo.getChecksum())
            .header("X-Chunk-Checksum", chunkInfo.getChecksum())
            .header("X-Checksum-SHA256", chunkInfo.getChecksum())
            .body(resource);
    }

    @PostMapping("/{transferId}/complete")
    public ResponseEntity<Void> completeTransfer(@PathVariable String transferId) {
        try {
            transferService.completeTransfer(transferId);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }
}
