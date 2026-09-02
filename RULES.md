# Large-File Transfer — RULES.md

## Status

ACTIVE — Non-Negotiable Engineering Rules

These rules apply to all implementation work unless the project documentation is deliberately revised first.

## 1. Core Product Rule

The system is a chunk-based file transfer service.

Do not turn it into a generic storage platform, collaboration system, chat application, P2P system, or custom transport protocol.

## 2. Backend Authority

The backend is authoritative for server-side transfer state.

The frontend must never assume that a chunk exists merely because an upload request was sent or a progress counter advanced.

## 3. Chunk Availability

A chunk is `AVAILABLE` only after:

- Data has been received
- Integrity has been verified
- Data has been durably persisted
- Corresponding metadata/state has been safely committed

Never expose a chunk as available earlier.

## 4. One Logical Chunk

For a given transfer:

`(transfer_id, chunk_index)`

identifies exactly one logical chunk.

This must be enforced at the database level.

Concurrent requests must not create duplicate logical chunks or silently replace valid content.

## 5. Idempotent Upload

Retrying an already-valid identical chunk must be safe.

Conflicting content for an existing chunk index must be rejected or handled by the explicitly defined conflict policy.

Never silently overwrite a valid chunk with different bytes.

## 6. Integrity

Checksum verification is mandatory at the application level.

A checksum mismatch is a failed chunk operation, not a successful upload/download.

The client must not mark a partially received or corrupted chunk as complete.

## 7. Progressive Transfer

The receiver may download chunks while the sender is still uploading.

Do not require `COMPLETE` before exposing already-available chunks.

Caught-up is not completion.

If the receiver has downloaded all currently available chunks but the transfer is not complete, it must wait for more data.

## 8. Pause Independence

Sender pause must not stop the receiver from consuming already-available chunks.

Receiver pause must not stop the sender from uploading.

Pause never deletes completed progress.

## 9. Connection Recovery

Connection loss must not reset completed progress.

After reconnecting:

- Sender reconciles with server chunk state.
- Receiver reconciles with local progress and server availability.

Never blindly restart a large transfer from zero.

## 10. Memory

Never load an entire multi-GB file into browser memory.

Upload incrementally using file slices.

Download/assembly must use a browser storage/output mechanism appropriate for multi-GB data.

## 11. Storage Separation

PostgreSQL stores metadata/state.

Object storage stores chunk bytes.

Do not store multi-GB transfer contents inside PostgreSQL.

Application/domain code must not depend directly on a specific storage provider.

## 12. Storage Consistency

Metadata and object bytes must be treated as two related pieces of state.

If metadata says a chunk is available but the object cannot be retrieved, treat this as a storage failure/inconsistency, not as an ordinary unavailable chunk.

If an object exists without valid corresponding metadata, it must not become downloadable merely because the object exists.

## 13. Expiration

Expired transfers must not be accessible.

Physical cleanup may happen after logical expiration.

Cleanup must be idempotent and must remove:

1. Chunk objects
2. Chunk metadata
3. Transfer metadata

Do not allow cleanup races to expose deleted/expired data.

## 14. Access Control

There are no user accounts in the MVP.

Transfer access is capability-based.

Public transfer credentials must be unpredictable.

Do not use sequential database IDs as access credentials.

Never expose storage-provider credentials to browsers.

## 15. API Boundary

Public APIs expose transfer operations, not raw storage-provider operations.

Controllers handle HTTP concerns.

Controllers must not become the location for transfer business rules.

## 16. Frontend Boundary

React components are presentation/UI.

Chunk scheduling, retries, pause/resume, recovery, checksum handling, and protocol behavior belong in dedicated client-side managers/services/hooks.

Do not implement the protocol by scattering request logic across UI components.

## 17. Notifications

Polling/SSE/WebSocket notifications are never authoritative.

They are only signals that state may have changed.

After reconnecting or detecting inconsistency, query authoritative transfer state.

## 18. Concurrency

Correctness comes before parallelism.

Start with sequential or tightly bounded concurrency.

Never introduce unbounded chunk concurrency.

## 19. No Premature Infrastructure

Do not add:

- Kafka
- RabbitMQ
- Redis
- Kubernetes
- Microservices
- GraphQL
- Elasticsearch
- Service mesh
- Distributed databases
- WebRTC
- P2P
- Custom TCP/UDP

unless a demonstrated requirement and explicit architectural decision justify them.

## 20. No Transport Reinvention

HTTP/HTTPS and the underlying network stack handle network-level transport reliability.

The application tracks file-level chunk state.

Do not implement TCP-like packet protocols inside the application.

## 21. Error Semantics

Keep these concepts distinct:

- Transfer does not exist
- Transfer is expired
- Chunk does not yet exist
- Checksum mismatch
- Chunk conflict
- Storage failure
- Invalid request/state

Do not collapse different failure causes into misleading success states.

## 22. Testing Rule

Every important invariant should have a corresponding test.

Important edge cases include:

- Empty/invalid requests
- Single-chunk files
- Files smaller than the chunk size
- Files exactly divisible by chunk size
- Files not divisible by chunk size
- Duplicate uploads
- Concurrent uploads
- Conflicting uploads
- Lost upload responses
- Connection loss
- Pause/resume
- Progressive download
- Receiver caught-up state
- Receiver pause/resume
- Storage/object mismatch
- Expiration
- Sender abandonment before explicit completion

## 23. Change Discipline

Do not change an architectural invariant casually.

If implementation reveals that a locked decision is technically invalid:

1. Stop.
2. Document the evidence.
3. Propose the change.
4. Update the affected specification(s).
5. Then implement against the new contract.

Do not silently drift from the specification.

## 24. Simplicity

Prefer the simplest design that satisfies the locked requirements.

Do not optimize prematurely.

Do not add abstraction layers that have no meaningful responsibility.

Do not add technology merely because it is available.

## 25. Definition of Done

A feature is not done because the UI appears to work.

It is done when:

- The implementation matches the relevant specification.
- Failure/recovery behavior is handled.
- Relevant tests pass.
- Important invariants remain true.
- No accidental architectural coupling was introduced.
- Documentation is updated if the contract changed.
