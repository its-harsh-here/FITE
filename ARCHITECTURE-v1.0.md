# Large-File Transfer — Architecture Specification

**Status:** ACTIVE — Architecture Baseline  
**Document:** `ARCHITECTURE.md`  
**Version:** 1.0  
**Depends on:** `MVP.md`, `TECHNICAL.md`

---

# 1. Purpose

This document defines how the MVP is structured internally.

The architecture must make these properties possible:

- Large-file transfer without loading the whole file into memory
- Chunked upload
- Chunked download
- Sender upload pause/resume
- Receiver download pause/resume
- Sender connection-loss recovery
- Receiver connection-loss recovery
- Progressive download while upload is still running
- Chunk integrity verification
- Temporary transfers
- Multiple independent receivers
- No user accounts
- Simple deployment
- Clear future extension points without premature complexity

The architecture is intentionally a **modular monolith**, not microservices.

---

# 2. Core Architecture Decision

The system uses a central transfer infrastructure:

```text
                 ┌─────────────────┐
                 │     Sender      │
                 │ React Browser   │
                 └────────┬────────┘
                          │
                       HTTPS / API
                          │
                          v
                 ┌─────────────────────┐
                 │     Spring Boot     │
                 │    Transfer API     │
                 └─────────┬───────────┘
                           │
              ┌────────────┼────────────┐
              │            │            │
              v            v            v
         PostgreSQL   Object Storage   Expiry
         metadata         chunks       cleanup
              ^            ^
              │            │
              └──────┬─────┘
                     │
                     v
                Receiver Browser
```

The sender and receiver do **not** directly communicate in the MVP.

P2P is explicitly outside the MVP.

---

# 3. Architectural Style

Use a **modular monolith**.

The backend is one deployable Spring Boot application, internally separated by responsibility.

Conceptually:

```text
backend/

├── api/
├── application/
├── domain/
├── infrastructure/
└── configuration/
```

The exact package names can be finalized during implementation.

Do not split the MVP into microservices.

---

# 4. Main Responsibilities

## 4.1 API Layer

Responsible for HTTP concerns:

- Request parsing
- Response formatting
- Validation at the API boundary
- Authentication/authorization of transfer access
- HTTP status codes
- Mapping application errors to responses

The API layer must not contain transfer business logic.

## 4.2 Application Layer

Responsible for coordinating use cases.

Examples:

- Create transfer
- Upload chunk
- Get transfer state
- Get available chunks
- Download chunk
- Record receiver progress if later required
- Complete transfer
- Expire transfer

The application layer coordinates domain logic and infrastructure.

## 4.3 Domain Layer

Contains transfer rules.

Examples:

- Is a chunk allowed to become available?
- Is a chunk already uploaded?
- Can a transfer be accessed?
- Is a transfer expired?
- What is the next missing chunk?
- Is a transfer complete?
- What state transition is valid?

The domain layer should not know whether storage is R2, local disk, or another provider.

## 4.4 Infrastructure Layer

Contains external systems:

- PostgreSQL
- Object storage
- File-system storage for local development
- Hashing implementation
- Scheduled cleanup
- External provider adapters

Infrastructure implements interfaces defined at the application/domain boundary where appropriate.

---

# 5. Transfer as the Central Domain Object

A `Transfer` represents one logical file transfer.

Example:

```text
Transfer

│
├── id
├── shareCode / shareToken
├── fileName
├── contentType
├── fileSize
├── chunkSize
├── totalChunks
├── status
├── createdAt
└── expiresAt
```

A transfer is created before the complete file exists.

This is essential for progressive transfer.

---

# 6. Chunk as the Fundamental Unit

A file is represented as ordered chunks.

```text
Transfer

│
├── Chunk 0
├── Chunk 1
├── Chunk 2
├── Chunk 3
├── ...
└── Chunk N
```

Each chunk has an index.

Example:

```text
chunkIndex = 42
```

The index identifies the logical position of that chunk in the file.

The system must not use byte offsets as the only source of truth.

The chunk index is the primary application-level identifier.

---

# 7. Chunk Metadata

Candidate metadata:

