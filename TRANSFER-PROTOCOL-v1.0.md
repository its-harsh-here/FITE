# Large-File Transfer — Transfer Protocol Specification

**Status:** ACTIVE — Protocol Baseline  
**Document:** `TRANSFER-PROTOCOL.md`  
**Version:** 1.0  
**Depends on:** `MVP.md`, `TECHNICAL.md`, `ARCHITECTURE.md`

---

# 1. Purpose

This document defines the application-level protocol used to transfer large files as independently addressable chunks.

It defines:

- Transfer creation
- Chunk addressing
- Chunk upload
- Chunk availability
- Chunk download
- Pause/resume behavior
- Connection-loss recovery
- Retry behavior
- Idempotency
- Chunk conflicts
- Integrity verification
- Progressive transfer
- Completion
- Expiration
- Receiver behavior
- Protocol invariants

This document defines **behavior**, not HTTP endpoint naming. Exact HTTP request/response contracts belong in `API.md`.

---

# 2. Protocol Model

A file is represented as an ordered sequence of chunks.

```text
File
 |
 +-- Chunk 0
 +-- Chunk 1
 +-- Chunk 2
 +-- ...
 +-- Chunk N
```

Each chunk is independently addressed by:

```text
transferId + chunkIndex
```

The backend maintains authoritative state for which chunks are available.

The protocol therefore operates on **application-level chunk state**, while HTTP/TCP handle network transport.

---

# 3. Core Protocol Principles

The protocol follows these principles:

1. A chunk is independently transferable.
2. A chunk becomes available only after successful persistence and integrity verification.
3. Upload and download progress are based on actual chunk state, not UI assumptions.
4. Chunk uploads are retryable.
5. Retrying an already-valid identical chunk must be safe.
6. Conflicting content for an already-valid chunk must not silently overwrite it.
7. Connection loss must not invalidate completed work.
8. Sender pause affects future uploads, not already-available chunks.
9. Receiver pause affects future downloads, not already-downloaded chunks.
10. Receivers may download available chunks before the transfer is complete.
11. Notifications are hints; authoritative state is obtained from the backend.
12. A failed or partial operation must never be recorded as successful.

---

# 4. Terminology

| Term | Meaning |
|---|---|
| Transfer | Logical file being shared |
| Chunk | Fixed-size portion of the file |
| Chunk index | Zero-based logical position of a chunk |
| Available | Chunk is persisted, verified, and downloadable |
| Missing | Chunk has not been successfully stored |
| Sender | Browser uploading the file |
| Receiver | Browser downloading the file |
| Share capability | Public credential used to access a transfer |
| Reconciliation | Comparing client state with authoritative server state |
| Caught up | Receiver has downloaded all currently available chunks |
| Complete | All expected chunks are available and transfer completion has been confirmed |

---

# 5. Transfer Creation

The sender begins by creating a transfer.

Conceptually:

```text
Sender
   |
   | create transfer
   v
Backend
   |
   | transfer metadata + share capability
   v
Sender
```

The transfer contains at minimum:

```text
transferId
shareCapability
fileName
contentType
fileSize
chunkSize
totalChunks
status
expiresAt
```

The transfer exists before all file data exists.

Initial state:

```text
CREATED
```

After upload begins:

```text
UPLOADING
```

---

# 6. Chunk Calculation

Given:

```text
fileSize
chunkSize
```

the total number of chunks is:

```text
totalChunks = ceil(fileSize / chunkSize)
```

Chunk indexes are:

```text
0 ... totalChunks - 1
```

For a chunk index `i`:

```text
start = i * chunkSize
end   = min(start + chunkSize, fileSize)
```

The final chunk may be smaller than the configured chunk size.

The client must never create a chunk whose byte range exceeds the original file.

---

# 7. Chunk Identity

A chunk is uniquely identified within a transfer by:

```text
transferId + chunkIndex
```

The chunk index is the authoritative logical position.

The storage key may be derived from it, for example:

```text
transfers/{transferId}/chunks/{chunkIndex}
```

The original filename must not be required for chunk identity.

---

# 8. Upload Protocol

The normal upload sequence is:

```text
1. Create transfer
2. Receive transfer metadata
3. Determine chunk boundaries
4. Read one chunk
5. Calculate checksum
6. Send chunk
7. Server validates request
8. Server verifies checksum
9. Server persists chunk
10. Server records chunk metadata
11. Server marks chunk AVAILABLE
12. Client records successful progress
13. Release chunk memory
14. Continue
```

The client should process chunks incrementally.

The complete file must not be loaded into memory.

---

# 9. Upload Ordering

