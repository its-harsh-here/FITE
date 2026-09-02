# Large-File Transfer — Storage Specification

**Status:** ACTIVE — Storage Baseline  
**Document:** `STORAGE.md`  
**Version:** 1.0  
**Depends on:** `MVP.md`, `TECHNICAL.md`, `ARCHITECTURE.md`, `TRANSFER-PROTOCOL.md`, `API.md`

---

# 1. Purpose

This document defines how transfer chunks and transfer-related data are stored.

The storage design must support:

- Independently readable chunks
- Progressive download
- Resumable upload/download
- Chunk integrity
- Temporary transfers
- Safe deletion
- Local development
- Cloud object storage deployment
- Multiple independent receivers

The storage layer must not determine transfer business rules.

---

# 2. Storage Responsibilities

Storage has two distinct parts:

```text
PostgreSQL
    |
    +--> Transfer metadata
    +--> Chunk metadata
    +--> State
    +--> Checksums
    +--> Timestamps

Object Storage
    |
    +--> Actual chunk bytes
```

PostgreSQL is the metadata source of truth.

Object storage is the byte-storage source of truth.

---

# 3. Storage Abstraction

Application/domain code must not depend directly on Cloudflare R2 or the local filesystem.

Conceptually:

```text
ChunkStorage

    +--> putChunk()
    +--> getChunk()
    +--> exists()
    +--> deleteChunk()
    +--> deleteTransfer()
```

The implementation may use:

```text
ChunkStorage
     |
     +--> LocalChunkStorage
     |
     +--> R2ChunkStorage
```

The application layer interacts with the abstraction.

---

# 4. One Object Per Chunk

Each chunk is stored as an independent object.

Example:

```text
transfers/{transferId}/chunks/000000
transfers/{transferId}/chunks/000001
transfers/{transferId}/chunks/000002
```

This is required for progressive download.

A receiver can read chunk `000002` without requiring the complete file to exist.

---

# 5. Storage Key

Canonical storage-key format:

```text
transfers/{transferId}/chunks/{chunkIndex}
```

Example:

```text
transfers/7f8a.../chunks/000000
transfers/7f8a.../chunks/000001
transfers/7f8a.../chunks/000002
```

The storage key must not contain the original filename.

Chunk indexes should use a deterministic representation.

The storage key is an internal identifier and must not be exposed as a public authorization credential.

---

# 6. PostgreSQL Metadata

Conceptual transfer metadata:

```text
Transfer
---------
id
share_code
file_name
file_size
content_type
chunk_size
total_chunks
status
created_at
expires_at
```

Conceptual chunk metadata:

```text
TransferChunk
-------------
transfer_id
chunk_index
size
checksum
storage_key
uploaded_at
```

The exact SQL schema and indexes belong to the implementation.

---

# 7. Metadata/Blob Consistency

A chunk is AVAILABLE only when both conditions are true:

```text
chunk bytes persisted
        +
chunk metadata successfully committed
        =
AVAILABLE
```

The system must not report a chunk as available when the corresponding bytes are unavailable.

Conversely, temporary orphaned storage objects must not be treated as available merely because an object exists.

The application must reconcile metadata and storage where necessary.

---

# 8. Upload Storage Flow

The conceptual sequence is:

```text
Receive chunk
     |
     v
Validate transfer/chunk
     |
     v
Verify checksum
     |
     v
Persist chunk bytes
     |
     v
Persist/update chunk metadata
     |
     v
Return success
```

A successful API response must only be returned after the storage operation has succeeded.

A storage failure must never be reported as successful upload.

---

# 9. Idempotent Storage

Repeated writes for the same logical chunk must be deterministic.

Example:

```text
PUT chunk 42
    |
    v
stored successfully

PUT chunk 42 again
    |
    +--> same checksum -> idempotent success
    |
    +--> different checksum -> conflict
```

The application/protocol layer owns the conflict decision.

Storage must provide enough information to support that decision.

The system must never silently replace a valid chunk with conflicting data.

---

# 10. Chunk Retrieval

A chunk read follows:

```text
GET chunk
   |
   v
Transfer authorization
   |
   v
Check metadata availability
   |
   v
Read object
   |
   v
Stream bytes to receiver
```

The API must not expose raw storage-provider credentials or storage administration operations.

---

# 11. Missing Chunk

If metadata says the chunk is unavailable, the storage layer must not fabricate or return data.