```text
TransferChunk

│
├── transferId
├── chunkIndex
├── size
├── checksum
├── storageKey
└── uploadedAt
```

A chunk is considered available only after its data has been successfully persisted and its integrity has been verified.

Possible logical states:

```text
MISSING
UPLOADING
AVAILABLE
FAILED
```

The exact persistence representation may use status fields, row existence, or another design.

---

# 8. Authoritative State

The backend is authoritative.

The browser must never assume:

```text
"I uploaded chunk 500, therefore chunk 500 exists."
```

Instead:

```text
Browser
   |
   v
Backend
   |
   v
Actual persisted state
```

This is critical for connection recovery.

If a browser crashes after sending a request but before receiving the response, the client can reconnect and reconcile with the backend.

---

# 9. Upload Lifecycle

```text
CREATE
  |
  v
CREATED
  |
  v
UPLOADING
  |
  +--------------------+
  |                    |
  v                    v
PAUSE              CONNECTION LOSS
  |                    |
  v                    v
RESUME              RECONNECT
  |                    |
  +----------+---------+
             |
             v
         UPLOADING
             |
             v
      ALL CHUNKS AVAILABLE
             |
             v
          COMPLETE
```

Pause is primarily a **client-side control over sending new chunks**.

It must not delete or invalidate already persisted chunks.

---

# 10. Sender Upload Algorithm

Conceptually:

```text
1. Create transfer
2. Receive transfer ID + share information
3. Determine chunk size
4. Read one chunk from File
5. Calculate checksum
6. Upload chunk
7. Server verifies checksum
8. Server persists chunk
9. Server marks chunk AVAILABLE
10. Release client-side chunk memory
11. Move to next chunk
12. Repeat
```

The complete file must never be held in browser memory.

---

# 11. Upload Resume

After interruption:

```text
Browser reconnects
        |
        v
GET transfer state
        |
        v
Determine available chunks
        |
        v
Find missing chunk(s)
        |
        v
Continue upload
```

The client must reconcile with server state rather than trusting its own local progress percentage.

This protects against:

- Lost responses
- Browser crashes
- Duplicate requests
- Partial writes
- Network interruption

---

# 12. Idempotency of Chunk Upload

Chunk upload should be safe to retry.

Example:

```text
PUT chunk 42
       |
       v
Network failure
       |
       v
Client retries chunk 42
```

The server should not create two logical copies of chunk 42.

A repeated upload of an already-valid identical chunk should be handled deterministically.

If the same index is uploaded with conflicting content, the server must reject or safely reconcile it rather than silently replacing valid data.

The exact conflict policy is finalized in `TRANSFER-PROTOCOL.md`.

---

# 13. Sender Pause / Resume

Sender pause does not modify the transfer's persisted chunks.

```text
Sender
  |
  | PAUSE
  v
Stop producing/uploading new chunks
  |
  v
Existing chunks remain AVAILABLE
```

On resume:

```text
RESUME
  |
  v
Reconcile state
  |
  v
Upload next missing chunk
```

This means:

```text
Sender PAUSED
+
Receiver DOWNLOADING
```

is a valid system state.

---

# 14. Receiver Download Architecture

The receiver first asks:

```text
What transfer is this?

What chunks are currently available?
```

Then downloads available chunks.

Conceptually:

```text
Receiver
   |
   v
Transfer metadata
   |
   v
Available chunk indexes
   |
   v
Download chunks
   |
   v
Write to local output
```

The receiver does not need to wait for `COMPLETE`.

---

# 15. Progressive Download

Progressive transfer is implemented through **chunk availability**, not by pretending an incomplete object is a complete file.

Example:

```text
Total chunks = 100

Available:
0 ... 59

Receiver:
0 ... 59  -> can download
60 ... 99  -> unavailable
```

The receiver can therefore consume chunks 0–59 immediately.

When chunk 60 becomes available, the receiver can continue.

This is the central architecture enabling progressive download.

---

# 16. Receiver Caught-Up State

If:

```text
uploaded = 60 chunks
downloaded = 60 chunks
```

the receiver is caught up.

The receiver enters:

```text
WAITING_FOR_DATA
```

