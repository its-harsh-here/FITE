# Large-File Transfer — Security Specification

**Status:** ACTIVE — Security Baseline  
**Document:** `SECURITY.md`  
**Version:** 1.0  
**Depends on:** `MVP.md`, `TECHNICAL.md`, `ARCHITECTURE.md`, `TRANSFER-PROTOCOL.md`, `API.md`, `STORAGE.md`, `TESTING.md`, `DEPLOYMENT.md`

---

# 1. Purpose

This document defines the security model for the no-account MVP.

The system does not have user authentication. Security therefore focuses on:

- Transfer capability security
- Transport security
- Input validation
- Access control
- Rate limiting
- Storage isolation
- Resource exhaustion
- Abuse prevention
- Expiration
- Secret management
- Safe error handling

Security controls must preserve the core transfer model without introducing unnecessary infrastructure.

---

# 2. Security Model

The MVP uses **transfer-based authorization**.

A transfer is accessed through a public capability consisting of an unpredictable identifier/token.

Conceptually:

```text
Random capability
       |
       v
Transfer access
```

Possession of the valid capability grants access according to the transfer policy.

There are no user accounts, passwords, sessions, or social identities.

Therefore:

> The security of a transfer capability is equivalent to the security of the transfer itself.

---

# 3. Threat Model

The system should defend against at least:

- Guessing transfer identifiers
- Brute-force access attempts
- Unauthorized transfer enumeration
- Malicious oversized requests
- Invalid chunk indexes
- Chunk-conflict attempts
- Checksum abuse
- Excessive upload/download requests
- Storage exhaustion
- Database exhaustion
- Expired-transfer access
- Malicious filenames/content types
- Leaked server credentials
- Cross-origin abuse
- Replay/retry abuse
- Information leakage through errors

The MVP does not attempt to solve every possible Internet threat.

---

# 4. HTTPS

All production traffic must use HTTPS.

This protects:

- Transfer capabilities
- File contents while in transit
- Checksums
- API requests
- API responses

HTTP may be permitted only for local development where appropriate.

Production clients must never be instructed to send transfer capabilities over plaintext HTTP.

---

# 5. Transfer Capability Security

The public transfer capability must use cryptographically secure randomness.

Do not use:

- Sequential database IDs
- Timestamps
- Predictable counters
- Filenames
- Short deterministic hashes
- User-controlled identifiers as credentials

A capability should contain sufficient entropy to make online guessing impractical.

The server should keep the internal transfer ID separate from the public capability where practical:

```text
Internal transfer ID
        |
        +--> database identity

Public capability
        |
        +--> external access
```

The public capability must not reveal internal database sequencing.

---

# 6. Human-Friendly Codes vs Share Tokens

A short human-readable code and a high-entropy share token have different security properties.

A short code has a smaller search space and therefore requires stronger online abuse controls.

A long random share token can rely more heavily on entropy.

For the MVP:

> Security-sensitive authorization should use a high-entropy random capability rather than relying solely on a short human-readable code.

If a human-readable code is exposed in the UI, it should not silently become the sole security credential unless its entropy and rate-limit policy have been explicitly validated.

---

# 7. Rate Limiting

Rate limiting is mandatory because transfer capabilities are bearer credentials.

Rate limits must be applied by endpoint class rather than using one uniform limit for every request.

Conceptually:

```text
Transfer creation
    -> strict limit

Transfer lookup
    -> strict limit

Availability polling
    -> moderate limit

Chunk upload
    -> high enough for legitimate transfers

Chunk download
    -> high enough for legitimate transfers
```

Chunk endpoints have a fundamentally different request profile from metadata endpoints.

The exact production limits must be measured against the selected deployment provider and expected chunk size/concurrency.

Rate limiting must not make a legitimate large-file transfer unusable.

---

# 8. Brute-Force Protection

Repeated invalid capability attempts should be throttled.

The system should avoid revealing whether a guessed identifier is close to a valid transfer.

Responses for invalid capabilities should not expose:

- Database IDs
- Transfer ownership information
- File metadata
- Expiration details beyond what is necessary
- Storage keys

The exact rate-limit implementation may remain infrastructure-dependent.

---

# 9. Transfer Expiration

Every transfer must have an expiration time.

```text
createdAt
expiresAt
```

Once expired:

```text
Request
   |
   v
Expiration check
   |
   v
Reject access
```

Expiration is an authorization rule, not merely a cleanup mechanism.

Physical deletion may occur later.

Therefore:

