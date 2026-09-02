# Large-File Transfer — API Specification

**Status:** ACTIVE — API Baseline  
**Document:** `API.md`  
**Version:** 1.0  
**Depends on:** `MVP.md`, `TECHNICAL.md`, `ARCHITECTURE.md`, `TRANSFER-PROTOCOL.md`

---

# 1. Purpose

This document defines the HTTP API contract for the MVP.

It specifies:

- Transfer creation
- Transfer lookup
- Chunk availability
- Chunk upload
- Chunk download
- Transfer completion
- Error responses
- Access control through share capabilities
- Request/response semantics

The API exposes transfer operations, not storage-provider operations.

The exact implementation may use Spring Boot controllers and application services, but the HTTP contract defined here is the boundary that the frontend implements against.

---

# 2. API Principles

The API follows these rules:

1. HTTP/HTTPS is the transport.
2. The backend is authoritative for transfer and chunk state.
3. Chunks are independently addressable by index.
4. Uploading a chunk is idempotent for identical content.
5. A chunk is available only after successful persistence and integrity verification.
6. Unavailable chunks cannot be downloaded.
7. A receiver may download chunks before the transfer is complete.
8. Sender pause/resume is primarily client-side and does not require a dedicated server pause endpoint.
9. Receiver pause/resume is entirely client-side.
10. Connection recovery is performed through state reconciliation.
11. Transfer access is controlled through an unpredictable bearer capability.
12. Notifications, if later added, do not replace the availability endpoint as the source of truth.

---

# 3. Base URL

Development:

```text
http://localhost:<port>/api
```

Production:

```text
https://<domain>/api
```

The exact production domain and port are deployment concerns.

---

# 4. Transfer Identifier and Share Capability

Each transfer has:

```text
transferId
shareToken
```

`transferId` is an internal identifier.

`shareToken` is the public bearer capability used by a receiver to access the transfer.

The public capability must be generated using cryptographically secure randomness.

Sequential database IDs must never be used as the public access credential.

The API must not expose storage keys.

---

# 5. Transfer Creation

## Endpoint

```http
POST /api/transfers
```

## Purpose

Creates a new transfer before any chunks are uploaded.

The transfer may immediately be used by the sender to begin uploading chunks.

## Request

```http
Content-Type: application/json
```

Example:

```json
{
  "fileName": "example.pdf",
  "fileSize": 22576087,
  "contentType": "application/pdf",
  "chunkSize": 8388608
}
```

### Fields

| Field | Type | Required | Description |
|---|---|---:|---|
| `fileName` | string | yes | Original filename |
| `fileSize` | integer | yes | File size in bytes |
| `contentType` | string | yes | MIME type |
| `chunkSize` | integer | yes | Selected chunk size in bytes |

The server calculates:

```text
totalChunks = ceil(fileSize / chunkSize)
```

The server must validate that the supplied values are valid.

## Response

```http
201 Created
Content-Type: application/json
```

Example:

```json
{
  "transferId": "01J...",
  "shareToken": "random-public-capability",
  "fileName": "example.pdf",
  "fileSize": 22576087,
  "contentType": "application/pdf",
  "chunkSize": 8388608,
  "totalChunks": 3,
  "status": "CREATED",
  "createdAt": "2026-09-01T15:30:00Z",
  "expiresAt": "2026-09-02T15:30:00Z"
}
```

The sender stores the returned transfer information locally.

---

# 6. Transfer State

## Endpoint

```http
GET /api/transfers/{transferId}
```

## Purpose

Returns authoritative transfer metadata and current chunk state.

This endpoint is used for:

- Initial receiver setup
- Upload recovery
- Download recovery
- Browser refresh recovery
- Debugging
- Progress reconciliation

## Authorization

The request must provide a valid transfer capability.

Conceptually:

```http
Authorization: Bearer <shareToken>
```

The exact header mechanism may be changed only if the implementation preserves the same security properties.

## Response

```http
200 OK
Content-Type: application/json
```

