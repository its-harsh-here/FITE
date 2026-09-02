# Large-File Transfer — Deployment Specification

**Status:** ACTIVE — Deployment Baseline  
**Document:** `DEPLOYMENT.md`  
**Version:** 1.0  
**Depends on:** `MVP.md`, `TECHNICAL.md`, `ARCHITECTURE.md`, `TRANSFER-PROTOCOL.md`, `API.md`, `STORAGE.md`, `TESTING.md`

---

# 1. Purpose

This document defines how the Large-File Transfer MVP is built, configured, deployed, operated, and cleaned up in a real environment.

The deployment must support:

- React + TypeScript frontend
- Java 17 + Spring Boot backend
- PostgreSQL metadata storage
- Object storage for chunks
- HTTPS
- Large chunk requests
- Resumable transfers
- Progressive transfer
- Temporary transfer expiration
- Health checks
- Environment-based configuration
- Automated build and test through GitHub Actions

The deployment should remain simple and inexpensive during MVP development.

---

# 2. Deployment Architecture

The production deployment follows:

```text
                    Internet
                       |
              +--------+--------+
              |                 |
          Sender Browser   Receiver Browser
              |                 |
              +--------+--------+
                       |
                     HTTPS
                       |
                       v
              Frontend / Backend
                       |
              +--------+--------+
              |                 |
              v                 v
        Spring Boot API     PostgreSQL
              |
              v
        ChunkStorage
              |
              v
        Object Storage
```

The frontend and backend may be hosted separately.

The exact hosting providers are not permanently locked by this document.

---

# 3. Deployment Components

## 3.1 Frontend

The frontend is a production React application built with Vite.

Responsibilities:

- Render transfer UI
- Upload/download files
- Communicate with backend API
- Maintain sender state
- Maintain receiver state
- Maintain local receiver persistence
- Display transfer progress

The frontend should be served over HTTPS.

---

## 3.2 Backend

The backend is one Spring Boot application.

Responsibilities:

- Transfer API
- Transfer state
- Chunk upload/download
- Integrity verification
- Access control
- Expiration
- Cleanup
- Health endpoint

The backend should remain stateless with respect to active browser execution state.

Persisted transfer state belongs in PostgreSQL/object storage.

---

## 3.3 PostgreSQL

PostgreSQL stores:

- Transfer metadata
- Chunk metadata
- Checksums
- Timestamps
- Expiration state
- Other persistent transfer state

PostgreSQL must not be used for storing large chunk payloads.

---

## 3.4 Object Storage

Object storage stores individual chunk objects.

Required capability:

```text
PUT chunk
GET chunk
EXISTS chunk
DELETE chunk
DELETE transfer chunks
```

The deployment must preserve the one-object-per-chunk architecture.

Example:

```text
transfers/{transferId}/chunks/{chunkIndex}
```

---

# 4. Hosting Requirements

The selected hosting environment must support:

- HTTPS
- Java 17
- Spring Boot
- PostgreSQL connectivity
- Environment variables/secrets
- Persistent external object storage
- Sufficient request body size
- Sufficient request timeout
- Sufficient response timeout
- Required bandwidth
- Scheduled/background cleanup
- Public API access
- CORS configuration

A platform that terminates large requests prematurely is unsuitable even if it can technically run Spring Boot.

---

# 5. Chunk Request Limits

The backend must explicitly configure request limits around the selected chunk size.

For every deployment:

```text
maximum request body size > configured chunk size
```

There should also be sufficient timeout for a slow upload/download of one chunk.

The exact limits depend on:

- Chunk size
- Expected user connection speed
- Hosting provider
- Reverse proxy
- Backend configuration

These values must be tested before production deployment.

A 32 MB chunk is not useful if the deployment layer rejects requests above 10 MB.

---

# 6. HTTPS

HTTPS is mandatory outside local development.

HTTPS protects:

- File contents in transit
- Transfer credentials
- Share links/codes
- API requests
- Chunk checksums

The deployment must reject or redirect insecure HTTP traffic where the hosting environment permits it.

