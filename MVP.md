# Large-File Transfer — MVP Specification

**Status:** FROZEN  
**Document:** MVP.md  
**Version:** 1.0  
**Purpose:** Define the exact scope of the first deployable version. This document is the product boundary for implementation.

---

## 1. Product Summary

This project is a simple, privacy-oriented file transfer application.

The primary use case is transferring large files reliably over the internet, while still supporting ordinary small files.

The core differentiator is **progressive file transfer**:

> A receiver can begin downloading a file while the sender is still uploading it.

The receiver can consume every portion that has already been uploaded. If the receiver catches up with the sender, the receiver waits until more data becomes available and then continues automatically.

The system is designed around:

- Chunked transfer
- Pause and resume
- Connection-loss recovery
- Progressive download
- Temporary transfers
- File/chunk integrity verification
- A simple no-login user experience

The product is intentionally narrow. It is a file-transfer system, not a collaboration or social platform.

---

# 2. Product Goal

Build a deployed file-transfer service that makes large transfers feel reliable even when:

- The file is several gigabytes in size
- The sender pauses the transfer
- The sender loses their connection
- The receiver loses their connection
- The receiver joins before the upload is complete
- The receiver catches up with the currently available data

The user should never have to restart a multi-gigabyte transfer from zero merely because of a temporary interruption.

---

# 3. Core User Experience

The application has two primary actions on the landing page:

```text
+---------------------------+
|                           |
|       SEND   RECEIVE      |
|                           |
+---------------------------+
```

There are no accounts or login screens.

## Send flow

```text
Landing page
     |
     v
   SEND
     |
     v
Select / drop a file
     |
     v
Transfer is created
     |
     v
Share link + transfer code become available
     |
     v
Upload begins
```

The sender can share the link/code immediately.

The receiver does not need to wait for the sender to finish uploading.

## Receive flow

```text
Landing page
     |
     v
  RECEIVE
     |
     v
Enter / paste link or code
     |
     v
Transfer is found
     |
     v
Download begins
```

The receiver can join while the sender is still uploading.

---

# 4. File Support

## 4.1 Normal files

Small and ordinary files are fully supported.

Examples:

- Text files
- Documents
- Images
- Videos
- Archives
- Datasets
- Other binary files

There is no separate small-file architecture from the user's perspective.

The same transfer system should handle both small and large files.

## 4.2 Large files

Large files are the first-class engineering use case.

The system must be designed so that file size does not require loading the complete file into application memory.

The upper file-size limit is intentionally **not fixed in the MVP specification**.

The practical limit will be determined by:

- Available storage
- Infrastructure limits
- Upload/download constraints
- Request/proxy limits
- Performance
- Cost
- Testing

The MVP will be tested using a real approximately **20 GB dataset** where practical, but 20 GB is a testing target, not the product's maximum limit.

---

# 5. Folder Support

The MVP transfers **one logical file per transfer**.

Native multi-file/folder transfer is out of scope.

If a user wants to transfer a folder:

```text
Folder
   |
   v
Archive (for example ZIP)
   |
   v
Transfer as one file
```

ZIP is lossless. Creating a ZIP archive does not inherently lose the files or their contents.

The application does not need to implement special folder-transfer logic in the MVP.

---

# 6. Pause and Resume

**Pause and resume are explicit core features for BOTH participants.**

The sender and receiver each have independent controls over their own side of the transfer:

| Participant | Operation | What can be paused/resumed |
|---|---|---|
| Sender | Upload | The file upload from sender to transfer infrastructure |
| Receiver | Download | The file download from transfer infrastructure to receiver |

Pausing one side must not unnecessarily cancel or destroy the other side's progress.

## 6.1 Sender — Pause/Resume Upload

The sender must be able to intentionally pause an active upload.

```text
UPLOADING
    |
    v
 PAUSED
    |
    v
 RESUME
    |
    v
UPLOADING
```

When the sender pauses:

- Uploading stops.
- Already uploaded chunks remain available.
- The transfer itself remains valid.
- The receiver may continue downloading chunks that are already available.
- If the receiver catches up, it waits for more data.
- When the sender resumes, uploading continues from the existing transfer state.

The sender must not have to restart the complete upload.

## 6.2 Receiver — Pause/Resume Download

The receiver must be able to intentionally pause an active download.

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

When the receiver pauses:

- Downloading stops.
- Already downloaded data remains available.
- The transfer remains valid.
- The sender may continue uploading independently.
- New chunks may become available while the receiver is paused.
- When the receiver resumes, it continues from its existing download state.