Example:

```json
{
  "transferId": "01J...",
  "fileName": "example.pdf",
  "fileSize": 22576087,
  "contentType": "application/pdf",
  "chunkSize": 8388608,
  "totalChunks": 3,
  "status": "UPLOADING",
  "availableChunks": [0, 1],
  "availableChunkCount": 2,
  "createdAt": "2026-09-01T15:30:00Z",
  "expiresAt": "2026-09-02T15:30:00Z"
}
```

The backend determines `availableChunks`.

The client must not infer availability from its own progress percentage.

---

# 7. Chunk Availability

## Endpoint

```http
GET /api/transfers/{transferId}/availability
```

## Purpose

Returns the currently available chunks without requiring the full transfer metadata.

This is primarily used by receivers while waiting for additional chunks.

## Authorization

A valid transfer capability is required.

## Response

```http
200 OK
Content-Type: application/json
```

Example:

```json
{
  "transferId": "01J...",
  "totalChunks": 100,
  "availableChunks": [0, 1, 2, 3, 4, 5],
  "availableChunkCount": 6,
  "transferStatus": "UPLOADING"
}
```

The receiver compares this against its local downloaded-chunk state.

If the next required chunk is unavailable and the transfer is not complete, the receiver waits and checks again according to the selected availability mechanism.

---

# 8. Chunk Upload

## Endpoint

```http
PUT /api/transfers/{transferId}/chunks/{chunkIndex}
```

## Purpose

Uploads one logical chunk.

A chunk index identifies one fixed position in the file.

## Headers

```http
Content-Type: application/octet-stream
X-Chunk-Checksum: <checksum>
```

The checksum algorithm must match the algorithm defined by the implementation.

SHA-256 is the current protocol baseline.

## Request Body

Raw chunk bytes.

The request body must contain only the chunk payload.

Do not wrap large chunks in JSON or base64.

## Validation

The server validates:

1. Transfer exists
2. Transfer has not expired
3. Transfer is in a valid upload state
4. `chunkIndex` is within range
5. Payload size matches the expected size for that chunk
6. Checksum matches the received bytes
7. Existing chunk state, if any, is handled according to the conflict rules

## Successful New Upload

```http
201 Created
Content-Type: application/json
```

Example:

```json
{
  "transferId": "01J...",
  "chunkIndex": 42,
  "size": 8388608,
  "checksum": "sha256:...",
  "status": "AVAILABLE"
}
```

The server must persist the chunk before reporting `AVAILABLE`.

---

# 9. Idempotent Chunk Upload

If the same chunk index is uploaded again with identical valid content:

```http
200 OK
```

Example:

```json
{
  "transferId": "01J...",
  "chunkIndex": 42,
  "size": 8388608,
  "checksum": "sha256:...",
  "status": "AVAILABLE",
  "alreadyExists": true
}
```

The request must not create a duplicate logical chunk.

If the same chunk index is submitted with different content from the already-valid chunk:

```http
409 Conflict
```

Example:

```json
{
  "error": "CHUNK_CONFLICT",
  "message": "Chunk already exists with different content."
}
```

The server must never silently replace a valid chunk with conflicting content.

---

# 10. Chunk Size Rules

For a transfer with:

```text
fileSize
chunkSize
totalChunks
```

All chunks except the final chunk should have:

```text
size = chunkSize
```

The final chunk may be smaller.

Example:

```text
fileSize = 22,576,087
chunkSize = 8,388,608

chunk 0 = 8,388,608 bytes
chunk 1 = 8,388,608 bytes
chunk 2 = 5,798,871 bytes
```

The server must validate expected chunk size.

The final chunk size is:

```text
fileSize - (chunkSize × (totalChunks - 1))
```

---

# 11. Chunk Download

## Endpoint

```http
GET /api/transfers/{transferId}/chunks/{chunkIndex}
```

## Purpose

Downloads one available chunk.

The receiver may request a chunk before the transfer is complete.