---

# 7. CORS

If frontend and backend are hosted on different origins, the backend must explicitly configure CORS.

Allowed origins should be environment-specific.

Example:

```text
Development:
http://localhost:5173

Production:
https://<frontend-domain>
```

Do not use unrestricted production CORS unless there is a demonstrated requirement.

Allowed methods should include only those required by the API.

---

# 8. Environment Configuration

Production configuration must be supplied through environment variables or the hosting provider's secret/configuration system.

Examples:

```text
DATABASE_URL
DATABASE_USERNAME
DATABASE_PASSWORD

STORAGE_ENDPOINT
STORAGE_ACCESS_KEY
STORAGE_SECRET_KEY
STORAGE_BUCKET
STORAGE_REGION

CORS_ALLOWED_ORIGINS

TRANSFER_EXPIRATION
MAX_CHUNK_SIZE
```

Secrets must never be committed to Git.

---

# 9. Database Configuration

Production PostgreSQL must provide:

- Persistent storage
- TLS where required by the provider
- Connection credentials
- Connection limits suitable for the backend
- Automated backups where available

Database migrations/schema changes must be handled deliberately.

The production environment should not depend on destructive automatic schema recreation.

---

# 10. Object Storage Configuration

The production storage bucket must be private by default.

The backend controls access to chunk objects.

Clients should not receive unrestricted bucket credentials.

Conceptually:

```text
Browser
   |
   v
Backend
   |
   v
Private Object Storage
```

Storage credentials belong only to the backend.

---

# 11. Storage Cleanup

Expired transfers must eventually be removed.

Cleanup order:

```text
Expired transfer detected
        |
        v
Reject new access
        |
        v
Delete chunk objects
        |
        v
Delete chunk metadata
        |
        v
Delete transfer metadata
```

Cleanup operations must be idempotent.

If cleanup fails partially, a later cleanup run must be able to continue safely.

---

# 12. Cleanup Scheduling

The backend should run a scheduled cleanup process.

The cleanup frequency should balance:

- Storage cost
- Database load
- Expiration accuracy

For MVP, hourly cleanup is a reasonable starting point unless deployment constraints require another interval.

Expiration is authoritative even if physical deletion has not happened yet.

Therefore:

```text
expiresAt < now
```

means the transfer is inaccessible immediately, regardless of whether cleanup has already deleted its objects.

---

# 13. Health Endpoint

The backend exposes:

```text
GET /health
```

A healthy application returns HTTP 200.

The health endpoint should be lightweight.

It should not perform expensive storage operations or database scans on every request.

If the deployment platform supports separate liveness/readiness checks, those can be introduced later.

---

# 14. Logging

The backend should log enough information to diagnose transfer failures.

Important events:

- Transfer creation
- Chunk upload failure
- Chunk checksum mismatch
- Chunk conflict
- Chunk download failure
- Storage failure
- Transfer completion
- Transfer expiration
- Cleanup failure
- Unexpected application errors

Do not log:

- File contents
- Storage secrets
- Database passwords
- Full private credentials
- Sensitive share credentials unnecessarily

---

# 15. Metrics

MVP observability should track, where practical:

```text
request count
request latency
error count
chunk upload failures
chunk download failures
checksum failures
storage failures
transfer completions
expired transfers
cleanup failures
```

Detailed distributed tracing is not required for the MVP.

---

# 16. Deployment Build

The backend production build should:

```text
Source
  |
  v
Maven build
  |
  v
Tests
  |
  v
Spring Boot artifact
  |
  v
Deploy
```

The frontend build should:

```text
Source
  |
  v
npm install
  |
  v
Tests
  |
  v
Vite production build
  |
  v
Deploy static assets
```

The exact package-manager commands are repository decisions and should remain consistent with the actual repository configuration.

---

# 17. CI/CD

GitHub Actions should run on relevant pushes/pull requests.

Minimum checks:

```text
Backend
  -> build
  -> unit tests
  -> integration tests

Frontend
  -> build
  -> tests
```

