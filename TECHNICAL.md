# Large-File Transfer — Technical Specification

**Status:** ACTIVE — Technical Baseline  
**Document:** `TECHNICAL.md`  
**Version:** 1.0

## 1. Purpose

Define the technical stack and major engineering decisions for the MVP in `MVP.md`.

The system is a web-based file-transfer service built around:

- Chunked transfer
- Resumable upload/download
- Sender upload pause/resume
- Receiver download pause/resume
- Connection-loss recovery
- Progressive download while upload is still running
- Chunk integrity verification
- Temporary storage
- No user accounts

We are **not** building a new transport protocol. TCP/HTTP handle network-level reliability; our application tracks reliable **file-chunk state**.

---

# 2. Technology Stack

## Frontend

**React + TypeScript + Vite**

Main browser technologies:

- HTML5 File API
- `File.slice()`
- Fetch API
- Streams where useful
- Server-Sent Events (SSE) or WebSocket only if needed for live availability updates

Responsibilities:

- Select/drop files
- Read files incrementally
- Create chunks
- Upload chunks
- Pause/resume upload
- Track upload progress
- Request/download available chunks
- Pause/resume download
- Reconnect and reconcile state

A multi-GB file must never be loaded completely into browser memory.

## Backend

**Java + Spring Boot + Maven**

Java 17 is the baseline.

Responsibilities:

- Transfer creation
- Transfer metadata/state
- Chunk coordination
- Upload/download authorization
- Integrity verification
- Expiration
- Storage coordination
- Health endpoint
- API layer

Spring Boot currently requires at least Java 17. [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html)

## Database

**PostgreSQL**

Stores metadata, never the actual multi-GB file contents.

Conceptually:

```text
Transfer
---------
transfer_id
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

Potential chunk metadata:

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

The exact schema is defined later in `ARCHITECTURE.md`.

## Object Storage

**Cloudflare R2 — primary candidate**

R2 is attractive because it provides object storage, free internet egress, and a current Standard free allowance of 10 GB-month storage, 1M Class A operations, and 10M Class B operations per month.

Important: the 20 GB dataset used for testing exceeds the free storage allowance if retained long enough. Therefore, **20 GB is a test target, not a promise of unlimited zero-cost operation**.

Storage should sit behind an internal abstraction:

```text
Transfer Service
       |
       v
Storage Service
       |
       +---- R2
       |
       +---- Local filesystem (development)
       |
       +---- Future provider
```

R2 remains a deployment candidate until the architecture and deployment constraints are tested.

## CI/CD

**GitHub Actions**

Minimum pipeline:

```text
git push
   |
   v
GitHub Actions
   |
   +--> Backend build
   +--> Backend tests
   +--> Frontend build
   +--> Frontend tests
   |
   v
PASS / FAIL
```

Deployment automation can be added after the hosting provider is finalized.

---

# 3. Core Technical Model

The central abstraction is:

> A file is a sequence of independently addressable chunks whose state is tracked by the system.

```text
File
 |
 +-- Chunk 0
 +-- Chunk 1
 +-- Chunk 2
 +-- Chunk 3
 +-- ...
 +-- Chunk N
```

The backend is the source of truth for which chunks are actually available.

The browser's displayed percentage is only a presentation of that state.

---

# 4. Chunking

Chunking is mandatory.

A large file is processed incrementally:

```text
20 GB File
    |
    v
+---------+
| Chunk 0 |
+---------+
| Chunk 1 |
+---------+
| Chunk 2 |
+---------+
|   ...   |
+---------+
| Chunk N |
+---------+
```

Initial benchmark range:

**8–32 MB per chunk**

The final value is not locked yet.

Chunk size affects:

- Request count
- Memory usage
- Retry cost
- Storage operations
- Progress granularity
- Throughput

---

# 5. Upload

Conceptual flow:

```text
Browser
   |
   | Create transfer
   v
Backend
   |
   | Transfer ID + share information
   v
Browser
   |
   | Chunk N
   v
Backend
   |
   v
Object Storage
```

A chunk becomes `AVAILABLE` only after:

1. Data is received
2. Integrity is verified
3. Data is persisted
4. Metadata/state is updated

The receiver must never be told that a chunk exists before it is safely persisted.

---

# 6. Resumable Upload

Example:

```text
Total chunks: 1000
Available:    0–643
Next chunk:   644
```

After connection loss:

```text
Connection lost
      |
      v