The protocol does not require chunks to be uploaded strictly in order.

For example:

```text
0 1 2 3 4 5
```

may be uploaded as:

```text
0 1 2 4 3 5
```

provided the backend accepts valid chunk indexes independently.

The initial implementation may use sequential upload because correctness is more important than parallelism.

If bounded concurrency is later introduced, the same protocol semantics must remain valid.

---

# 10. Server-Side Chunk Acceptance

A received chunk follows:

```text
RECEIVED
   |
   v
VALIDATE
   |
   v
VERIFY CHECKSUM
   |
   v
PERSIST
   |
   v
RECORD METADATA
   |
   v
AVAILABLE
```

A chunk must **not** become `AVAILABLE` before its bytes are safely persisted and its integrity has been verified.

If any required step fails:

```text
RECEIVED
   |
   v
FAILURE
   |
   v
NOT AVAILABLE
```

The client may retry.

---

# 11. Chunk Upload Idempotency

Chunk uploads must be retry-safe.

Example:

```text
PUT chunk 42

        |
        v
server persists chunk

        |
        v
connection fails before response

        |
        v
client retries chunk 42
```

The backend checks the existing state.

If chunk 42 already exists and the incoming content is identical:

```text
ACCEPT AS ALREADY AVAILABLE
```

No second logical chunk is created.

The operation is therefore idempotent from the transfer's perspective.

---

# 12. Chunk Conflict

If a chunk index already contains valid data and a new request supplies different content:

```text
Existing:
chunk 42 -> checksum A

Incoming:
chunk 42 -> checksum B
```

The server must not silently replace the existing valid chunk.

The protocol treats this as:

```text
CHUNK_CONFLICT
```

The client must reconcile its local state instead of assuming that its version should overwrite the server's version.

---

# 13. Integrity Verification

Each chunk has an integrity value.

Conceptually:

```text
chunk bytes
     |
     v
SHA-256
     |
     v
checksum
```

The sender provides the expected checksum.

The server calculates the checksum of received bytes and compares the values.

```text
MATCH
  |
  v
continue persistence
```

or:

```text
MISMATCH
  |
  v
reject chunk
```

A checksum mismatch must never result in an `AVAILABLE` chunk.

---

# 14. Upload Progress

Upload progress should be derived from authoritative chunk state where possible.

Conceptually:

```text
uploadedBytes =
sum(size of AVAILABLE chunks)
```

The UI may display:

```text
72%
```

but that percentage is only a presentation of actual transfer state.

A client must not permanently assume success merely because an HTTP request was sent.

---

# 15. Upload Resume

After connection loss, browser restart, or another interruption:

```text
Reconnect
   |
   v
Query transfer state
   |
   v
Receive available chunks
   |
   v
Compare with local progress
   |
   v
Find missing chunks
   |
   v
Resume upload
```

The client must reconcile against the backend.

It must not blindly restart from chunk zero.

---

# 16. Upload Pause

Sender pause is a local execution state.

When paused:

```text
PAUSED
```

the sender:

- Stops starting new chunk uploads.
- Allows already-running requests to finish or be safely aborted according to implementation.
- Preserves successfully uploaded chunks.
- Does not delete transfer data.
- Does not invalidate the transfer.

Existing chunks remain available to receivers.

---

# 17. Upload Resume After Pause

When the sender resumes:

```text
RESUME
   |
   v
RECONCILE
   |
   v
FIND MISSING CHUNKS
   |
   v
CONTINUE UPLOAD
```

The sender must not rely exclusively on the last locally displayed progress value.

---

# 18. Receiver Discovery

A receiver obtains transfer information using the share capability.

Conceptually:

```text
share capability
       |
       v
backend
       |
       v
transfer metadata
```

The receiver then requests current chunk availability.

Example:

```text
totalChunks = 100

available:
0 ... 37
```

The receiver may download chunks `0–37`.

Chunks `38–99` are not yet available.

---

# 19. Chunk Availability

A chunk is downloadable only when:

```text
chunk status == AVAILABLE
```

The receiver must never infer availability solely from:

- Sender progress UI
- Previous polling results
- Local assumptions
- Notification delivery
- Chunk request timing

The backend remains authoritative.

---

# 20. Progressive Download

The receiver does not wait for:

```text
transfer.status == COMPLETE
```

before downloading.

Instead:

```text
Upload:

0 1 2 3 4 5 6 ...

Availability:

0 1 2 3 4 5

Receiver:

0 1 2 3 4 5

Caught up
   |
   v
WAIT FOR MORE
```

When a new chunk becomes available:

```text
chunk 6 -> AVAILABLE
```