> An expired transfer must be inaccessible even if its storage objects still physically exist.

---

# 10. Cleanup Security

Cleanup must remove:

1. Chunk objects
2. Chunk metadata
3. Transfer metadata

Cleanup should be idempotent.

A failed cleanup must not cause an expired transfer to become accessible again.

The cleanup interval should be selected relative to the configured expiration window.

For example, if expiration is short, cleanup should not be so infrequent that expired data accumulates unnecessarily.

---

# 11. Chunk Authorization

Every chunk operation must validate the transfer capability before accessing storage.

The API must not allow:

```text
GET /chunks/{index}
```

to become a direct storage lookup.

The correct flow is:

```text
Request
  |
  v
Validate capability
  |
  v
Validate transfer
  |
  v
Validate expiration
  |
  v
Validate chunk
  |
  v
Storage operation
```

Storage-provider credentials must never be exposed to browsers.

---

# 12. Chunk Index Validation

The server must validate:

```text
0 <= chunkIndex < totalChunks
```

Invalid indexes must be rejected.

The server must not use a client-provided chunk index to construct arbitrary filesystem paths or unrestricted storage keys.

Storage keys must be generated by the server from validated transfer and chunk identifiers.

---

# 13. Request Size Limits

The server and deployment infrastructure must enforce request-size limits compatible with the selected chunk size.

The relationship is:

```text
Allowed request size
        >=
Configured chunk size + protocol overhead
```

but must remain bounded to prevent abuse.

The final values must be validated against the actual hosting provider before deployment.

This is a deployment/security constraint and must not be left implicit.

---

# 14. Request Timeout Limits

Upload and download requests must have bounded timeouts appropriate to the chosen chunk size and expected network conditions.

Timeouts must not be so aggressive that legitimate large chunks routinely fail.

At the same time, unlimited request duration creates resource-exhaustion risk.

The final timeout budget must therefore be benchmarked with the selected hosting environment.

---

# 15. Chunk Checksum Validation

Uploaded chunks must be integrity-verified before becoming available.

```text
Received bytes
      |
      v
Calculate checksum
      |
      v
Compare expected checksum
      |
  +---+---+
  |       |
MATCH   MISMATCH
  |       |
  v       v
Accept   Reject
```

The server-side calculation is authoritative.

A client-declared checksum must never replace server-side verification.

---

# 16. Chunk Idempotency Security

Retrying a chunk must not silently replace valid data.

For an existing chunk:

```text
same index + same verified checksum
        -> idempotent success

same index + different checksum
        -> conflict
```

The database uniqueness constraint on:

```text
(transfer_id, chunk_index)
```

must enforce the one-logical-chunk invariant under concurrent requests.

The application must handle uniqueness conflicts deterministically rather than exposing raw database errors.

---

# 17. Storage Isolation

Chunk objects must be isolated by transfer.

Conceptually:

```text
transfers/
    {transferId}/
        chunks/
            {chunkIndex}
```

A request for transfer A must never be able to access transfer B's objects by manipulating a chunk index or storage key.

The server constructs storage keys.

The client does not provide arbitrary storage paths.

---

# 18. Path Traversal Protection

If local filesystem storage is used during development, user-controlled values must never directly become filesystem paths.

Do not construct paths such as:

```text
files/{userProvidedFilename}
```

without strict sanitization and controlled path generation.

Prefer:

```text
files/{serverGeneratedTransferId}/chunks/{validatedIndex}
```

The original filename is metadata, not a filesystem path.

---

# 19. Filename Handling

Filenames are untrusted input.

The backend should preserve the original filename as metadata where needed, but must not execute, interpret, or use it as a storage path.

The frontend must treat filenames as display data.

Special characters, path separators, HTML-sensitive characters, and unusual Unicode must not create executable or traversal behavior.

---

# 20. Content Type Handling

`Content-Type` is untrusted client metadata.

It may be stored for download presentation, but must not be treated as proof of the file's actual format.

The backend must not automatically execute uploaded content.

If the browser downloads content using the stored type, the implementation should avoid introducing script execution through unsafe inline handling.

---

# 21. CORS

Production CORS configuration must explicitly define permitted origins.

Avoid:

```text
Access-Control-Allow-Origin: *
```

for authenticated or capability-sensitive production APIs unless the security implications have been deliberately accepted.

Development may use permissive CORS for convenience.

The final production configuration belongs to deployment configuration.

---

# 22. Browser Security

The frontend must not store server secrets.

The browser may hold:

- Transfer capability
- Transfer ID
- Local receiver state
- Progress state

The browser must never hold:

- Object-storage secret keys
- Database credentials
- Backend signing secrets
- Provider administration credentials

---

# 23. Capability Leakage

Because transfer capabilities are bearer credentials, they must not be unnecessarily exposed.

Avoid placing capabilities in:

- Server logs
- Analytics events
- Error messages
- Debug output
- Public metrics
- Third-party telemetry

If a capability appears in a URL, remember that URLs can leak through browser history, screenshots, logs, referrers, and copied links.

Production logging should redact sensitive capability values.

---

# 24. Error Responses

Errors should reveal enough information for the client to recover without exposing internal infrastructure details.

Safe application-level errors include:

```text
TRANSFER_NOT_FOUND
TRANSFER_EXPIRED
INVALID_CHUNK_INDEX
CHECKSUM_MISMATCH
CHUNK_NOT_AVAILABLE
CHUNK_CONFLICT
INVALID_TRANSFER_STATE
STORAGE_FAILURE
```

Do not expose:

- Stack traces
- SQL queries
- Filesystem paths
- Storage credentials
- Provider internals
- Internal exception messages

---

# 25. 404 Error Handling

`404` may represent different application conditions.

For example:

```text
TRANSFER_NOT_FOUND
```

and:

```text
CHUNK_NOT_AVAILABLE
```

may both use HTTP 404.

Therefore the frontend must inspect the structured application error code.

It must not assume every 404 means that the transfer itself does not exist.

---

# 26. Storage/Metadata Consistency

The system must distinguish:

```text
CHUNK_NOT_AVAILABLE
```

from:

```text
STORAGE_FAILURE
```

If metadata says a chunk is available but its storage object cannot be retrieved, the system must not silently report the chunk as merely unavailable.

This condition indicates inconsistency or storage failure and must be handled explicitly.

---

# 27. Orphaned Storage Objects

An object may exist without corresponding metadata because a failure occurred between storage persistence and metadata commit.

Such objects must not become publicly accessible.

Cleanup/reconciliation should remove orphaned objects according to the storage lifecycle policy.

The database remains authoritative for logical chunk availability.

---

# 28. Database Security

The database must:

- Use credentials stored outside source control
- Restrict network access where possible
- Use a dedicated application account
- Avoid excessive privileges
- Use parameterized queries/JPA mechanisms
- Enforce required uniqueness constraints
- Maintain transaction boundaries around state changes

The application must not expose database connectivity to clients.

---

# 29. Secrets Management

Never commit:

```text
DATABASE_URL
STORAGE_ACCESS_KEY
STORAGE_SECRET_KEY
API_SECRETS
```

to the repository.

Use environment variables or the deployment provider's secret-management mechanism.

Local development secrets should remain outside version control.

---

# 30. Logging

Logs should support debugging without becoming a data-leak channel.

Useful events include:

- Transfer creation
- Upload failures
- Download failures
- Checksum failures
- Storage failures
- Expiration
- Cleanup failures
- Rate-limit events
- Unexpected application errors

Do not log:

- Full transfer capabilities
- Storage credentials
- File contents
- Sensitive request headers
- Unnecessary personal information

---

# 31. Resource Exhaustion

The application must protect against excessive:

- Transfer creation
- Chunk uploads
- Chunk downloads
- Availability polling
- Database rows
- Storage consumption
- Concurrent requests
- Request body sizes

No-account systems are especially vulnerable to anonymous resource abuse.

Rate limits, expiration, request limits, and bounded concurrency form the primary MVP defenses.

---

# 32. Upload Abuse

A malicious client may attempt to create a transfer and upload data indefinitely.

The system should enforce:

- Maximum file size
- Maximum chunk size
- Maximum total chunks
- Transfer expiration
- Transfer creation rate limits
- Chunk request rate limits
- Storage quotas where practical

The maximum file size must be chosen consistently with the MVP's actual infrastructure.

---

# 33. Download Abuse

A valid capability may be shared intentionally or stolen.

A receiver could repeatedly download the same chunks.

The system should therefore apply appropriate download rate limits without making normal multi-GB transfers impractical.

The MVP does not require per-user bandwidth accounting.

---

# 34. Polling Abuse

Availability polling can generate significant request volume.

The receiver should:

- Use a bounded polling interval
- Stop polling while paused
- Stop polling after transfer completion
- Stop polling after expiration/error
- Reconcile state after reconnecting

The server may additionally rate-limit availability requests.