It does not treat this as completion unless the transfer itself is complete.

Conceptually:

```text
if downloaded < totalChunks:

    if nextChunkAvailable:
        download()

    else:
        wait()

else:
    complete()
```

If the receiver uses polling, polling should stop while the receiver is explicitly paused. Resuming should trigger an immediate state reconciliation rather than waiting for the next polling interval.

---

# 17. Receiver Pause / Resume

Receiver pause is independent.

```text
DOWNLOADING
     |
     v
  PAUSED
     |
     v
  RESUME
     |
     v
DOWNLOADING
```

When paused:

- Stop downloading new chunks
- Stop availability polling
- Preserve already downloaded data
- Do not invalidate transfer state
- Sender may continue uploading

When resumed:

```text
Read local download state
        |
        v
Determine next missing chunk
        |
        v
Check server availability
        |
        v
Continue
```

---

# 18. Receiver Connection Recovery

If the receiver disconnects at:

```text
Downloaded: 43%
```

the local client must preserve its progress.

After reconnection:

```text
Reconnect
   |
   v
Reconcile server availability
   |
   v
Determine next missing local chunk
   |
   v
Continue download
```

The receiver must not unnecessarily restart from zero.

---

# 19. Local Receiver State

The receiver needs enough local state to recover.

The MVP assumes receiver resume is **local to the same browser/device**. Cross-device resume is not required.

The implementation should maintain something equivalent to:

```text
transferId
fileName
totalChunks
chunkSize
downloadedChunks
```

The exact persistence mechanism may be:

- IndexedDB
- File System Access API where appropriate
- Another browser-supported mechanism

These mechanisms are not interchangeable for a 20 GB target. The choice must be made through an early browser-storage prototype before the final download architecture is frozen.

A receiver that switches browsers/devices is not guaranteed to retain its previous local progress in the MVP.

---

# 20. Download Assembly

The receiver ultimately needs a valid local file.

```text
Chunk 0
Chunk 1
Chunk 2
...
Chunk N

   |
   v

Ordered file
```

The implementation must avoid building an enormous in-memory array of all chunks.

For the 20 GB target, browser-side storage and assembly must be validated before the implementation is considered complete.

The first architecture prototype should specifically evaluate direct writable file streams and persistent browser storage for reliable multi-GB assembly without excessive memory or quota problems.

---

# 21. Integrity Pipeline

For upload:

```text
Client
  |
  | bytes + checksum
  v
Backend
  |
  | verify
  v
Storage
  |
  v
AVAILABLE
```

For download:

```text
Storage
   |
   v
Receiver
   |
   | verify checksum
   v
Accept chunk
```

A checksum mismatch must result in recovery/retry rather than silently accepting the data.

A receiver must not mark a chunk as locally downloaded until the complete chunk has been successfully received, persisted locally, and integrity-verified.

---

# 22. Why We Do Not Implement Go-Back-N Literally

TCP already handles packet-level retransmission.

Our problem is different.

We need to recover **file chunks**.

Example:

```text
Chunks:

0 1 2 3 4 5 6 7 8 9

Available:

0 1 2 3 4 X 6 7 8 9
```

We should not unnecessarily resend:

```text
0 1 2 3 4
```

We only need to recover the missing/invalid application-level chunk.

This resembles the useful state-tracking idea behind Selective Repeat.

---

# 23. Storage Abstraction

Application code should not depend directly on Cloudflare R2 SDK/API.

Define an internal abstraction conceptually:

```text
ChunkStorage

│
├── putChunk()
├── getChunk()
├── exists()
├── deleteChunk()
└── deleteTransfer()
```

Then:

```text
                ChunkStorage
                    |
          +---------+---------+
          |                   |
          v                   v
       R2Storage         LocalStorage
```

This gives us:

- Easy local development
- Easier provider changes
- Testable storage logic
- Less vendor lock-in

---

# 24. PostgreSQL vs Object Storage

PostgreSQL stores:

```text
metadata
state
checksums
timestamps
relationships
```

Object storage stores:

```text
actual chunk bytes
```

Do not store multi-GB file data inside PostgreSQL.

---