the receiver continues.

This is the core progressive-transfer behavior.

---

# 21. Receiver Caught-Up State

If:

```text
downloadedChunks == currentlyAvailableChunks
```

but:

```text
downloadedChunks < totalChunks
```

the receiver is:

```text
WAITING_FOR_DATA
```

It must not treat this as transfer completion.

Conceptually:

```text
if next required chunk is available:
    download it
else if transfer is COMPLETE:
    finish
else:
    wait
```

---

# 22. Availability Discovery

The receiver may discover newly available chunks using polling or another notification mechanism.

The initial implementation should prefer the simplest reliable mechanism.

Regardless of mechanism:

```text
notification != source of truth
```

A notification means:

```text
"State may have changed."
```

The receiver must then query authoritative state.

---

# 23. Polling Rules

If polling is used:

```text
receiver
   |
   | availability request
   v
backend
   |
   | available chunks
   v
receiver
```

Polling should:

- Stop or substantially reduce while the receiver is paused.
- Stop after transfer completion.
- Stop after expiration/error requiring user action.
- Use a bounded interval.
- Avoid aggressive polling loops.

When the receiver resumes, it reconciles current server state before continuing.

---

# 24. Receiver Download

For an available chunk:

```text
1. Request chunk
2. Receive bytes
3. Verify checksum
4. Persist chunk locally
5. Mark local chunk as downloaded
6. Update progress
7. Continue
```

The client must not mark a chunk as downloaded before the bytes have been successfully persisted.

---

# 25. Download Integrity

The receiver verifies downloaded chunk data against the server-provided checksum.

```text
download bytes
      |
      v
calculate checksum
      |
      v
compare
   /     \
MATCH   MISMATCH
  |         |
  v         v
persist    retry
```

A mismatched chunk must not be treated as successfully downloaded.

---

# 26. Partial Download Failure

If a chunk download fails:

```text
chunk 42
   |
   v
download interrupted
```

the client must treat chunk 42 as incomplete.

Any partial local data must not be mistaken for a valid completed chunk.

The client may retry the chunk.

Only after complete successful persistence may the local state become:

```text
chunk 42 -> DOWNLOADED
```

---

# 27. Receiver Pause

When the receiver is paused:

```text
PAUSED
```

it:

- Stops starting new chunk downloads.
- Preserves successfully downloaded chunks.
- Does not reset progress.
- Does not invalidate the transfer.
- May stop polling for new availability.

The sender may continue uploading while the receiver is paused.

---

# 28. Receiver Resume

When resumed:

```text
RESUME
   |
   v
READ LOCAL DOWNLOAD STATE
   |
   v
QUERY SERVER AVAILABILITY
   |
   v
FIND NEXT REQUIRED CHUNK
   |
   v
CONTINUE
```

The receiver must reconcile local progress with current server availability.

---

# 29. Receiver Connection Recovery

If the receiver loses connectivity:

```text
DOWNLOAD
   |
   v
CONNECTION LOST
   |
   v
PRESERVE VALID LOCAL CHUNKS
   |
   v
RECONNECT
   |
   v
RECONCILE
   |
   v
CONTINUE
```

Completed local chunks must not be discarded.

The receiver should resume from the earliest required missing chunk or otherwise continue with the set of missing chunks.

---

# 30. Receiver Local Persistence

Receiver progress must be represented by durable browser-side state appropriate for the implementation.

At minimum, the logical state is equivalent to:

```text
transferId
totalChunks
chunkSize
downloadedChunks
```

The storage mechanism is an implementation concern, but it must satisfy:

- A completed chunk survives temporary network failure.
- A completed chunk is not confused with partial data.
- The implementation avoids requiring the complete file in RAM.
- Resume can determine which chunks are locally complete.

The exact browser persistence mechanism is defined during implementation based on the validated browser storage approach.

---

# 31. Download Assembly

The receiver eventually produces the original ordered file:

```text
Chunk 0
Chunk 1
Chunk 2
...
Chunk N
   |
   v
Final file
```

Chunks must be assembled in logical index order.

The implementation must avoid unnecessarily constructing the entire multi-GB file in browser memory.

Where supported and appropriate, writing incrementally to persistent browser storage is preferred over holding all chunks in RAM.

---

# 32. Multiple Receivers

Each receiver is independent.

Example:

```text
Transfer
 |
 +-- Receiver A -> chunk 20
 |
 +-- Receiver B -> chunk 64
 |
 +-- Receiver C -> complete
```

The server's authoritative shared state is:

```text
which chunks are available
```

Receiver-specific progress may remain client-local for the MVP.