## Authorization

A valid transfer capability is required.

## Successful Response

```http
200 OK
Content-Type: application/octet-stream
```

Response body:

```text
raw chunk bytes
```

Optional metadata headers may include:

```http
X-Chunk-Index: 42
X-Chunk-Checksum: sha256:...
```

The exact header set may be finalized during implementation.

## Important Rule

The server must return a chunk only if that chunk is `AVAILABLE`.

If the chunk has not been successfully persisted and verified:

```http
404 Not Found
```

or the implementation's defined unavailable-chunk status must be returned consistently.

The API must not return partial chunk data as a successful chunk download.

---

# 12. Receiver Download Verification

The receiver must not mark a chunk as downloaded merely because an HTTP request began or returned headers.

The receiver should:

1. Receive the complete chunk
2. Verify its checksum
3. Persist it locally
4. Only then mark it locally as downloaded

If the download is interrupted or checksum verification fails:

```text
chunk remains incomplete/missing
```

The receiver retries it later.

This protects against partial local writes being mistaken for successful progress.

---

# 13. Transfer Completion

## Endpoint

```http
POST /api/transfers/{transferId}/complete
```

## Purpose

Marks the transfer complete after all expected chunks are available.

This endpoint is controlled by the backend's authoritative state.

## Request

No request body is required.

## Server Validation

The server verifies:

```text
availableChunkCount == totalChunks
```

and that every required chunk is valid.

The server must not trust a client-provided:

```text
"100%"
```

or equivalent completion claim.

## Successful Response

```http
200 OK
Content-Type: application/json
```

Example:

```json
{
  "transferId": "01J...",
  "status": "COMPLETE"
}
```

If chunks are missing:

```http
409 Conflict
```

Example:

```json
{
  "error": "TRANSFER_NOT_COMPLETE",
  "message": "Not all chunks are available."
}
```

---

# 14. Upload Pause / Resume

No dedicated pause endpoint is required for the MVP.

Pause is a sender-client operation:

```text
UploadManager.pause()
```

The client stops initiating new chunk uploads.

Already uploaded chunks remain available.

Resume performs reconciliation:

```text
GET transfer state
        |
        v
determine missing chunks
        |
        v
continue upload
```

The server does not need to know that the sender's UI is currently paused.

This allows:

```text
sender paused
+
receiver downloading
```

to remain valid.

---

# 15. Receiver Pause / Resume

No dedicated receiver pause endpoint is required.

Pause stops the receiver's download scheduling.

Already downloaded chunks remain locally persisted.

Resume performs:

```text
read local state
        |
        v
query server availability
        |
        v
find next missing chunk
        |
        v
continue
```

The sender is unaffected.

---

# 16. Connection Recovery

## Sender

After connection loss:

```text
reconnect
   |
   v
GET transfer state
   |
   v
read available chunks
   |
   v
find missing chunks
   |
   v
resume upload
```

The sender must not blindly restart from chunk zero.

## Receiver

After connection loss:

```text
reconnect
   |
   v
read local state
   |
   v
GET availability
   |
   v
find next missing chunk
   |
   v
resume download
```

The receiver must not assume that the last request sent was successfully persisted locally.

---

# 17. Progressive Transfer Contract

The API explicitly supports:

```text
Transfer status = UPLOADING
+
Some chunks = AVAILABLE
```

Example:

```text
totalChunks = 100

available:
0 ... 59

unavailable:
60 ... 99
```

The receiver may download:

```text
0 ... 59
```

without waiting for:

```text
COMPLETE
```

When caught up:

```text
downloaded == available
```

but:

```text
transferStatus == UPLOADING
```

the receiver waits for more availability.

---

# 18. Expired Transfers

Every transfer has an expiration time.

After expiration, transfer access must be rejected.

Example:

```http
410 Gone
```

Response:

```json
{
  "error": "TRANSFER_EXPIRED",
  "message": "This transfer has expired."
}
```

Cleanup is performed separately.