A deployment should not proceed when required CI checks fail.

Automatic production deployment may be enabled after the hosting provider is finalized.

---

# 18. Deployment Environments

At minimum:

```text
Local
Production
```

A staging environment may be introduced if deployment testing becomes complex enough to justify it.

The MVP does not require multiple production-like environments purely for process.

---

# 19. Local Development

Local development should support:

```text
React/Vite
     |
     v
Spring Boot
     |
     +--> Local PostgreSQL
     |
     +--> LocalStorage implementation
```

The local storage adapter should implement the same abstraction as production object storage.

This allows transfer logic to be tested without requiring cloud storage for every development run.

---

# 20. Production Storage Adapter

Production uses the configured object-storage implementation.

The application should not contain provider-specific logic throughout the transfer domain.

Instead:

```text
Transfer Application
       |
       v
ChunkStorage
       |
       v
Production Storage Adapter
       |
       v
Object Storage Provider
```

This preserves the architecture defined in `STORAGE.md`.

---

# 21. Domain and Provider Separation

The deployment configuration must not change domain behavior.

Changing:

```text
LocalStorage
```

to:

```text
R2Storage
```

should not require changing:

- Transfer state rules
- Chunk state rules
- Pause/resume logic
- Recovery logic
- Progressive transfer logic
- Integrity rules

Only infrastructure configuration/adapter behavior should change.

---

# 22. Database Availability

The application must handle temporary database failures without falsely reporting successful transfer operations.

For example:

```text
Chunk received
    |
    v
Database update fails
    |
    v
Request must not report success
```

The system must preserve the invariant that authoritative metadata reflects actual successful persistence.

The exact transaction/retry behavior belongs to the implementation.

---

# 23. Storage Availability

The same principle applies to object storage.

```text
Upload request
    |
    v
Storage write
    |
    +--> success -> continue
    |
    +--> failure -> report failure
```

A storage failure must never be converted into a successful chunk upload.

If metadata and object storage become inconsistent, the system must surface the appropriate storage failure and allow recovery according to the rules in `STORAGE.md`.

---

# 24. Deployment Failure Recovery

If a backend instance restarts:

```text
Backend restart
      |
      v
PostgreSQL state remains
      |
      v
Object storage remains
      |
      v
Clients reconnect
      |
      v
Reconcile transfer state
      |
      v
Continue
```

No active transfer should depend on in-memory backend state for correctness.

This is a key reason the backend remains stateless.

---

# 25. Browser Refresh / Reconnection

A sender refreshing the page should be able to recover using the transfer identifier and server state.

A receiver refreshing the page should recover according to the local persistence mechanism defined by the frontend implementation.

The deployment must not assume browser tabs remain continuously connected.

---

# 26. Domain and Routing

Production should expose a stable HTTPS API origin.

Conceptually:

```text
https://app.example.com
https://api.example.com
```

or a single-origin deployment:

```text
https://app.example.com
    |
    +--> frontend
    |
    +--> /api/*
```

Either model is acceptable.

The final routing arrangement depends on the hosting provider.

---

# 27. CDN Considerations

A CDN may be used for frontend static assets.

However, chunk API traffic should not be routed through a caching layer that can incorrectly cache:

- Chunk availability
- Transfer metadata
- Chunk responses
- Error responses

Dynamic transfer endpoints must have appropriate cache-control behavior.

The MVP does not require a CDN for chunk traffic.

---

# 28. Rate Limiting

Because the MVP uses bearer-style transfer access without accounts, public endpoints must be protected against obvious abuse.

At minimum consider rate limits for:

- Transfer lookup
- Share-code lookup
- Chunk download
- Chunk upload
- Availability polling

The exact limits depend on deployment capacity and are finalized in `SECURITY.md`.

---

# 29. Bandwidth and Cost

Object storage and network traffic are potentially the largest operational costs.

The deployment should therefore:

- Delete expired transfers
- Avoid unnecessary duplicate uploads
- Avoid excessive polling
- Avoid unbounded retries
- Avoid unnecessary chunk duplication
- Monitor storage usage
- Monitor request volume