This means the MVP assumes receiver resume is tied to the browser/device where its local state exists.

Switching to another browser or device is not guaranteed to resume previous local progress.

---

# 33. Sender and Receiver Independence

Sender pause and receiver pause are independent.

Valid state:

```text
Sender:   PAUSED
Receiver: DOWNLOADING
```

Another valid state:

```text
Sender:   UPLOADING
Receiver: PAUSED
```

The protocol does not require the sender and receiver to move in lockstep.

---

# 34. Transfer Completion

The transfer becomes complete only when all expected chunks are available.

Conceptually:

```text
availableChunks == totalChunks
        |
        v
COMPLETE
```

The backend may expose completion only after all chunk metadata/storage state has been successfully verified.

A client must not declare the transfer complete based solely on its own local counters.

---

# 35. Completion Race

The sender and receiver may observe state at different times.

Example:

```text
Sender uploads final chunk
        |
        v
Backend marks final chunk AVAILABLE
        |
        v
Receiver queries availability
```

The receiver may therefore observe:

```text
last chunk available
```

before or at the same time as the transfer-level `COMPLETE` state.

The authoritative condition for successful completion is that all expected chunks are available and valid.

---

# 36. Transfer Expiration

Every transfer has an expiration time.

```text
createdAt
expiresAt
```

After expiration:

```text
access request
      |
      v
TRANSFER_EXPIRED
```

The transfer must no longer be usable for normal upload/download operations.

Cleanup removes:

1. Chunk objects
2. Chunk metadata
3. Transfer metadata

Cleanup must be safe to retry.

---

# 37. Expiration During Activity

Expiration is authoritative even if a client is still active.

Example:

```text
Receiver downloading
        |
        v
Transfer expires
        |
        v
Further requests rejected
```

The client must handle expiration as a terminal transfer condition.

---

# 38. Retry Policy

Retries are application-level recovery.

A retry may occur for:

- Network failure
- Timeout
- Temporary storage failure
- Checksum mismatch
- Transient server error

A retry must target the affected chunk rather than unnecessarily restarting the entire file.

Retries must be bounded.

The exact retry count and backoff are implementation parameters.

---

# 39. Retry and Idempotency

The client may safely retry a chunk request after an uncertain result.

For upload:

```text
unknown result
   |
   v
reconcile server
   |
   +--> already AVAILABLE -> skip
   |
   +--> missing -> retry
   |
   +--> conflicting -> handle conflict
```

For download:

```text
unknown result
   |
   v
check local completed state
   |
   +--> complete -> skip
   |
   +--> incomplete -> retry
```

---

# 40. Out-of-Order Availability

The protocol allows chunks to become available out of order.

Example:

```text
0 -> AVAILABLE
1 -> AVAILABLE
2 -> AVAILABLE
3 -> MISSING
4 -> AVAILABLE
```

The receiver may download chunk 4 if its implementation supports out-of-order local persistence.

It must not interpret the missing chunk 3 as proof that chunk 4 is invalid.

The initial implementation may choose ordered downloading for simplicity.

---

# 41. Recommended Initial Transfer Strategy

For the first correct implementation:

```text
Upload:
sequential or tightly bounded

Download:
sequential or tightly bounded

Availability:
simple polling

Storage:
one object per chunk

Integrity:
SHA-256

Resume:
server reconciliation

Receiver progress:
local persistence
```

Optimization should occur only after correctness has been established.

---

# 42. Error Categories

The protocol recognizes at least:

```text
TRANSFER_NOT_FOUND
TRANSFER_EXPIRED
INVALID_CHUNK_INDEX
CHUNK_NOT_AVAILABLE
CHECKSUM_MISMATCH
CHUNK_CONFLICT
INVALID_TRANSFER_STATE
STORAGE_FAILURE
```

Errors must be distinguishable enough for the client to choose between:

```text
retry
wait
reconcile
abort
```

---

# 43. Recovery Decision Rules

Conceptually:

```text
Network failure
    -> retry/reconnect

Checksum mismatch
    -> retry affected chunk

Chunk unavailable
    -> wait/recheck availability

Transfer expired
    -> stop

Transfer not found
    -> stop

Chunk conflict
    -> reconcile

Storage failure
    -> retry if transient, otherwise report failure
```

The frontend must not hide terminal protocol errors behind an indefinite retry loop.

---

# 44. Authorization

The no-account MVP uses transfer-based access.

A valid share capability is required to access the transfer.

The share capability must be sufficiently unpredictable to prevent practical guessing.

Public identifiers must not be sequential database IDs.