Expiration must not depend on cleanup having already deleted the data.

The transfer is logically inaccessible as soon as it expires.

---

# 19. Error Response Format

All API errors should use a consistent JSON structure.

Example:

```json
{
  "error": "CHECKSUM_MISMATCH",
  "message": "Chunk checksum verification failed."
}
```

Optional fields may include:

```json
{
  "error": "CHECKSUM_MISMATCH",
  "message": "Chunk checksum verification failed.",
  "transferId": "01J...",
  "chunkIndex": 42
}
```

Do not expose:

- Database internals
- Storage-provider credentials
- Storage keys
- Stack traces
- Internal filesystem paths

---

# 20. Standard Error Codes

The MVP recognizes at least:

```text
TRANSFER_NOT_FOUND
TRANSFER_EXPIRED
INVALID_TRANSFER_STATE
INVALID_CHUNK_INDEX
INVALID_CHUNK_SIZE
CHECKSUM_MISMATCH
CHUNK_NOT_AVAILABLE
CHUNK_CONFLICT
TRANSFER_NOT_COMPLETE
STORAGE_FAILURE
INVALID_REQUEST
UNAUTHORIZED
FORBIDDEN
```

The exact HTTP status mapping is:

| Error | HTTP Status |
|---|---:|
| `INVALID_REQUEST` | 400 |
| `UNAUTHORIZED` | 401 |
| `FORBIDDEN` | 403 |
| `TRANSFER_NOT_FOUND` | 404 |
| `CHUNK_NOT_AVAILABLE` | 404 |
| `TRANSFER_EXPIRED` | 410 |
| `INVALID_CHUNK_INDEX` | 400 |
| `INVALID_CHUNK_SIZE` | 400 |
| `CHECKSUM_MISMATCH` | 422 |
| `CHUNK_CONFLICT` | 409 |
| `TRANSFER_NOT_COMPLETE` | 409 |
| `INVALID_TRANSFER_STATE` | 409 |
| `STORAGE_FAILURE` | 500 |

The implementation should keep these mappings consistent.

---

# 21. Access Control

The MVP has no user accounts.

Transfer access is capability-based.

Conceptually:

```text
shareToken
    |
    v
transfer access
```

A valid capability allows access according to the transfer policy.

The server must:

- Validate the capability
- Validate expiration
- Prevent transfer enumeration
- Never expose unrelated transfers
- Avoid leaking storage-provider identifiers

---

# 22. Share Token Requirements

The share token must have enough entropy to make guessing impractical.

The implementation must use a cryptographically secure random generator.

Short human-readable codes may be supported later, but they must not replace a sufficiently strong bearer capability without additional protections.

If a human-readable code is introduced, rate limiting and brute-force protection become mandatory parts of its security model.

The exact token format is finalized in `SECURITY.md`.

---

# 23. Request Size and Streaming

Chunk endpoints must support binary request/response bodies.

The implementation must not require the entire multi-GB transfer to be represented as one HTTP request.

Each request is limited to one chunk.

The backend should process chunk payloads in a streaming-friendly manner where practical.

The client must never convert large chunks into unnecessary base64 representations.

---

# 24. Content-Disposition

For direct chunk downloads, the response is binary data.

The final-file download mechanism is a frontend concern.

The API should not attempt to make each individual chunk appear to the user as a separate file.

The receiver assembles the ordered chunks locally.

---

# 25. CORS

The backend must allow the configured frontend origin(s).

Development may use:

```text
http://localhost:<frontend-port>
```

Production origins must be explicitly configured.

Do not use unrestricted CORS in production unless there is a demonstrated reason.

---

# 26. Health Endpoint

## Endpoint

```http
GET /health
```

## Response

```http
200 OK
```

Example:

```json
{
  "status": "UP"
}
```

The endpoint should remain lightweight.

Detailed infrastructure diagnostics should not be exposed publicly by default.

---

# 27. API Does Not Expose Storage Operations

The following are intentionally NOT public API concepts:

```text
PUT /r2/object
GET /storage/object
DELETE /bucket/key
```

The client interacts with:

```text
transfer
chunk
availability
```

The backend decides how those operations map to storage.

This preserves the storage abstraction defined in `ARCHITECTURE.md`.

---

# 28. Example Complete Flow

## Sender

```text
POST /transfers
        |
        v
receive transferId + shareToken
        |
        v
PUT /transfers/{id}/chunks/0
        |
        v
201 Created
        |
        v
PUT /transfers/{id}/chunks/1
        |
        v
201 Created
        |
        v
...
        |
        v
POST /transfers/{id}/complete
```

## Receiver

```text
GET /transfers/{id}
        |
        v
receive metadata + availability
        |
        v
GET /transfers/{id}/chunks/0
        |
        v
verify + persist locally
        |
        v
GET /transfers/{id}/chunks/1
        |
        v
...
```

If the receiver catches up:

```text
GET /transfers/{id}/availability
        |
        v
no new chunk
        |
        v
wait
        |
        v
check again
```

---

# 29. Example Connection Recovery

Suppose:

```text
totalChunks = 100
server available = 0...57
sender believes uploaded = 0...58
```

The sender reconnects and queries the server.

The server says:

```text
0...57 available
58 missing
```

The sender therefore retries:

```text
chunk 58
```

It does not restart from:

```text
chunk 0
```

The same principle applies to receivers.

---

# 30. API Invariants

The following must always hold:

1. A successful chunk upload means the chunk is safely persisted and verified.
2. An unavailable chunk cannot be successfully downloaded.
3. A valid identical retry does not create duplicate logical chunks.
4. A conflicting retry cannot silently overwrite a valid chunk.
5. Completion cannot be reported while required chunks are missing.
6. Expired transfers cannot be accessed.
7. The client cannot declare server-side chunk availability.
8. Pause does not delete progress.
9. Connection recovery uses authoritative state.
10. The API never exposes storage-provider credentials or internal storage keys.
11. A partially downloaded chunk is never reported by the client as successfully downloaded.
12. Progressive download is possible while the transfer status remains `UPLOADING`.

---

# 31. Endpoint Summary

| Method | Endpoint | Purpose |
|---|---|---|
| `POST` | `/api/transfers` | Create transfer |
| `GET` | `/api/transfers/{transferId}` | Get transfer state |
| `GET` | `/api/transfers/{transferId}/availability` | Get available chunks |
| `PUT` | `/api/transfers/{transferId}/chunks/{chunkIndex}` | Upload chunk |
| `GET` | `/api/transfers/{transferId}/chunks/{chunkIndex}` | Download chunk |
| `POST` | `/api/transfers/{transferId}/complete` | Complete transfer |
| `GET` | `/health` | Health check |

No pause/resume endpoints are required.

No authentication endpoints are required.

No storage-provider endpoints are exposed.

---

# 32. Explicitly Deferred

The following are intentionally not frozen beyond this API contract:

- Exact framework controller class names
- Exact DTO class names
- Exact database schema
- Storage provider
- Polling interval
- SSE/WebSocket implementation
- Rate-limit thresholds
- Exact share-token encoding
- Exact checksum header naming if implementation requires adjustment
- Browser local persistence mechanism
- Parallel request limits

These must not change the core API semantics without updating this document.

---

# 33. Final API Definition

The MVP exposes a small transfer-oriented HTTP API.

The API treats a file as independently addressable chunks and allows:

- Transfer creation
- Chunk upload
- Chunk availability discovery
- Chunk download
- Progressive downloading
- Pause/resume through client control
- Connection recovery through state reconciliation
- Integrity verification
- Transfer completion
- Temporary expiration

The backend remains authoritative for transfer and chunk state.

The frontend controls execution and presentation but never becomes the source of truth for persisted transfer state.

The API intentionally remains small and storage-provider agnostic so that the implementation can evolve internally without requiring the frontend to understand infrastructure details.