A 20 GB test transfer is a benchmark target, not an assumption that production will remain free at that scale.

---

# 30. Large-File Deployment Testing

Before declaring production deployment successful, test at minimum:

```text
Small file
Single-chunk file
Multi-chunk file
Large test file
Sender pause/resume
Receiver pause/resume
Sender connection loss
Receiver connection loss
Progressive transfer
Checksum mismatch
Chunk retry
Expired transfer
Backend restart
Storage failure
Database failure
```

The testing requirements are defined in `TESTING.md`.

---

# 31. Production Smoke Test

After deployment:

```text
1. Open frontend over HTTPS
2. Create transfer
3. Upload a small file
4. Open receiver using share access
5. Download file
6. Verify downloaded content
7. Check backend health
8. Confirm chunk storage
9. Confirm expiration configuration
```

A larger-file test should then verify the real chunk-transfer path.

---

# 32. Rollback

A deployment must be reversible.

At minimum retain:

- Previous frontend build
- Previous backend artifact/image
- Database migration history
- Configuration version/history where supported

A failed application deployment must not require deleting transfer data.

Database migrations must be designed so that application rollback does not unnecessarily destroy persistent transfer state.

---

# 33. Secret Management

Production secrets must be stored using the hosting provider's secret manager/environment configuration.

Never commit:

```text
.env
credentials
access keys
private keys
database passwords
storage secrets
```

If a secret is accidentally committed, rotate it rather than merely deleting it from Git history.

---

# 34. Security Baseline

Production deployment must enforce:

- HTTPS
- Private object storage
- Unpredictable transfer capabilities
- CORS restrictions
- Input validation
- Rate limiting
- Expiration
- Safe error responses
- No directory listing
- No storage credentials in frontend code

Detailed security rules belong in `SECURITY.md`.

---

# 35. Scaling Direction

The MVP does not require horizontal scaling.

If traffic grows, the architecture should permit:

```text
             Load Balancer
                   |
          +--------+--------+
          |                 |
     Spring Boot A     Spring Boot B
          |                 |
          +--------+--------+
                   |
          +--------+--------+
          |                 |
      PostgreSQL       Object Storage
```

This is possible because persistent transfer state is external to application memory.

Shared infrastructure must therefore remain authoritative.

---

# 36. What We Are Not Deploying

Do not introduce:

- Kubernetes
- Service mesh
- Kafka
- RabbitMQ
- Redis
- Custom orchestration
- Dedicated distributed coordination systems

unless real measured requirements justify them.

The MVP should remain a small deployable system.

---

# 37. Deployment Checklist

Before production:

```text
[ ] HTTPS configured
[ ] Frontend production build works
[ ] Backend production build works
[ ] PostgreSQL configured
[ ] Object storage configured
[ ] Storage bucket private
[ ] Environment secrets configured
[ ] CORS configured
[ ] Chunk request limits configured
[ ] Request timeouts verified
[ ] Health endpoint verified
[ ] Cleanup scheduler enabled
[ ] Expiration verified
[ ] Rate limiting configured
[ ] CI passes
[ ] Large-file smoke test passes
[ ] Resume test passes
[ ] Progressive transfer test passes
[ ] Integrity test passes
[ ] Failure recovery tested
```

---

# 38. Final Deployment Definition

The MVP is deployed as a simple HTTPS-based web application consisting of:

```text
React/Vite frontend
        |
        v
Spring Boot backend
        |
        +--> PostgreSQL
        |
        +--> Object Storage
```

The backend remains stateless with respect to active browser execution.

Persistent transfer state lives in PostgreSQL and object storage.

Individual chunks are independently stored and retrieved.

Transfers expire and are cleaned up automatically.

The deployment must support large chunk requests, connection recovery, progressive transfer, integrity verification, and temporary storage without introducing unnecessary infrastructure.

The final hosting providers may change, but they must preserve the architectural invariants established by the preceding specifications.
