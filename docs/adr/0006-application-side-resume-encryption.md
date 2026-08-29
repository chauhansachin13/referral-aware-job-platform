# 6. Resumes encrypted in the application, released by our own signed URL

- Status: Accepted
- Date: 2026-08-29

## Context

A resume is the most sensitive thing this platform holds. Requirements: encrypted at rest,
released only through short-lived signed URLs, and a working hard-delete path.

## Decision

Encrypt with AES-256-GCM inside the application before the bytes reach object storage. Release
through an HMAC-signed token that this application mints and redeems, valid for five minutes.

Deletion removes the object first, then the row.

## Alternatives considered

**Server-side encryption (SSE-S3 / SSE-KMS) plus S3 presigned URLs.** The conventional answer,
and simpler. Rejected on three counts:

1. With SSE, anyone holding bucket credentials reads resumes in plaintext, and the set of things
   holding bucket credentials only grows — backup jobs, analytics exports, a debugging session.
2. A presigned URL cannot be revoked before it expires. If a referral is closed or a request is
   withdrawn thirty seconds after a link is minted, that link still works.
3. The access decision moves to the object store, where it is not audited. Every resume release
   in this system is a logged event attached to a referral request.

**Encrypt in the application, but serve ciphertext via presigned URL.** Combines both models and
works for neither: the referrer's browser downloads an encrypted blob.

**Envelope encryption with KMS per object.** The right answer with a real KMS. Deferred because
the platform must run from `docker compose up` with no cloud dependency; `key_id` is on the
`resume` row so a rotation to per-object keys is a migration rather than a redesign.

## Consequences

- The object store never holds plaintext.
- Access is checked twice: when a link is minted and again when it is redeemed, against the
  referral's current state. A token from an accepted request that has since closed is void.
- GCM's authentication tag plus a stored SHA-256 means tampering fails loudly instead of
  returning altered bytes.
- Downloads flow through the application, so they consume application bandwidth and cannot be
  offloaded to a CDN. At resume sizes and referral volumes this is not the constraint.
- Rotating the encryption key requires re-encrypting existing objects. There is no automated
  rotation job yet; this is a known gap.