Reconnect
      |
      v
Read transfer state
      |
      v
Next required chunk = 644
      |
      v
Continue
```

The client must not blindly restart from chunk 0.

---

# 7. Sender Pause / Resume

Sender pause is an intentional upload control.

```text
UPLOADING
    |
    v
 PAUSED
    |
    v
 RESUMED
    |
    v
UPLOADING
```

When paused:

- Stop sending new chunks
- Preserve uploaded chunks
- Keep the transfer valid
- Allow the receiver to consume available chunks

When resumed:

- Reconcile transfer state
- Continue from the correct next chunk

---

# 8. Receiver Download

The receiver does not wait for the complete file.

```text
Available:

[0][1][2][3][4][5][ ][ ][ ]

Receiver:
[0] -> download
[1] -> download
[2] -> download
[3] -> download
[4] -> download
[5] -> download

Chunk 6 unavailable
        |
        v
      WAIT
```

When chunk 6 becomes available, downloading continues.

This is the core progressive-transfer mechanism.

---

# 9. Receiver Pause / Resume

Receiver pause is independent from sender state.

```text
DOWNLOADING
      |
      v
   PAUSED
      |
      v
   RESUMED
      |
      v
DOWNLOADING
```

When paused:

- Stop requesting new chunks
- Preserve downloaded data
- Do not reset progress
- Allow the sender to continue independently

When resumed:

- Determine the next missing chunk
- Continue downloading from that point

---

# 10. Progressive Transfer

The system must distinguish:

```text
Uploaded data
```

from:

```text
Complete file
```

Therefore:

```text
CREATE TRANSFER
       |
       v
UPLOAD CHUNKS
       |
       +------------------+
       |                  |
       v                  v
AVAILABLE CHUNKS      RECEIVER
       |                  |
       +---------> DOWNLOAD
                          |
                          v
                    WAIT IF CAUGHT UP
                          |
                          v
                    CONTINUE WHEN
                    NEW CHUNKS EXIST
```

The receiver can consume only data that has already been successfully uploaded and made available.

---

# 11. Transfer State

The backend owns authoritative transfer state.

Candidate states:

```text
CREATED
UPLOADING
COMPLETING
COMPLETE
EXPIRED
DELETED
FAILED
```

Important:

**Sender pause is not necessarily a global transfer state.**

A sender may pause uploading while a receiver continues downloading already-available chunks.

Therefore the final architecture should distinguish:

```text
Transfer state
Sender upload state
Receiver download state
```

The exact state machine belongs in `ARCHITECTURE.md`.

---

# 12. Integrity

Each chunk should have an integrity value.

```text
Chunk bytes
     |
     v
Hash
     |
     v
Stored checksum
```

Verification:

```text
Received chunk
      |
      v
Calculate checksum
      |
      v
Compare
   /     \
MATCH   MISMATCH
 |          |
 v          v
ACCEPT     RETRY
```

SHA-256 is the initial candidate.

The exact implementation is not locked until architecture/testing.

Integrity is an application-level guarantee; it is separate from TCP reliability.

---

# 13. Resumable Upload Protocols

The **tus protocol** is highly relevant.

tus is an HTTP-based resumable upload protocol. Its core model uses an upload offset to determine where an interrupted upload should continue, and it defines extensions for creation, expiration, checksums, termination and other functionality.

We should evaluate:

1. Use tus directly
2. Reuse its concepts
3. Implement a smaller application-specific protocol

We should **not blindly copy tus**.

Reference: [tus resumable upload protocol](https://tus.io/protocols/resumable-upload)

---

# 14. Networking Model

Use:

**HTTP/HTTPS**

TCP already handles:

- Packet ordering
- Packet retransmission
- Connection-level reliability

Our application handles:

- File chunks
- Chunk persistence
- Transfer state
- Pause/resume
- Progressive availability
- Integrity
- Expiration
- Recovery

We are therefore operating at the application layer above TCP.

---

# 15. Go-Back-N / Selective Repeat Influence

Go-Back-N and Selective Repeat are useful conceptual references.

The MVP does **not** need to implement either ARQ protocol literally.

The useful application-level idea is:

```text
File chunks
    |
    v
