# Large-File Transfer — AGENTS.md

## Purpose

This file is the working guide for coding agents contributing to this repository.

The project is a storage-backed, modular-monolith large-file transfer system. Read the project documentation before changing architecture or protocol behavior.

## Documentation Order

Before implementing a feature, use the relevant documents as the source of truth:

1. `MVP.md` — what the product must do
2. `TECHNICAL.md` — technology and technical direction
3. `ARCHITECTURE.md` — internal system structure and responsibilities
4. `TRANSFER-PROTOCOL.md` — chunk protocol, state transitions, retries, and recovery
5. `API.md` — HTTP contracts
6. `STORAGE.md` — storage behavior and persistence rules
7. `TESTING.md` — required verification
8. `DEPLOYMENT.md` — deployment and operational constraints
9. `SECURITY.md` — security requirements
10. `RULES.md` — non-negotiable engineering rules

If implementation conflicts with documentation, stop and resolve the conflict instead of silently choosing a different design.

## Architecture

Use a modular monolith.

Keep responsibilities separated:

- API layer — HTTP concerns only
- Application layer — use-case orchestration
- Domain layer — transfer/chunk business rules
- Infrastructure layer — PostgreSQL, object storage, filesystem, hashing, scheduling, provider adapters
- Configuration — application configuration and environment wiring

Do not introduce microservices for MVP functionality.

Do not put transfer business logic inside controllers or React components.

## Backend

Backend stack:

- Java 17
- Spring Boot
- Maven
- PostgreSQL

PostgreSQL stores metadata and state.

Object storage stores chunk bytes.

Use a storage abstraction rather than coupling application logic directly to R2 or another provider.

## Frontend

Frontend stack:

- React
- TypeScript
- Vite

Keep protocol behavior inside dedicated managers/hooks/services rather than inside UI components.

The main conceptual client components are:

- API client
- Upload Manager
- Download Manager
- Transfer state
- Local persistence

UI components should consume state and invoke operations such as pause/resume.

## Transfer Model

Treat a file as independently addressable chunks.

The backend is authoritative for:

- Transfer existence
- Expiration
- Available chunks
- Stored chunk metadata
- Integrity state
- Completion

Never treat a client-side progress percentage as proof that server-side data exists.

## Upload

Upload incrementally.

Never load the entire file into browser memory.

A chunk becomes available only after:

1. Bytes are received.
2. Integrity is verified.
3. Bytes are persisted.
4. Metadata/state is safely committed.

Chunk uploads must be retryable and idempotent.

Conflicting content for the same `(transfer_id, chunk_index)` must never silently replace valid data.

## Download

Receivers may download available chunks before the sender completes the transfer.

Do not require the complete file to exist before downloading begins.

Receiver progress must survive transient connection loss.

A chunk must not be marked locally complete until its bytes have been fully and successfully persisted.

## Pause / Resume

Sender pause controls new uploads.

Receiver pause controls new downloads.

Neither operation deletes completed progress.

Sender and receiver operation must remain independent.

## Recovery

On reconnection, reconcile against authoritative server state.

Do not blindly restart from chunk zero.

Handle lost responses, duplicate requests, interrupted transfers, and stale client state deterministically.

## Storage

Use one independently readable storage object per chunk.

Conceptual key:

`transfers/{transferId}/chunks/{chunkIndex}`

Do not expose storage-provider operations directly through the public API.

Storage failures and metadata/storage inconsistencies must be surfaced explicitly.

## Database

The database must enforce one logical chunk per transfer/index.

Use a database-level unique constraint on:

`(transfer_id, chunk_index)`

Do not rely only on application-level check-then-insert logic for concurrency safety.

## Security

There is no user-account system in the MVP.

Transfer access is capability-based.

Use unpredictable public transfer credentials, HTTPS, expiration, validation, safe storage access, and appropriate rate limiting.

Never expose sensitive information through storage keys or predictable identifiers.

## Testing

Implement tests around invariants and failure scenarios, not only happy paths.

At minimum cover:

- Chunk upload
- Chunk download
- Resume
- Pause/resume
- Connection loss
- Idempotent retry
- Conflicting upload
- Checksum mismatch
- Progressive download
- Caught-up receiver
- Expiration
- Storage failures
- Metadata/storage inconsistency
- Concurrent same-chunk uploads
- Single-chunk files
- Non-divisible file sizes
- Sender abandonment before explicit completion

## Coding Workflow

Before changing code:

1. Identify the relevant document.
2. Understand the existing implementation.
3. Make the smallest coherent change.
4. Preserve existing invariants.
5. Add/update tests.
6. Run the relevant test/build commands.
7. Report what changed and what was verified.

Do not rewrite working components merely for stylistic preference.

Do not add dependencies unless they solve a demonstrated requirement.

Do not introduce infrastructure such as Redis, Kafka, RabbitMQ, Kubernetes, WebRTC, or P2P without an explicitly approved requirement.

## Documentation

When implementation decisions change a documented contract, update the appropriate document.

Do not duplicate detailed protocol/API/storage definitions across unrelated documents.

Keep documentation and implementation consistent.
