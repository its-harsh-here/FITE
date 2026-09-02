# Large-File Transfer — Testing Specification

**Status:** ACTIVE — Testing Baseline  
**Document:** `TESTING.md`  
**Version:** 1.0  
**Depends on:** `MVP.md`, `TECHNICAL.md`, `ARCHITECTURE.md`, `TRANSFER-PROTOCOL.md`, `API.md`, `STORAGE.md`

---

# 1. Purpose

This document defines how the MVP is verified.

Testing must prove the system's core engineering properties rather than only verify that the UI works.

The primary goals are:

- Correct chunked upload
- Correct chunked download
- Pause/resume
- Connection-loss recovery
- Progressive transfer
- Chunk integrity
- Idempotent retries
- Expiration and cleanup
- Multiple independent receivers
- Large-file handling
- Correct storage behavior

---

# 2. Testing Principle

The system must be tested from the perspective of its invariants.

A successful UI request is not sufficient evidence of correctness.

The important question is:

> Does the persisted transfer state remain correct when operations succeed, fail, retry, pause, resume, or occur concurrently?

---

# 3. Test Levels

The MVP uses four primary levels:

```text
Unit Tests
    |
    v
Integration Tests
    |
    v
End-to-End Tests
    |
    v
Large-File / Stress Tests
```

Each level has a different purpose.

---

# 4. Unit Tests

Unit tests verify isolated domain and application behavior.

Backend unit tests should cover at minimum:

- Transfer state transitions
- Chunk index validation
- Chunk count calculation
- Completion determination
- Expiration determination
- Share-token validation
- Checksum validation
- Duplicate chunk decisions
- Conflicting chunk decisions
- Error mapping
- Progress calculations

The domain layer should be testable without PostgreSQL or object storage.

---

# 5. Storage Unit Tests

The `ChunkStorage` abstraction must be tested independently.

Required operations:

```text
putChunk()
getChunk()
exists()
deleteChunk()
deleteTransfer()
```

Test cases:

- Store a chunk
- Retrieve a stored chunk
- Detect missing chunk
- Delete a chunk
- Delete all chunks for a transfer
- Repeated deletion is safe
- Storage failure is propagated correctly
- Stored bytes remain unchanged after a failed operation

A local filesystem implementation should be sufficient for most fast tests.

---

# 6. Database Integration Tests

Integration tests must verify PostgreSQL behavior.

Important cases:

- Transfer creation
- Chunk metadata persistence
- Unique `(transfer_id, chunk_index)` constraint
- Transaction rollback
- Concurrent chunk writes
- Transfer expiration state
- Cleanup metadata deletion
- Foreign-key behavior
- Retrieval of available chunks

The database constraint enforcing one logical chunk per transfer/index is mandatory.

---

# 7. Upload Tests

The basic upload test:

```text
Create transfer
    |
    v
Upload chunk 0
    |
    v
Upload chunk 1
    |
    v
...
    |
    v
Upload final chunk
    |
    v
Complete transfer
```

Verify:

- Correct number of chunks
- Correct chunk sizes
- Correct checksums
- Correct storage keys
- Correct metadata
- Correct transfer state
- No missing chunks

---

# 8. Upload Idempotency Tests

Retrying the same valid chunk must not create duplicate logical state.

Test:

```text
PUT chunk 42
PUT chunk 42
```

Expected:

- No duplicate metadata
- No corrupted storage
- Deterministic response
- Transfer remains valid

For an identical retry, the result must follow the protocol-defined idempotency response.

---

# 9. Conflicting Upload Tests

Test:

```text
PUT chunk 42 -> content A
PUT chunk 42 -> content B
```

Expected:

- Existing valid chunk is not silently replaced
- Conflict is reported according to the protocol
- Original chunk remains authoritative

---

# 10. Concurrent Upload Tests

Two requests for the same chunk must be tested concurrently.

Example:

```text
Request A -> chunk 42
Request B -> chunk 42
```

Verify:

- Only one logical chunk exists
- Database uniqueness is enforced
- One request may succeed idempotently
- A conflicting request cannot overwrite valid data
- No unhandled database race condition escapes the API

This test validates the database-level uniqueness decision.

---

# 11. Checksum Tests

For upload:

```text
Client checksum
       |
       v
Backend recalculates
       |
       v
Compare
```

Test:

- Correct checksum
- Incorrect checksum
- Missing checksum where prohibited
- Corrupted payload
- Retry after mismatch

Expected:

```text
MATCH    -> accept
MISMATCH -> reject
```

A failed checksum must never result in an `AVAILABLE` chunk.

---

# 12. Download Tests

Basic download:

```text
Create transfer
    |
    v
Upload chunks
    |
    v
Request available chunk
    |
    v
Download
```

Verify:

- Correct bytes
- Correct chunk index
- Correct content length
- Correct checksum metadata
- Unavailable chunks are rejected
- Completed transfers expose all chunks

---

# 13. Progressive Download Test

This is a core MVP test.

The sender must upload only part of a file.

Example:

```text
Total chunks: 10

Available:
0 1 2 3 4
```

The receiver starts before the sender finishes.

Expected:

```text
Receiver downloads 0–4
Receiver waits for 5
Sender uploads 5
Receiver continues
```

The receiver must not require the transfer to be `COMPLETE` before downloading available chunks.

---

# 14. Caught-Up Test

Test:

```text
Uploaded: 5 chunks
Downloaded: 5 chunks
Total: 10
```

Expected receiver state:

```text
WAITING_FOR_DATA
```

It must not report completion.

When another chunk becomes available, the receiver must continue automatically according to the notification/polling mechanism.

---

# 15. Sender Pause / Resume Test

Test:

```text
Upload chunks 0–4
PAUSE
Wait
RESUME
Upload 5+
```

Verify:

- Chunks 0–4 remain available
- No new chunks are uploaded while paused
- Resume does not restart from chunk 0
- Final file remains correct

---

# 16. Receiver Pause / Resume Test

Test:

```text
Download chunks 0–4
PAUSE
Wait
RESUME
Download 5+
```

Verify:

- Downloaded chunks remain preserved
- No new chunks are downloaded while paused
- Resume continues from the next missing chunk
- Sender can continue independently

---

# 17. Sender Connection-Loss Test

Interrupt the sender during upload.

Example:

```text
Uploaded:
0–40

Connection lost

Reconnect

Expected:
Continue from missing chunks
```

Verify:

- Previously persisted chunks remain available
- Client reconciles against server state
- No unnecessary restart from chunk 0
- Final reconstructed file is correct

---

# 18. Receiver Connection-Loss Test

Interrupt the receiver during download.

Example:

```text
Downloaded:
0–40

Connection lost

Reconnect

Expected:
Continue from next missing local chunk
```

Verify:

- Local progress is preserved
- Already downloaded chunks are not treated as missing
- Partial writes are not marked complete
- Final file is correct

---

# 19. Receiver Refresh / Browser Restart Test

The receiver should be tested after:

1. Page refresh
2. Tab close and reopen
3. Browser restart where practical

Verify:

- Transfer identity can be recovered
- Download progress can be recovered
- Already completed chunks are not redownloaded unnecessarily
- The receiver can continue after reconnecting

This test depends on the browser persistence mechanism selected by the implementation.

---

# 20. Partial Download Write Test

A chunk must not be marked locally complete if its bytes were only partially written.

Simulate:

```text
Chunk download begins
        |
        v
Write interrupted
        |
        v
Failure
```

Expected:

```text
Chunk remains incomplete
        |
        v
Retry
```

This prevents corrupted local assembly.

---

# 21. Progressive Transfer With Sender Pause

Combined test:

```text
Sender uploads 0–20
        |
        v
Sender pauses
        |
        v
Receiver downloads 0–20
        |
        v
Receiver catches up
        |
        v
Receiver waits
        |
        v
Sender resumes
        |
        v
Receiver continues
```

This validates that sender and receiver pause states are independent.