Track completed chunks
    |
    v
Retry only missing/invalid chunks
```

This is closer to the useful part of Selective Repeat than retransmitting an entire file.

The exact recovery algorithm belongs in `TRANSFER-PROTOCOL.md`.

---

# 16. Real-Time Availability

The receiver needs to learn when more chunks become available.

Two candidates:

### Polling

```text
Receiver
   |
   | What is available?
   v
Backend
```

### SSE / WebSocket

```text
Backend
   |
   | Chunk 42 available
   v
Receiver
```

For the MVP, use the simplest mechanism that provides a reliable experience.

Real-time notifications must never be the source of truth. The receiver must always be able to query actual transfer state after reconnecting.

---

# 17. API Direction

Initial conceptual API:

```text
POST   /api/transfers
GET    /api/transfers/{id}
GET    /api/transfers/{id}/availability

PUT    /api/transfers/{id}/chunks/{index}

GET    /api/transfers/{id}/chunks/{index}

POST   /api/transfers/{id}/complete
```

These endpoint names are **not frozen**.

The final API belongs in `API.md`.

---

# 18. Browser Memory Rules

Never do:

```text
File
 |
 v
Read entire file
 |
 v
RAM
```

Instead:

```text
File
 |
 v
slice()
 |
 v
Chunk
 |
 v
Upload
 |
 v
Release chunk
 |
 v
Next chunk
```

This rule applies to both upload and download processing.

---

# 19. Parallelism

Parallel chunk transfer is **not required for the first correct implementation**.

First establish:

1. Correct chunk state
2. Pause/resume
3. Connection recovery
4. Progressive transfer
5. Integrity

Then benchmark limited parallelism.

If used:

```text
MAX_CONCURRENT_CHUNKS = N
```

Concurrency must be bounded.

Parallelism is an optimization, not the foundation of correctness.

---

# 20. Storage Layout

Possible object layout:

```text
transfers/
    {transfer-id}/
        chunks/
            000000
            000001
            000002
            ...
```

The exact storage-key strategy belongs in `STORAGE.md`.

---

# 21. Deployment Direction

Conceptual deployment:

```text
                    Internet
                       |
             +---------+---------+
             |                   |
          Browser A           Browser B
          Sender              Receiver
             |                   |
             +---------+---------+
                       |
                    Backend
                       |
             +---------+---------+
             |                   |
          PostgreSQL          Object Storage
          metadata               chunks