---

# 35. Browser Download Integrity

A chunk must not be marked locally as successfully downloaded until the complete chunk has been received and, where implemented, verified.

A partial download must be discarded or marked incomplete.

This prevents:

```text
80% of chunk received
        |
        v
incorrectly marked COMPLETE
```

The client must only advance durable receiver progress after successful persistence.

---

# 36. No Direct P2P

The MVP intentionally does not expose direct sender-to-receiver connections.

This keeps:

- Access control centralized
- Storage state authoritative
- Security boundaries simpler
- Network behavior predictable

P2P/WebRTC is outside the MVP.

---

# 37. Dependency Security

Dependencies should be kept current enough to receive security fixes.

The project should use automated dependency/security checks where practical.

Do not introduce large infrastructure dependencies without a demonstrated requirement.

---

# 38. Security Headers

Production HTTP responses should use appropriate browser security headers where applicable, including protections against:

- MIME sniffing
- Framing/clickjacking
- Unsafe resource execution

Exact headers should be validated against the frontend's actual deployment requirements.

---

# 39. Security Testing

Security tests should include at minimum:

- Invalid transfer capability
- Brute-force/rate-limit behavior
- Expired transfer access
- Invalid chunk index
- Cross-transfer chunk access
- Path traversal attempts
- Oversized request rejection
- Chunk checksum mismatch
- Chunk conflict
- Concurrent duplicate upload
- Storage/metadata inconsistency
- Partial download handling
- CORS behavior
- Secret leakage through logs/errors

These complement the functional tests in `TESTING.md`.

---

# 40. Security Invariants

The following must always hold.

**Invariant 1**

A transfer capability must be unpredictable.

**Invariant 2**

Expired transfers cannot be accessed.

**Invariant 3**

Clients cannot access arbitrary storage keys.

**Invariant 4**

A chunk cannot become available without successful integrity verification and persistence.

**Invariant 5**

A valid chunk cannot be silently replaced by conflicting content.

**Invariant 6**

Storage-provider credentials never reach the browser.

**Invariant 7**

Client-provided filenames cannot control storage paths.

**Invariant 8**

Client progress cannot authorize server-side access.

**Invariant 9**

A failed or partial download cannot be marked as successfully persisted.

**Invariant 10**

Rate limiting must distinguish metadata traffic from legitimate high-volume chunk traffic.

**Invariant 11**

Security failures must not expose infrastructure secrets or sensitive internal details.

---

# 41. MVP Security Boundaries

The MVP intentionally does not provide:

- User accounts
- Password authentication
- End-to-end encryption
- Malware scanning
- Content moderation
- Digital-rights management
- Enterprise identity integration
- Per-user quotas
- Advanced abuse detection
- P2P encryption

These may be considered later if the product scope requires them.

---

# 42. Pre-Deployment Security Validation

Before production deployment, validate:

```text
[ ] HTTPS configured

[ ] High-entropy transfer capability verified

[ ] Production CORS configured

[ ] Rate limits configured per endpoint class

[ ] Maximum file size configured

[ ] Chunk request size compatible with hosting limits

[ ] Request timeouts validated

[ ] Expiration enforced independently of cleanup

[ ] Cleanup interval validated against expiration

[ ] Storage credentials kept secret

[ ] Database credentials kept secret

[ ] Storage keys cannot be client-controlled

[ ] Error responses do not leak internals

[ ] Sensitive capability values are redacted from logs

[ ] Cross-transfer access tests pass

[ ] Checksum/conflict tests pass

[ ] Partial download recovery tests pass
```

---

# 43. Security Principle

The MVP should follow:

> **Treat every browser-provided value as untrusted, and treat every transfer capability as a secret.**

The browser controls execution and presentation.

The backend controls authorization and authoritative state.

Storage credentials remain server-side.

Expiration remains authoritative.

Security controls must reinforce the transfer invariants rather than bypassing them.

---

# 44. Final Security Definition

The MVP uses a capability-based security model over HTTPS.

Unpredictable transfer capabilities authorize access in the absence of user accounts. The backend validates every transfer and chunk operation, enforces expiration, validates chunk indexes and checksums, isolates storage keys, applies endpoint-specific rate limits, bounds resource usage, and prevents client input from becoming arbitrary storage operations.

Object-storage and database credentials remain server-side. Error responses and logs avoid exposing sensitive infrastructure information.

The security design deliberately remains simple enough for the modular-monolith MVP while establishing the controls required for anonymous, temporary, large-file transfers.