The receiver must not have to restart the complete download.

## 6.3 Independent Operation

Sender and receiver pause/resume states are independent.

For example:

```text
Sender:    UPLOADING
Receiver:  PAUSED
```

is valid.

Likewise:

```text
Sender:    PAUSED
Receiver:  DOWNLOADING
```

is valid as long as downloadable data is already available.

Both may also be paused simultaneously:

```text
Sender:    PAUSED
Receiver:  PAUSED
```

When either side resumes, the system continues according to the data currently available.

## 6.4 Pause vs Connection Loss

Intentional pause/resume and unexpected connection-loss recovery are different behaviors.

**Intentional pause:**

```text
User clicks PAUSE
        ↓
Transfer stops intentionally
        ↓
User clicks RESUME
        ↓
Transfer continues
```

**Connection loss:**

```text
Connection disappears
        ↓
Transfer is interrupted
        ↓
Connection returns
        ↓
System recovers
        ↓
Transfer continues
```

Both behaviors are required by the MVP.

# 7. Pause

The sender must be able to intentionally pause an active upload.

```text
UPLOADING
    |
    v
 PAUSED
    |
    v
 RESUME
    |
    v
UPLOADING
```

Already completed chunks must remain available.

Pausing must not cause the completed portion of the transfer to be discarded.

---

# 8. Resume

A paused transfer must be resumable.

The sender may:

- Pause
- Leave the transfer
- Close the browser if supported by the final implementation
- Return later
- Continue the same transfer

The system must determine the existing transfer state and continue from the correct point rather than restarting the complete file.

---

# 9. Connection-Loss Recovery

Connection interruption is a core requirement.

## Sender interruption

Example:

```text
Upload
  |
  v
Connection lost at 6.4 GB
  |
  v
Reconnect
  |
  v
Determine completed data
  |
  v
Continue from the correct point
```

The sender should not need to restart the entire upload.

## Receiver interruption

The same principle applies to downloading.

```text
Download
  |
  v
Connection lost at 4.8 GB
  |
  v
Reconnect
  |
  v
Determine downloaded data
  |
  v
Continue
```

The receiver should not unnecessarily restart from zero.

Connection-loss recovery is separate from intentional pause/resume:

- **Pause:** the user intentionally stops the transfer and later resumes it.
- **Connection loss:** the system detects an interruption and recovers automatically when connectivity returns.

---

# 10. Progressive File Transfer

This is the primary product differentiator.

The receiver can begin downloading before the sender has finished uploading.

Example:

```text
Sender
[################----] 80%

Receiver
[############--------] 60%
```

The receiver can continue consuming data while new chunks become available.

If the receiver catches up:

```text
Sender
[################----] 80%

Receiver
[################----] 80%

Receiver status:
Waiting for more data...
```

When the sender makes more data available:

```text
Sender
[##################--] 90%

Receiver
[################----] 80%
```

The receiver continues automatically.

### Important rule

The receiver can only download data that has already been successfully uploaded and made available by the system.

The receiver cannot access data that the sender has not uploaded yet.

---

# 11. Receiver Can Join at Any Point

The receiver is not required to join at the beginning of the upload.

They may join when the transfer is approximately:

- 1% complete
- 10% complete
- 50% complete
- 99% complete
- 100% complete

The receiver downloads whatever portion is currently available and continues as additional data becomes available.

---

# 12. Sender and Receiver Relationship

The sender must remain online while an incomplete upload is actively progressing.

Being online does not mean the sender must remain focused on the transfer page; the final implementation may allow the upload to continue in the background depending on browser/platform behavior.

Once the sender completes the upload:

```text
Upload complete
      |
      v
Sender may leave
      |
      v
Receiver can download later
      |
      v
Until transfer expiration
```

The completed transfer is stored temporarily until its expiration policy removes it.

---

# 13. Multiple Receivers

A transfer may be downloaded by multiple receivers who possess the valid transfer link/code.

Conceptually:

```text
                 +-- Receiver A
                 |
Sender --> Storage
                 |
                 +-- Receiver B
                 |
                 +-- Receiver C
```

No special collaboration or synchronization functionality is required.

Each receiver has an independent download state.

---

# 14. Share Mechanism

Each transfer receives:

1. A shareable link
2. A transfer code

The share information becomes available when the transfer is created, before upload completion.

This is necessary for progressive transfer.

Conceptually:

```text
Create transfer
      |
      +--> Transfer ID / state
      |
      +--> Share link
      |
      +--> Transfer code
      |
      v
Upload begins
```

---

# 15. Transfer Lifecycle