```

The deployment provider is **not locked yet**.

It must support:

- HTTPS
- Public access
- Environment variables/secrets
- Suitable request limits
- Suitable timeouts
- Persistent metadata
- Persistent object storage
- Chunked/large transfer traffic

---

# 22. Cost Rules

The project should remain practical on free tiers during development and small-scale testing.

Rules:

- Prefer free tiers
- Delete expired transfers
- Avoid duplicate chunk storage
- Avoid unnecessary database writes
- Avoid unnecessary polling
- Do not introduce Redis/Kafka/etc. without a demonstrated requirement
- Do not add paid infrastructure simply for convenience

Free tier does **not** mean unlimited free usage.

---

# 23. Technologies Explicitly Not Needed Initially

Do not introduce these unless a real requirement appears:

- Kafka
- RabbitMQ
- Redis
- Kubernetes
- Microservices
- GraphQL
- Elasticsearch
- Service mesh
- Distributed databases
- Custom TCP/UDP protocols
- WebRTC/P2P
- Automatic compression
- Server-side media processing

Scalability should come from clean responsibilities and stateless application design, not artificial infrastructure complexity.

---

# 24. Testing

## Backend

- JUnit
- Spring Boot Test
- Integration tests
- Storage abstraction tests

## Frontend

- Vitest
- React Testing Library

## End-to-end

Playwright may be added for:

- Send flow
- Receive flow
- Sender pause/resume
- Receiver pause/resume
- Progressive transfer
- Connection recovery

The exact testing plan belongs in `TESTING.md`.

---

# 25. Health and Observability

Expose:

```text
GET /health
```

returning HTTP 200 when the application is healthy.

Log/measure at minimum:

- Transfer creation
- Chunk failures
- Chunk retries
- Transfer completion
- Expiration/deletion
- Storage failures
- Request latency
- Error rates

Detailed observability can be added later.

---

# 26. Environment Configuration

Secrets must never be committed.

Examples:

```text
DATABASE_URL
STORAGE_ENDPOINT
STORAGE_ACCESS_KEY
STORAGE_SECRET_KEY
STORAGE_BUCKET
CORS_ALLOWED_ORIGINS
TRANSFER_EXPIRATION
```

Local development should use `.env` or equivalent configuration.

---

# 27. Repository Direction

The repository should keep clear boundaries:

```text
Frontend
Backend
Transfer domain
Storage
Persistence
Infrastructure
```

Do not mechanically duplicate Controller/Service/Repository layers everywhere.

The architecture should make these responsibilities obvious:

- What decides transfer state?
- What stores chunks?
- What exposes HTTP?
- What verifies integrity?
- What handles expiration?

The exact structure belongs in `ARCHITECTURE.md`.

---

# 28. Main Technical Risks

## Progressive download

Object storage commonly works around completed objects, while this product needs receivers to consume chunks before the complete file exists.

This is one of the central architecture problems.

## Browser behavior

Large transfers can be affected by:

- Memory
- Browser lifecycle
- Connection handling
- Background execution
- Tab closure

## Storage cost

A 20 GB test file exceeds many free storage allowances.

## Deployment limits

Reverse proxies and hosting platforms may impose:

- Body-size limits
- Timeouts
- Connection-duration limits
- Bandwidth restrictions

## Resume correctness

Incorrect state handling can cause:

- Missing chunks
- Duplicate chunks
- Corruption
- Incorrect progress
- Restarting from zero

These must be addressed before UI polish.

---

# 29. Core Engineering Principle

Separate:

```text
Transfer correctness
```

from:

```text
Transfer presentation
```

For example, the frontend may display:

```text
72%
```

but the backend/storage state determines what is actually available.

The UI is never the source of truth.

---

# 30. Locked vs Provisional

## Locked technical direction

- React + TypeScript
- Vite
- Java + Spring Boot
- Maven
- PostgreSQL for metadata
- Object storage for chunks
- Chunk-based transfer
- Browser-side incremental processing
- HTTP/HTTPS
- Application-level resumability
- Sender upload pause/resume
- Receiver download pause/resume
- Progressive transfer
- Chunk integrity verification
- Temporary storage
- GitHub Actions CI
- Automated testing
- Storage abstraction

## Provisional

- Cloudflare R2 as final storage provider
- Exact deployment provider
- Exact chunk size
- Exact PostgreSQL schema
- Exact API endpoints
- Polling vs SSE/WebSocket
- Direct tus usage vs custom protocol
- Parallel-transfer strategy
- Exact expiration duration
- Exact checksum implementation

These should be finalized after architecture/repository research.

---

# 31. Documentation Map

The project documentation should eventually be:

```text
MVP.md
    |
    | What are we building?
    v
TECHNICAL.md
    |
    | What technologies are we using?
    v
ARCHITECTURE.md
    |
    | How is the system structured?
    v
TRANSFER-PROTOCOL.md
    |
    | How do chunks, state, pause/resume,
    | recovery and progressive transfer work?
    v
API.md
    |
    | What backend interfaces exist?
    v
STORAGE.md
    |
    | How are chunks stored/deleted?
    v
TESTING.md
    |
    | How do we prove it works?
    v
DEPLOYMENT.md
    |
    | How do we deploy/operate it?
    v
SECURITY.md
    |
    | How do we protect transfers?
```

Each document should answer a different question and avoid unnecessary duplication.

---

# 32. Final Technical Definition

> A web application with a React/TypeScript client and Java/Spring Boot backend that treats a file as a sequence of independently tracked chunks, persists those chunks in object storage, tracks transfer metadata in PostgreSQL, allows the sender to pause/resume uploading and the receiver to pause/resume downloading independently, recovers from interrupted connections using persisted transfer state, verifies chunk integrity, uses temporary storage, and exposes already-available chunks to receivers before the complete file has finished uploading.

The implementation must remain simple enough to build and deploy rapidly while preserving these engineering properties.