# 25. Progressive Storage Requirement

A critical consequence of progressive transfer:

> The storage layer must make individual chunks independently readable before the complete file exists.

Therefore a storage architecture based only on:

```text
one incomplete object
```

is insufficient unless the provider/implementation gives us a reliable mechanism to read the uploaded prefix concurrently.

The safer MVP abstraction is:

```text
one object per chunk
```

This makes availability explicit:

```text
chunk-000000 -> exists
chunk-000001 -> exists
chunk-000002 -> exists
chunk-000003 -> missing
```

The receiver can safely download the first three.

This is one of the most important architecture decisions in the entire project.

---

# 26. Storage Key Design

Candidate:

```text
transfers/{transferId}/chunks/{chunkIndex}
```

Example:

```text
transfers/abc123/chunks/000000
transfers/abc123/chunks/000001
transfers/abc123/chunks/000002
```

The storage key should not expose sensitive information such as the original filename.

---

# 27. Share Link and Access Capability

The share identifier is an access credential in the no-login MVP.

It must have sufficient entropy to resist guessing.

Do not use sequential IDs as public access credentials.

Do not treat a short human-readable code such as `ABCD-1234` as secure merely because it looks random.

The architecture should distinguish between:

```text
Internal transfer ID
        |
        +--> database/storage identifier
        |
        +--> public access capability
```

The public access capability should be generated using a cryptographically secure random source.

If a human-readable code is retained for usability, it should not be the sole security boundary unless its entropy and brute-force protection are explicitly sufficient.

Because this is a bearer capability:

- Anyone possessing the valid capability may access the transfer according to policy
- HTTPS is mandatory in deployment
- Rate limiting is required
- Expiration is required
- Access attempts must not permit directory or transfer enumeration

The exact token/code format and API exposure are finalized in `SECURITY.md` and `API.md`.

---

# 28. Expiration

Every transfer has:

```text
createdAt
expiresAt
```

A scheduled cleanup process removes:

1. Chunk objects
2. Chunk metadata
3. Transfer metadata

Deletion should be idempotent.

---

# 29. Multiple Receivers

Each receiver has independent progress.

```text
Transfer

   |
   +--> Receiver A: 20%
   |
   +--> Receiver B: 64%
   |
   +--> Receiver C: 100%
```

For the MVP, receiver download progress is primarily local to each browser/device.

The server does not need detailed per-receiver progress unless a concrete requirement appears.

The server primarily needs to know which chunks are available.

A receiver moving to another browser/device is not guaranteed to resume because the MVP does not require server-side receiver-progress persistence.

---

# 30. Concurrency

Multiple chunks may eventually be transferred concurrently.

However:

> Correctness comes before parallelism.

Initial implementation should establish reliable sequential or bounded-concurrency behavior first.

Then benchmark:

```text
1 concurrent chunk
2 concurrent chunks
4 concurrent chunks
8 concurrent chunks
```

Do not introduce unbounded concurrency.

This applies to both chunk transfer concurrency and availability polling/notification behavior.

---

# 31. Availability Notification

The receiver needs to discover new chunks.

Possible mechanisms:

### Option A — Polling

```text
Receiver
   |
   | availability?
   v
Backend
```

### Option B — SSE

```text
Backend
   |
   | new chunk available
   v
Receiver
```

### Option C — WebSocket

Possible but likely unnecessary for the MVP.

The initial implementation should prefer the simplest reliable mechanism.

If polling is used:

- Paused receivers should stop polling
- Caught-up receivers may poll at a bounded interval
- Resuming should trigger immediate reconciliation
- Polling frequency must be bounded to avoid unnecessary load

Regardless of notification method:

> Notifications are hints, not authoritative state.

After reconnection, the receiver must query actual state.

---

# 32. API Responsibility

The API should expose operations around transfers and chunks rather than exposing storage-provider operations directly.

Conceptually:

```text
POST /transfers
GET  /transfers/{id}
GET  /transfers/{id}/availability
PUT  /transfers/{id}/chunks/{index}
GET  /transfers/{id}/chunks/{index}
POST /transfers/{id}/complete
```

The exact API contract will be frozen in `API.md`.

