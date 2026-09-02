# Large-File Transfer — Open Issues & Things to Keep In Mind

**Purpose:** Every doc (MVP → RULES) has been reviewed and locked. This file collects what's still genuinely unresolved or easy to get wrong during implementation, so nothing quietly falls through the cracks while vibe-coding.

---

## 🔴 Must resolve before/during implementation (real gaps)

### 1. Chunk request size & timeout vs. hosting provider — **no number picked yet**
Flagged in ARCHITECTURE, API, DEPLOYMENT, and SECURITY (four separate times). Every doc correctly says "must be validated against the real host," but nobody has actually picked:
- final `chunkSize`
- max request body size the host/proxy will accept
- request timeout budget for one chunk upload/download

**Action:** Once a hosting provider is chosen, this is the *first* thing to empirically test — before writing the chunk upload/download implementation, not after. A 32MB chunk on a host that caps bodies at 10MB silently breaks everything downstream.

### 2. Sender abandonment — protocol decision not actually made, only tested for
TRANSFER-PROTOCOL, API, and TESTING all flagged the scenario: sender uploads all chunks but never calls `POST /transfers/{id}/complete` (tab closed, crash, etc.). Receiver sits in `WAITING_FOR_DATA` forever until expiration is the only backstop. RULES.md/AGENTS.md added it to the required test list, but **the actual behavior is still undecided**:
- Option A: keep requiring explicit `/complete` call, rely on expiration as the eventual cleanup (current implicit default)
- Option B: auto-transition transfer to `COMPLETE` server-side the moment `availableChunkCount == totalChunks`, no client call needed

**Action:** Pick one explicitly before implementing the completion endpoint. Option B removes an entire failure mode for free.

### 3. Browser-side multi-GB storage/assembly — flagged as highest-priority, not yet prototyped
ARCHITECTURE.md correctly made this **Step 0** in the implementation order, ahead of the domain model. The spike in UPDATE.md tested server-side chunking only — this risk is **fully untouched**.

**Action:** Prototype before writing DownloadManager: File System Access API (writable stream to disk) vs IndexedDB (chunked blob storage) — these are structurally different approaches, not interchangeable "TBD" options. The result determines the whole download architecture.

### 4. Download request order vs. file assembly order — needs one clarifying line
TRANSFER-PROTOCOL §9 allows out-of-order chunk *upload*, and §40 allows out-of-order chunk *download requests*, but §31 requires final assembly in strict index order. This is fine in practice but should be stated explicitly: **download/request order ≠ write/assembly order.** Don't let an implementer conflate the two.

---

## 🟡 Design choices to make deliberately (not gaps, but decisions still open)

- **Share code format**: SECURITY.md correctly separates high-entropy bearer token (security-critical) from an optional short human-readable code (convenience only, needs its own rate-limiting if exposed). Decide up front whether v1 even ships a human-readable code — simpler to skip it entirely for MVP.
- **Rate limit values**: SECURITY.md §7 requires per-endpoint-class limits (chunk endpoints ≠ metadata endpoints) but leaves exact numbers to be measured. Don't ship one blanket rate limiter.
- **Cleanup interval**: must be set relative to `TRANSFER_EXPIRATION`, not a fixed "hourly" default if expiration windows are short (SECURITY §10, DEPLOYMENT §12).

---

## 🟢 Confirmed decisions — do not re-litigate these mid-implementation

These were explicitly locked after review. If you find yourself "improving" one of these while coding, stop and check the spec first (per RULES.md §23):

- **One object per chunk** in storage (not one incomplete object with byte-range reads) — this is the single most load-bearing architectural decision in the whole spec set.
- **Receiver progress is local to browser/device** in MVP — no cross-device resume. This is intentional, not an oversight.
- **`(transfer_id, chunk_index)` uniqueness is enforced at the DB level**, not via application check-then-insert. Concurrent identical/conflicting chunk writes must resolve through the DB constraint.
- **Metadata is authoritative over storage bytes.** `metadata=AVAILABLE + object=missing` → `STORAGE_FAILURE`, never silently treated as `CHUNK_NOT_AVAILABLE`.
- **A 404 is not always "doesn't exist."** `TRANSFER_NOT_FOUND` and `CHUNK_NOT_AVAILABLE` share HTTP 404 — frontend must read the structured error body, never branch on status code alone.
- **Sender pause and receiver pause are fully independent** client-side states, no server-side pause endpoint needed.
- **Notifications (polling/SSE) are hints only** — never authoritative. Always reconcile against `GET /transfers/{id}` or `/availability` after any notification or reconnect.
- **Polling stops while paused, on completion, and on expiration/error** — don't let it run indefinitely in the background.

---

## 🧪 Edge cases that must have explicit tests (easy to forget)

- Single-chunk file (`totalChunks = 1`) — degenerate case of "final chunk may be smaller."
- File size exactly divisible by chunk size (no partial final chunk at all).
- Concurrent identical/conflicting uploads to the same `(transfer_id, chunk_index)`.
- Metadata/storage inconsistency in **both directions** (object-without-metadata, and metadata-without-object).
- Sender abandonment (see #2 above) once the behavior is decided.
- Partial local write on the receiver side never marked as a completed chunk.

---

## Process note

`AGENTS.md` and `RULES.md` re-summarize invariants that live authoritatively in the other docs. If a spec ever changes, update the source doc *and* these two, or they'll drift out of sync — which is exactly the kind of silent drift RULES.md §23 is trying to prevent everywhere else.