Rate limiting and brute-force protection are security concerns defined in `SECURITY.md`.

---

# 45. Protocol State Model

Transfer-level state:

```text
CREATED
   |
   v
UPLOADING
   |
   v
COMPLETE
   |
   v
EXPIRED
```

Failure/cleanup paths may transition to:

```text
FAILED
DELETED
```

Sender pause is not a transfer-level state.

Receiver pause is not a transfer-level state.

They are client execution states.

---

# 46. State Invariants

The following must always hold.

**Invariant 1**

A chunk cannot be AVAILABLE unless its bytes are successfully persisted and integrity-verified.

**Invariant 2**

A receiver cannot successfully download a chunk that is not AVAILABLE.

**Invariant 3**

Retrying an identical valid chunk upload cannot create duplicate logical chunks.

**Invariant 4**

A valid stored chunk must not be silently overwritten by conflicting content.

**Invariant 5**

Pause does not delete completed progress.

**Invariant 6**

Connection loss does not reset completed progress.

**Invariant 7**

Client progress must be reconciled with authoritative server state after uncertain operations.

**Invariant 8**

A partial download must never be marked as a completed local chunk.

**Invariant 9**

The receiver may download available chunks before transfer completion.

**Invariant 10**

Notifications are never the authoritative source of transfer state.

**Invariant 11**

Expired transfers cannot be accessed through normal transfer operations.

**Invariant 12**

The complete file does not need to exist before progressive downloading begins.

**Invariant 13**

Storage failure cannot be reported as successful chunk persistence.

**Invariant 14**

Chunk indexes have stable logical positions within a transfer.

---

# 47. Complete End-to-End Flow

The intended complete protocol is:

```text
                    CREATE
                       |
                       v
                  TRANSFER
                    CREATED
                       |
                       v
                   UPLOADING
                       |
          +------------+------------+
          |                         |
          v                         v
     UPLOAD CHUNK              RECEIVER JOINS
          |                         |
          v                         v
       VERIFY                  GET AVAILABILITY
          |                         |
          v                         v
      PERSIST                  DOWNLOAD CHUNK
          |                         |
          v                         v
      AVAILABLE               VERIFY + PERSIST
          |                         |
          +------------+------------+
                       |
                       v
              MORE CHUNKS EXIST?
                  /         \
                YES          NO
                 |            |
                 v            v
              CONTINUE     COMPLETE
                               |
                               v
                           EXPIRE
                               |
                               v
                            DELETE
```

At any point before completion:

```text
Sender PAUSE
Sender RESUME
Sender CONNECTION LOSS
Receiver PAUSE
Receiver RESUME
Receiver CONNECTION LOSS
```

must preserve successfully completed work.

---

# 48. Protocol and HTTP Boundary

This protocol defines **what must happen**.

`API.md` will define **how it is represented over HTTP**.

For example, this document defines:

```text
Upload chunk 42
Verify it
Persist it
Mark it AVAILABLE
```

`API.md` will define the corresponding:

```text
HTTP method
URL
headers
request body
response
status codes
error format
```

The two documents must remain consistent.

---

# 49. Protocol and Storage Boundary

This protocol does not expose storage-provider details.

The protocol requires:

```text
chunk can be persisted
chunk can be retrieved independently
chunk availability can be determined
chunk can be deleted
```

Whether those operations are implemented using:

```text
R2
Local filesystem
Another object store
```

is defined by `STORAGE.md`.

---

# 50. Protocol and Client Architecture Boundary

The protocol does not dictate React component structure.

The frontend architecture maps protocol operations into:

```text
UploadManager
DownloadManager
API Client
Local Persistence
Transfer State
```

The UI consumes those abstractions.

React components should not implement chunk retry/reconciliation logic directly.

---

# 51. Protocol Versioning

This document is version `1.0`.

Protocol changes that alter interoperability or transfer semantics should require an explicit protocol-version decision.

Internal implementation changes that preserve these semantics do not necessarily require a protocol version change.

---

# 52. Final Protocol Definition

> A transfer is an ordered set of independently addressable file chunks. The sender uploads chunks incrementally, and the backend verifies and persists each chunk before marking it available. Receivers query authoritative availability and may download available chunks before the complete file exists. Upload and download operations are independently pausable and resumable. Connection loss is recovered through reconciliation with persisted server state and durable receiver-local state. Chunk retries are idempotent, conflicting valid chunks are never silently overwritten, integrity is verified using checksums, and partial operations are never recorded as complete. Transfer expiration terminates access and triggers cleanup.

The protocol prioritizes correctness, recoverability, progressive transfer, and simple implementation over premature transport-level optimization.