---

# 22. Multiple Receiver Test

One sender and multiple receivers:

```text
             Transfer
             /   |   \
            /    |    \
           A     B     C
```

Verify:

- All receivers can access the same available chunks
- Each receiver can progress independently
- Receiver A pausing does not pause B
- Receiver B disconnecting does not affect A
- Sender pause affects availability of future chunks, not existing ones

---

# 23. Expiration Tests

Create a transfer with a short test expiration.

Verify:

```text
Before expiry -> accessible
After expiry  -> inaccessible
```

Then verify cleanup:

- Chunk objects deleted
- Chunk metadata deleted
- Transfer metadata deleted
- Repeated cleanup is safe

Expired transfers must not become accessible again.

---

# 24. Storage Failure Tests

Simulate storage failures during:

- Chunk upload
- Chunk read
- Chunk deletion

Expected:

- API does not report false success
- Appropriate error category is returned
- Metadata does not falsely claim a chunk is available
- Failed cleanup can be retried safely

Also test the reverse inconsistency:

```text
Metadata says AVAILABLE
Storage object is missing
```

This must be detected as a storage failure, not as an ordinary unavailable chunk.

---

# 25. Metadata / Storage Consistency Tests

Important scenarios:

```text
Object exists
Metadata missing
```

and:

```text
Metadata AVAILABLE
Object missing
```

The system must detect and handle both cases according to `STORAGE.md`.

Orphan objects must not become publicly downloadable merely because they exist.

---

# 26. API Contract Tests

Every API endpoint defined in `API.md` should have tests for:

- Valid request
- Invalid request
- Missing transfer
- Expired transfer
- Invalid chunk index
- Missing chunk
- Checksum mismatch
- Chunk conflict
- Storage failure
- Unauthorized access
- Correct HTTP status
- Correct JSON error structure

The client must not depend solely on HTTP status codes where the API defines structured error codes.

---

# 27. Error Recovery Tests

Every recoverable operation should be tested with:

```text
Success
Failure
Retry
Success
```

Examples:

- Upload retry
- Download retry
- Availability request retry
- Reconnection
- Storage retry
- Cleanup retry

Retries must not create duplicate or corrupted state.

---

# 28. Large-File Tests

The system must eventually be tested with realistic large files.

Initial progression:

```text
100 MB
1 GB
5 GB
10 GB
20 GB
```

The exact final test size depends on available hardware and storage.

The 20 GB test is a validation target, not a promise of unlimited production storage.

---

# 29. Existing Large-File Validation

The prototype already validated the basic chunk reconstruction path.

Observed test:

```text
Chunks:
chunk-0.bin -> 8,388,608 bytes
chunk-1.bin -> 8,388,608 bytes
chunk-2.bin -> 5,798,871 bytes
```

Reconstructed file:

```text
22,576,087 bytes
```

SHA-256 of reconstructed file:

```text
20A2174C9D6C30364536E76A642BB151A459176C74EBACCDDFA529BD734CEAC5
```

The original downloaded PDF produced the same SHA-256:

```text
20A2174C9D6C30364536E76A642BB151A459176C74EBACCDDFA529BD734CEAC5
```

Therefore this test established that the tested chunk reconstruction path produced a byte-identical file.

---

# 30. Performance Testing

Performance testing should measure:

- Upload throughput
- Download throughput
- Chunk latency
- API latency
- Database latency
- Storage latency
- CPU usage
- Memory usage
- Browser memory usage
- Concurrent receiver behavior

Test different chunk sizes where practical:

```text
8 MB
16 MB
32 MB
```

Do not optimize based only on theoretical throughput.

---

# 31. Concurrency Testing

Test bounded concurrency:

```text
1 concurrent chunk
2 concurrent chunks
4 concurrent chunks
8 concurrent chunks
```

Measure:

- Throughput
- Error rate
- CPU
- Memory
- Database load
- Storage operations

Unbounded concurrency is not permitted.

---

# 32. Network Failure Testing

Simulate:

- High latency
- Packet loss where possible
- Connection interruption
- Temporary backend unavailability
- Slow upload
- Slow download
- Reconnection

The goal is not to reproduce TCP internals.

The goal is to prove application-level recovery.

---

# 33. Browser Testing

At minimum test the supported browser target on:

- Normal desktop operation
- Large file upload
- Large file download
- Pause/resume
- Refresh
- Temporary connection loss
- Long-running transfer

Browser support must be based on actual tested behavior, especially for local file persistence and multi-GB assembly.

---

# 34. End-to-End Test Matrix

Core E2E scenarios:

| Scenario | Expected |
|---|---|
| Upload complete file | File reconstructed correctly |
| Download complete file | File reconstructed correctly |
| Sender pause | Upload stops without losing progress |
| Sender resume | Upload continues |
| Receiver pause | Download stops without losing progress |
| Receiver resume | Download continues |
| Sender disconnect | Upload recovers |
| Receiver disconnect | Download recovers |
| Progressive transfer | Receiver downloads before completion |
| Receiver caught up | Waits for new chunks |
| Checksum mismatch | Chunk rejected |
| Duplicate upload | No duplicate logical state |
| Conflicting upload | Existing valid chunk protected |
| Expiration | Access rejected and data cleaned |
| Multiple receivers | Independent progress |
| Storage failure | No false success |

---

# 35. Test Data

Test data should include:

- Small text files
- Binary files
- PDFs
- Images
- Random binary data
- Files whose size is not divisible by chunk size
- Empty files where supported
- Files with very long names
- Files with unusual characters in names
- Large multi-GB files

The most important large-file test is one whose final chunk is smaller than the configured chunk size.

---

# 36. File Correctness Verification

Every end-to-end transfer must verify final bytes.

Preferred validation:

```text
Original file
     |
     v
SHA-256
     |
     v
Expected hash


Reconstructed file
     |
     v
SHA-256
     |
     v
Actual hash
```

Expected:

```text
Expected hash == Actual hash
```

File size equality alone is insufficient.

---

# 37. Test Environment

Local development should support:

```text
Frontend
Backend
PostgreSQL
Local ChunkStorage
```

The local storage implementation should allow the majority of tests to run without cloud infrastructure.

Cloud object storage should be tested separately before deployment.

---

# 38. CI Requirements

GitHub Actions should run automatically on pushes and pull requests.

Minimum CI:

```text
Backend compile
Backend unit tests
Backend integration tests
Frontend build
Frontend tests
```

Large multi-GB tests should not necessarily run on every commit if they make CI impractical.

They can be separated into scheduled or manually triggered validation.

---

# 39. Test Naming

Tests should describe behavior rather than implementation.

Prefer:

```text
shouldResumeUploadFromPersistedChunkState()
```

over:

```text
testUploadManagerMethod3()
```

Tests should make the expected invariant obvious.

---

# 40. Definition of Done

The MVP transfer implementation is considered technically validated when:

- Chunk upload works
- Chunk download works
- File reconstruction is byte-correct
- Checksums are verified
- Duplicate uploads are safe
- Conflicting uploads are rejected safely
- Concurrent same-index uploads are handled safely
- Sender pause/resume works
- Receiver pause/resume works
- Sender connection recovery works
- Receiver connection recovery works
- Progressive download works
- Receiver caught-up state works
- Multiple receivers work independently
- Expiration works
- Cleanup is idempotent
- Storage failures are handled correctly
- Large-file behavior is validated
- CI passes the required automated test suite

---

# 41. Testing Philosophy

The project should not claim reliability merely because:

```text
"the upload completed once."
```

Reliability is demonstrated by deliberately breaking the system and verifying that persisted state remains correct.

The most valuable tests are therefore:

```text
Upload
   +
Failure
   +
Recovery
   +
Integrity verification
```

The final proof is not the progress bar.

The final proof is:

> The reconstructed file is exactly the original file, even after interruption, retry, pause/resume, progressive consumption, and other expected failure scenarios.