Conceptually:

```text
chunk unavailable
       |
       v
CHUNK_NOT_AVAILABLE
```

The receiver may retry after new availability is detected.

---

# 12. Storage Failure

Storage failures must be distinguished from normal missing chunks.

Examples:

```text
CHUNK_NOT_AVAILABLE
STORAGE_FAILURE
```

A missing chunk means the chunk has not been made available.

A storage failure means the system could not reliably perform the requested storage operation.

These must not be treated as equivalent.

---

# 13. Download Integrity

The stored checksum belongs to chunk metadata.

Conceptually:

```text
stored bytes
     |
     v
stored checksum
```

The receiver can use the checksum supplied by the API to verify the downloaded chunk.

A failed integrity check must cause the chunk to remain incomplete locally and be retried.

A partially written chunk must never be marked as successfully downloaded.

---

# 14. Local Development Storage

Development should support local filesystem storage.

Example:

```text
files/
  transfers/
    {transferId}/
      chunks/
        000000
        000001
        000002
```

The local implementation must follow the same `ChunkStorage` contract as object storage.

The application should therefore behave the same way regardless of storage provider.

---

# 15. Object Storage Deployment

Cloudflare R2 is the primary deployment candidate from `TECHNICAL.md`.

The implementation should use an adapter rather than allowing R2-specific logic to leak into transfer logic.

Conceptually:

```text
Application
     |
     v
ChunkStorage
     |
     v
R2ChunkStorage
     |
     v
Cloudflare R2
```

The provider remains replaceable.

---

# 16. Storage Isolation

A transfer must only access objects belonging to itself.

Conceptually:

```text
Transfer A
    |
    +--> transfers/A/...

Transfer B
    |
    +--> transfers/B/...
```

The API/application authorization layer must validate the transfer capability before allowing access.

A caller must never be able to enumerate or retrieve another transfer's chunks by modifying a storage key.

---

# 17. Expiration

Every transfer has an expiration time.

When a transfer expires:

```text
Transfer expired
      |
      v
Reject new access
      |
      v
Cleanup
```

Cleanup removes:

1. Chunk objects
2. Chunk metadata
3. Transfer metadata

Deletion should be idempotent.

Running cleanup more than once must not corrupt the system.

---

# 18. Cleanup Ordering

Cleanup should tolerate partial failure.

Example:

```text
Delete chunk objects
      |
      v
Delete chunk metadata
      |
      v
Delete transfer metadata
```

If cleanup fails partway through, a later cleanup run must be able to continue.

The system must not assume that one cleanup invocation always succeeds completely.

---

# 19. Orphaned Objects

The architecture must tolerate temporary orphaned objects.

Example:

```text
Object exists
    |
    X
Metadata commit fails
```

The object must not become downloadable merely because it exists.

A cleanup/reconciliation mechanism may later remove orphaned objects.

Orphan cleanup must be designed to avoid deleting valid active chunks.

---

# 20. Storage Consistency Model

The application should treat PostgreSQL metadata as the authoritative availability record.

Therefore:

```text
metadata says AVAILABLE
        +
object exists
        =
downloadable chunk
```

If metadata says unavailable:

```text
object exists
        +
metadata says unavailable
        =
not downloadable
```

The object itself is not sufficient authorization or availability evidence.

---

# 21. Chunk Size

Storage does not determine the chunk size.

The transfer configuration provides:

```text
chunkSize
```

The storage layer must accept the configured chunk size without embedding a fixed size into its implementation.

The final chunk may be smaller than the configured chunk size.

---

# 22. Storage Metadata

Useful object metadata may include:

```text
Content-Length
Content-Type
```

The application metadata remains authoritative for logical transfer state and checksum.

Provider-specific metadata should not become a required part of the domain model unless a real requirement appears.

---

# 23. Concurrency

Storage implementations must safely handle bounded concurrent requests.

The system must not assume that chunks are uploaded strictly sequentially.

However, application-level concurrency remains bounded.

The storage layer must preserve the invariant:

```text
one transfer + one chunk index
        |
        v
one logical chunk
```

---

# 24. No Database Blob Storage

Actual chunk bytes must not be stored in PostgreSQL.

PostgreSQL stores:

```text
metadata
state
checksum
timestamps
```

Object storage stores:

```text
chunk bytes
```

This separation is mandatory for the large-file architecture.

---

# 25. Storage Interface Boundary