A transfer has a lifecycle similar to:

```text
CREATED
   |
   v
UPLOADING <----> PAUSED
   |
   v
COMPLETE
   |
   v
EXPIRED
   |
   v
DELETED
```

A transfer may also enter an appropriate failure/error state if the final architecture requires it.

The exact state model will be defined in `ARCHITECTURE.md`.

---

# 16. Temporary Storage and Expiration

Transfers are temporary.

The service must not retain user files indefinitely.

After expiration:

```text
Transfer
   |
   v
Expired
   |
   v
File/chunks deleted
   |
   v
Transfer no longer accessible
```

The exact expiration period will be determined during architecture/security design.

Temporary retention is part of the product's privacy positioning.

The application must clearly communicate that transfers are temporary.

---

# 17. Integrity Verification

The system must verify that transferred chunks are correct.

Conceptually:

```text
Expected chunk
     |
     v
Hash / integrity check
     |
     +---- MATCH ----> Accept
     |
     +---- MISMATCH -> Retry / recover
```

The final implementation should also provide a mechanism to verify the completed file where practical.

The exact hashing algorithm and where verification occurs are architecture decisions.

The MVP requirement is:

> Corrupted or incomplete chunks must not silently become accepted file data.

---

# 18. Security Baseline

Authentication is intentionally excluded.

Access to a transfer is controlled by the transfer link/code and the security properties of the transfer identifier.

The MVP should include, where practical:

- HTTPS
- Unpredictable transfer identifiers/codes
- No public directory/listing of transfers
- Server-side validation
- File-size limits based on infrastructure capacity
- Transfer expiration
- Integrity verification
- Basic rate limiting / abuse protection
- Safe file handling
- Appropriate request validation

The exact security architecture will be documented separately.

---

# 19. Authentication

Authentication is **out of scope**.

No:

- User accounts
- Login
- OAuth
- User profiles
- Password-based accounts

The MVP is intentionally anonymous from the application-user perspective.

---

# 20. Compression

Automatic compression is **out of scope**.

The application does not automatically:

```text
File
  |
  v
Compress
  |
  v
Transfer
```

Users can compress files themselves when appropriate.

For example:

```text
Folder
  |
  v
ZIP
  |
  v
Transfer
```

This keeps the transfer system simpler and avoids unnecessary CPU usage for files that are already compressed.

---

# 21. P2P

Peer-to-peer transfer is **out of scope for the MVP**.

The MVP uses a storage-backed transfer model.

```text
Sender
   |
   v
Transfer infrastructure / storage
   |
   v
Receiver
```

P2P may be considered later as a separate optimization or transfer mode.

The MVP should not depend on both sender and receiver being online simultaneously.

---

# 22. Media Preview / Streaming

File preview is **out of scope**.

The application transfers files.

It does not attempt to become:

- A video streaming service
- An audio streaming service
- A document viewer
- A media preview platform

Progressive transfer means the receiver can progressively **download the file**, not that the application provides media playback while the file is arriving.

---

# 23. UI Scope

The UI should be intentionally minimal but polished.

## Landing page

Primary actions:

```text
SEND
RECEIVE
```

## Send page

Must provide:

- File selection/drop zone
- File name
- File size
- Upload progress
- Transfer state
- Pause upload control
- Resume upload control when paused
- Share link
- Transfer code
- Clear indication of whether the transfer is still uploading or complete

Example conceptual UI:

```text
+----------------------------------+
| dataset.zip                      |
| 8.2 GB                           |
|                                  |
| Uploading                        |
| ##################----  72%      |
|                                  |
| 42 MB/s                          |
|                                  |
| [ PAUSE ]                        |
|                                  |
| Share link: ...                  |
| Code: ABCD-1234                  |
+----------------------------------+
```

## Receive page

Must provide:

- Link/code input
- File information
- Download progress
- Current transfer availability
- Waiting state when receiver catches up
- Pause download control
- Resume download control when paused
- Resume/reconnection behavior
- Completion state

Example:

```text
+----------------------------------+
| dataset.zip                      |
|                                  |
| Downloading                      |
| ############--------  55%       |
|                                  |
| 5.5 GB downloaded                |
|                                  |
| Waiting for sender...            |
+----------------------------------+
```

The UI should make transfer state transparent rather than hiding it.

---

# 24. What the MVP Does NOT Include

The following are explicitly excluded:

- User accounts
- Login
- OAuth
- Chat
- Messaging
- Collaborative workspace
- Social features
- Native multi-file transfers
- Native folder transfers
- Automatic compression
- P2P
- Media preview
- Media streaming
- Complex permissions
- Team/workspace management
- Permanent file storage
- Recommendation/discovery features
- Unnecessary abstractions unrelated to transfer reliability

---

# 25. Technical Concepts Behind the MVP

The project will leverage established networking and file-transfer concepts rather than reinventing transport protocols.

Relevant concepts include:

### Chunking

Breaking a large file into manageable pieces.

### Resumable transfer

Continuing from already completed data after interruption.

### Application-level transfer state

Tracking which portions of a file have been successfully persisted and/or downloaded.

### Integrity verification

Using hashes/checksums to detect incorrect data.

### HTTP range-based downloading

Using HTTP's existing ability to request portions of a resource where appropriate.

### Resumable upload protocols

Established protocols such as tus may be evaluated during architecture design instead of implementing resumable uploads from scratch.

### ARQ principles

Go-Back-N and Selective Repeat are useful conceptual references for understanding selective recovery.

The application must **not reimplement TCP reliability**. TCP/HTTP/network protocols already handle lower-level packet delivery and retransmission.

The application-level system is concerned with reliable **file-chunk state**.

---

# 26. Core Engineering Promise

The MVP should satisfy these statements:

### Reliability

> A temporary connection failure should not force the user to restart a large transfer from zero.

### Pause/resume

> A sender can intentionally pause an upload and later continue it.

### Progressive transfer

> A receiver can start downloading before the sender has finished uploading.

### Availability boundary

> A receiver can only consume data that has already become available.

### Temporary privacy

> Files are retained only for the lifetime of the transfer and are deleted after expiration.

### Integrity

> The system must detect invalid or incomplete chunk data rather than silently accepting corruption.

### Simplicity

> A user can send or receive a file without creating an account.

---

# 27. MVP Success Criteria

The MVP is considered successful when we can demonstrate all of the following with a deployed application:

## Basic transfer

- Send a small file
- Receive a small file
- Send a large file
- Receive a large file

## Pause/resume

- Pause the sender's upload
- Resume the sender's upload
- Pause the receiver's download
- Resume the receiver's download
- Verify previously uploaded data is not resent unnecessarily
- Verify previously downloaded data is not unnecessarily downloaded again

## Sender interruption

- Interrupt sender connection
- Reconnect
- Continue transfer successfully

## Receiver interruption

- Interrupt receiver connection
- Reconnect
- Continue download successfully

## Progressive transfer

- Start uploading a large file
- Give the receiver the link/code before completion
- Start receiver download
- Confirm receiver can consume already-available data
- Confirm receiver waits when it catches up
- Confirm receiver continues when more data becomes available

## Integrity

- Verify completed file matches the source
- Verify chunk integrity/recovery behavior

## Expiration

- Verify expired transfers become inaccessible
- Verify associated temporary data is eventually deleted

## Multiple receivers

- Have multiple receivers access the same transfer
- Confirm independent downloads work

---

# 28. MVP Boundary

This is the most important section for implementation.

The team must not expand the product while implementing this MVP unless a change is necessary to make an existing requirement work.

If a proposed feature does not directly support:

1. Sending files
2. Receiving files
3. Large-file reliability
4. Sender pause/resume for upload AND receiver pause/resume for download
5. Connection recovery
6. Progressive transfer
7. Integrity
8. Temporary storage/privacy
9. The basic Send/Receive experience

it is probably **not MVP**.

---

# 29. Future Possibilities — NOT MVP

Possible future work includes:

- P2P transfer
- Hybrid P2P + server fallback
- End-to-end encryption
- Multiple-file transfer
- Native folder transfer
- Parallel transfer optimization
- Smarter congestion/transfer scheduling
- Larger transfer limits
- User accounts
- Transfer history
- Advanced access controls
- Password-protected transfers
- Transfer analytics
- Desktop/mobile clients

These must not influence the MVP implementation unless the architecture needs to leave a clean extension point.

---

# 30. Final Frozen Definition

> **A simple, no-login, privacy-oriented file transfer service that supports ordinary files and very large files, using chunked transfers with pause/resume, connection-loss recovery, integrity verification, temporary storage, shareable links/codes, and progressive downloading while the sender is still uploading.**

The product has two primary actions:

```text
SEND
RECEIVE
```

The system stores transfers temporarily, allows receivers to join at any point, allows receivers to consume currently available data while an upload is still progressing, allows both sender and receiver to intentionally pause and resume their respective transfer, and recovers interrupted transfers without unnecessarily restarting from zero.

**No additional product features are part of MVP v1.**
