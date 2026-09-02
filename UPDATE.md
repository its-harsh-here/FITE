# Development Update

**Date:** 2026-09-01  
**Status:** Technical spike completed

## 1. Summary

The initial large-file transfer spike has been completed and the core chunk-based transfer approach has been validated locally.

The spike proved:

- Chunked file upload
- Persistent chunk storage
- Individual chunk download
- Exact file reconstruction
- SHA-256 integrity verification
- Interrupted upload state preservation
- Resume from the next missing chunk without re-uploading completed chunks

This is a technical spike, not the final production implementation.

## 2. Environment

Current local setup:

- Server: Node.js + Express
- Client: browser-based frontend
- Server: `http://localhost:3000`
- Client: `http://localhost:5173`
- Storage during spike: local filesystem

The laptop was used as the server during testing.

## 3. Chunking Test

Tested chunk size:

**8 MiB = 8,388,608 bytes**

Chunks were stored individually on the server:

```text
files/
└── chunks/
    ├── chunk-0.bin
    ├── chunk-1.bin
    ├── chunk-2.bin
    └── ...
```

## 4. Download Test

Individual chunks were successfully downloaded from the server.

Tested endpoints:

```text
GET /chunk/0
GET /chunk/1
GET /chunk/2
```

## 5. Reconstruction + Integrity Test

A test PDF was reconstructed from stored chunks and compared against the original.

SHA-256 of original:

```text
20A2174C9D6C30364536E76A642BB151A459176C74EBACCDDFA529BD734CEAC5
```

SHA-256 of reconstructed file:

```text
20A2174C9D6C30364536E76A642BB151A459176C74EBACCDDFA529BD734CEAC5
```

**Result: PASS — byte-for-byte identical.**

## 6. Resume Test

A 32 MiB test file was created.

With an 8 MiB chunk size:

```text
32 MiB
  ↓
4 chunks

chunk 0
chunk 1
chunk 2
chunk 3
```

Test sequence:

```text
Upload chunk 0
      ↓
Upload chunk 1
      ↓
Simulated interruption
      ↓
Verify chunks 0 and 1 remain
      ↓
Resume from chunk 2
      ↓
Upload chunk 3
      ↓
Reconstruct file
      ↓
Compare SHA-256
```

Chunks 0 and 1 were not re-uploaded during the resume portion.

The reconstructed file's SHA-256 matched the original.

**Result: PASS**

## 7. What We Have Actually Proven

The following basic model works:

```text
File
 ↓
Chunk
 ↓
Persist chunk
 ↓
Retrieve chunk
 ↓
Reconstruct
 ↓
Verify integrity
```

We have also proven the basic foundation for resumable upload:

```text
Completed chunks
      ↓
Remain persisted
      ↓
Resume from missing chunk
```

## 8. What Has NOT Been Proven Yet

The following are still unimplemented or unvalidated:

- tus integration
- Production resumable-upload protocol
- Browser-side large-file persistence
- File System Access API strategy
- IndexedDB strategy
- Receiver-side resumable download
- Receiver pause/resume
- Sender pause/resume UI
- Progressive downloading while upload is active
- Automatic connection-loss recovery
- Multiple receivers
- PostgreSQL transfer metadata/state
- Production object storage
- Transfer expiration
- Cleanup jobs
- Rate limiting
- Production security
- Production deployment
- 20 GB end-to-end browser test

Do not treat the spike as proof that these features already work.

## 9. Important Technical Findings

### Individual chunk storage works

The spike supports the architecture direction of treating each chunk as independently persisted data.

This makes chunk availability explicit and is compatible with progressive transfer.

### Reconstruction is reliable

The chunk sequence can be reconstructed into the exact original file.

### Resume can avoid duplicate upload

The basic test successfully resumed from chunk 2 after chunks 0 and 1 had already been persisted.

This validates the fundamental idea of tracking completed chunks.

## 10. tus

`tus` has **not** been integrated yet.

It remains part of the planned technical evaluation for resumable uploads.

The spike deliberately used simple HTTP chunk endpoints first to validate the underlying chunk-storage and resume behavior independently of a resumable-upload protocol.

## 11. Important Risks Still Remaining

The biggest unresolved areas are:

1. Browser-side persistence for very large downloads
2. Progressive download coordination
3. Authoritative transfer/chunk state
4. Sender and receiver pause/resume
5. Connection-loss recovery
6. Transfer-code/link security
7. Production object storage

These need to be solved before calling the transfer system production-ready.

## 12. Temporary Test Files

The spike created temporary test artifacts, including:

```text
files/chunks/
files/reconstructed.bin
files/resume-test.bin
files/resume-reconstructed.bin
```

These are test artifacts only and should be cleaned up before the repository is finalized.

## 13. Handoff

The core technical spike is complete.

The next implementation should move from the local proof-of-concept toward the actual system design.

Recommended documentation order:

1. `TECH_STACK.md`
2. `ARCHITECTURE.md`
3. `TRANSFER_PROTOCOL.md`
4. `API.md`
5. `SECURITY.md`
6. `TESTING.md`

`MVP.md` remains frozen and defines the product boundary.