The storage abstraction should expose operations required by the application, not provider-specific functionality.

Conceptually:

```text
putChunk(transferId, chunkIndex, bytes, metadata)

getChunk(transferId, chunkIndex)

exists(transferId, chunkIndex)

deleteChunk(transferId, chunkIndex)

deleteTransfer(transferId)
```

The exact method signatures are implementation details.

Do not expose R2/S3-specific types through the domain layer.

---

# 26. Storage Testing

Every storage implementation should be tested against the same behavioral contract.

Minimum tests:

- Store chunk
- Retrieve chunk
- Check existence
- Delete chunk
- Delete transfer
- Missing chunk
- Repeated identical write
- Conflicting write
- Storage failure
- Large chunk handling
- Concurrent bounded operations

Local storage should be used heavily during development.

Provider integration tests should verify the R2 adapter before deployment.

---

# 27. Storage Performance

Storage performance must be measured rather than assumed.

Important measurements:

```text
upload throughput
download throughput
request latency
chunk write latency
chunk read latency
failure/retry behavior
```

Do not optimize around theoretical provider limits before measuring the actual transfer path.

---

# 28. Cost Considerations

Storage cost is especially important because every chunk is an independently stored object.

The system should:

- Delete expired transfers
- Avoid duplicate chunks
- Avoid unnecessary rewrites
- Keep chunk count reasonable
- Avoid retaining failed/orphaned objects indefinitely

A 20 GB test transfer is a benchmark target, not a guarantee of zero-cost production storage.

---

# 29. Security Requirements

Storage must not expose:

- Provider access keys
- Provider secret keys
- Internal bucket credentials
- Direct unrestricted bucket administration

Public access should occur through the application's transfer authorization mechanism.

Storage keys must not be treated as bearer credentials.

---

# 30. Local vs Production

The intended relationship is:

```text
Development

React
  |
Spring Boot
  |
Local PostgreSQL
  |
LocalChunkStorage


Production

React
  |
Spring Boot
  |
PostgreSQL
  |
R2ChunkStorage
  |
Cloudflare R2
```

The application/domain behavior should remain independent of the selected storage backend.

---

# 31. Failure Scenarios

The storage architecture must tolerate:

### Upload interrupted

Persisted chunks remain available.

### Storage write fails

Chunk is not reported as available.

### Metadata write fails after object write

Object may temporarily become orphaned and must not become downloadable without valid metadata.

### Download fails

The receiver does not mark the chunk as complete.

### Cleanup fails

A later cleanup run can retry deletion.

### Duplicate upload

Identical content is handled idempotently.

Conflicting content is rejected according to the transfer protocol.

---

# 32. Critical Storage Invariants

**Invariant 1**

A chunk is not available without valid persisted bytes.

**Invariant 2**

Metadata availability is authoritative.

**Invariant 3**

A valid stored chunk must not be silently replaced by conflicting content.

**Invariant 4**

Expired transfer data must eventually be deleted.

**Invariant 5**

Cleanup must be safe to retry.

**Invariant 6**

Storage-provider credentials never reach the browser.

**Invariant 7**

PostgreSQL does not store multi-GB file/chunk blobs.

**Invariant 8**

One logical chunk index corresponds to one logical stored chunk.

**Invariant 9**

A failed download must not be recorded as completed locally.

**Invariant 10**

The storage implementation must remain replaceable behind the storage abstraction.

---

# 33. Explicit Non-Goals

The storage layer does not implement:

- Transfer authorization policy
- Transfer state-machine decisions
- Retry scheduling
- Upload/download UI
- Receiver polling
- Share-code generation
- Authentication
- Compression
- Encryption policy beyond provider/application requirements
- CDN behavior

Those responsibilities belong elsewhere.

---

# 34. Final Storage Definition

The MVP uses PostgreSQL for transfer/chunk metadata and independently stored objects for actual chunk bytes.

Each chunk has a deterministic storage key based on transfer ID and chunk index.

A chunk becomes logically available only when its bytes have been safely persisted and its metadata has been committed as available.

The application accesses storage through a provider-independent `ChunkStorage` abstraction.

Local filesystem storage is used for development, while Cloudflare R2 is the primary production candidate.

Expiration and cleanup remove temporary transfer data.

The storage design exists specifically to support progressive, resumable, integrity-verified large-file transfer without requiring the complete file to exist as a single object.