---

# 33. Error Handling

Errors must be explicit.

Important categories:

```text
TRANSFER_NOT_FOUND
TRANSFER_EXPIRED
INVALID_CHUNK_INDEX
CHECKSUM_MISMATCH
CHUNK_NOT_AVAILABLE
CHUNK_CONFLICT
INVALID_TRANSFER_STATE
STORAGE_FAILURE
ACCESS_DENIED
RATE_LIMITED
```

The frontend should convert these into understandable user states.

---

# 34. No User Accounts

There is no user account system.

Authorization is transfer-based.

```text
Valid transfer capability
        |
        v
Access transfer
```

Security therefore depends heavily on:

- Cryptographically unpredictable access capabilities
- HTTPS
- Expiration
- Input validation
- Rate limiting
- Safe storage access
- No directory enumeration

---

# 35. Frontend Architecture

Conceptually:

```text
React UI
   |
   v
Transfer Controller / Hooks
   |
   +--> Upload Manager
   |
   +--> Download Manager
   |
   +--> Transfer State
   |
   +--> API Client
   |
   +--> Local Persistence
```

The UI should not directly manage chunk protocol details.

For example:

```text
Pause button
    |
    v
Upload Manager.pause()
```

not:

```text
React component manually controls every request
```

---

# 36. Upload Manager

Responsible for:

- File slicing
- Chunk scheduling
- Uploading
- Pause/resume
- Retry
- Progress calculation
- Reconciliation
- Checksum generation

It should expose a simple state to the UI:

```text
status
progress
speed
uploadedBytes
totalBytes
error
```

---

# 37. Download Manager

Responsible for:

- Chunk availability
- Chunk scheduling
- Downloading
- Pause/resume
- Retry
- Reconnection
- Local persistence
- File assembly
- Progress calculation
- Download integrity verification

The UI consumes its state rather than implementing these rules itself.

---

# 38. Backend State vs Client State

### Backend is authoritative for:

- Transfer existence
- Transfer expiration
- Available chunks
- Stored chunk metadata
- Chunk integrity
- Transfer completion

### Sender client owns:

- Current upload execution
- Whether user pressed pause
- Active request scheduling
- Local UI state

### Receiver client owns:

- Current download execution
- Whether user pressed pause
- Local downloaded-chunk state
- Active request scheduling
- Local UI state

This separation prevents the UI from becoming the source of truth.

---

# 39. Failure Scenarios

The architecture must handle at least:

### Sender loses connection

```text
Upload stops
    |
    v
Persisted chunks remain
    |
    v
Reconnect
    |
    v
Reconcile
    |
    v
Continue
```

### Receiver loses connection

```text
Download stops
    |
    v
Downloaded chunks remain locally
    |
    v
Reconnect
    |
    v
Reconcile
    |
    v
Continue
```

### Sender pauses

```text
No new upload chunks

+

Existing chunks remain available

+

Receiver continues until caught up
```

### Receiver pauses

```text
No new downloads

+

Availability polling stops

+

Sender can continue uploading
```

### Chunk checksum mismatch

```text
Reject
  |
  v
Retry
```

### Expired transfer

```text
Reject access
  |
  v
Cleanup
```

---

# 40. Critical Invariants

These rules must always hold.

## Invariant 1

A chunk is not AVAILABLE until it is safely persisted and integrity-verified.

## Invariant 2

A receiver cannot download an unavailable chunk.

## Invariant 3

Pause does not delete completed progress.

## Invariant 4

Connection loss does not reset completed progress.

## Invariant 5

A chunk index maps to one logical position in the file.

## Invariant 6

The frontend is not the source of truth for server-side availability.

## Invariant 7

Expired transfers cannot be accessed.

## Invariant 8

The complete file is never required to exist before progressive downloading begins.

## Invariant 9

Storage failures must not be reported as successful chunk uploads.

## Invariant 10

Retrying a chunk must not silently corrupt or replace valid data.

## Invariant 11

A receiver must not mark a chunk as downloaded until the complete chunk has been successfully received, persisted locally, and integrity-verified.

---

# 41. What We Are Explicitly NOT Building

Do not introduce:

- Microservices
- Kafka
- RabbitMQ
- Redis
- Kubernetes
- Custom TCP
- Custom UDP
- P2P
- WebRTC
- Distributed consensus
- Automatic compression
- Authentication
- Chat
- Collaboration features

unless a later requirement proves one is necessary.

The MVP should demonstrate strong engineering through correctness and architecture, not technology count.

---

# 42. Architecture Risks to Resolve Before Coding

These are the issues we must validate early.

## Risk 1 — Browser-side multi-GB storage and assembly

Can the browser reliably preserve and assemble a 20 GB transfer without excessive memory usage or browser storage/quota problems?

This is the highest-priority architecture prototype.

Before the download architecture is frozen, test viable browser mechanisms, especially:

- File System Access API / writable streams
- IndexedDB
- Memory behavior
- Persistence across refresh/reconnect
- Practical multi-GB limits

The result of this prototype may determine the final `DownloadManager` and local persistence design.

## Risk 2 — Object storage request behavior

Can the chosen object-storage provider handle the chunk pattern and expected request volume economically?

## Risk 3 — Hosting limits

Does the backend host permit sufficiently large chunk requests and appropriate request durations/timeouts?

## Risk 4 — Progressive polling/notification

Can the receiver efficiently detect new chunks without excessive request volume?

## Risk 5 — Share-code/access capability security

Is the selected access capability sufficiently unpredictable, and are rate limiting and enumeration protections adequate?

## Risk 6 — Resume after browser refresh/close

Can receiver progress be persisted robustly enough for the desired same-browser/device UX?

These should be tested with small prototypes before the corresponding architecture and API contracts are frozen.

---

# 43. Implementation Order

The implementation should follow this dependency order:

```text
0. Browser storage / 20 GB receive prototype
        |
        v
1. Transfer domain model
        |
        v
2. Storage abstraction
        |
        v
3. Create transfer
        |
        v
4. Chunk upload
        |
        v
5. Chunk availability
        |
        v
6. Chunk download
        |
        v
7. Pause/resume
        |
        v
8. Connection recovery
        |
        v
9. Progressive transfer
        |
        v
10. Integrity
        |
        v
11. Expiration
        |
        v
12. UI polish
        |
        v
13. CI/CD
        |
        v
14. Deployment
```

The browser-storage prototype is intentionally first because a failure there could require changes to the download architecture.

The exact order may change after repository/protocol research.

---

# 44. Architecture Boundary

The system should be easy to explain as:

```text
                 ┌───────────────┐
                 │    Browser    │
                 │               │
                 │ UploadManager │
                 │ DownloadMgr   │
                 └───────┬───────┘
                         │
                       HTTP
                         │
                         v
                 ┌───────────────┐
                 │   Transfer    │
                 │     API       │
                 └───────┬───────┘
                         │
                 ┌───────┴───────┐
                 │               │
                 v               v
          Transfer Domain   Storage Adapter
                 │               │
                 v               v
            PostgreSQL      Object Storage
```

Each layer should have one clear responsibility.

---

# 45. Final Architecture Definition

The MVP is a **storage-backed, modular-monolith file-transfer system**.

A transfer is represented as a collection of independently persisted chunks.

The backend maintains authoritative transfer/chunk state.

The sender uploads chunks incrementally and may pause/resume independently.

The receiver downloads available chunks incrementally and may pause/resume independently.

Because chunks become independently available, the receiver can begin downloading before the sender finishes uploading.

Connection recovery works by reconciling client progress against persisted transfer state rather than restarting the file.

Receiver progress is local to the browser/device in the MVP; cross-device resume is not guaranteed.

Integrity verification ensures that corrupted or incomplete chunks are detected.

Temporary expiration removes transfer data after its lifetime.

The public transfer capability must be cryptographically unpredictable and protected against brute-force enumeration.

The architecture deliberately avoids microservices, P2P, custom transport protocols, and unnecessary infrastructure.

Before the download/API contracts are frozen, the browser-side multi-GB storage/assembly strategy must be validated through a focused prototype.

The implementation must preserve the critical invariants defined in this document.
