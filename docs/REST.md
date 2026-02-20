# REST

**Status:** Proposed design for Quarkus rebuild — aligned with [ADR-032](../../pm/docs/decisions/adr-032-rest-api-conventions.md).

REST API, reference token identity, and file upload/download.

## Framework

Quarkus RESTEasy Reactive — the default REST framework in Quarkus:

```groovy
// build.gradle.kts
implementation("io.quarkus:quarkus-rest")
implementation("io.quarkus:quarkus-rest-jackson")
```

See [Quarkus REST Guide](https://quarkus.io/guides/rest).

## Authentication

Every request carries a Vault-issued reference token (JWT). The JWT is a session
pointer — authorization context is loaded from the database per request. See
[Authentication.md](Authentication.md) for the full model.

```java
@Path("/api/t/{tid}")
@Authenticated
public class ContentResource {

    @Inject VaultRequestContext ctx;  // loaded from session DB lookup

    // ctx.principalId(), ctx.tenantId(), ctx.role(), ctx.sandboxId()
    // RLS session variables already set before this method executes
}
```

### Request Interceptor Chain

1. Extract JWT from `Authorization: Bearer` header or `__Host-vault` cookie
2. Verify ES256 signature (is this token from this Vault instance?)
3. Look up session by `jti` — load principal, tenant, role, sandbox
4. Extract `{tid}` from path, verify principal has role on that tenant
5. `SET LOCAL vault.tenant_id`, `vault.principal_id`, `vault.sandbox_id`, `vault.role`
6. Execute the API handler — RLS automatically filters results

## Path Structure

```
/auth/*              Authentication (outside tenant scope)
/api/t/{tid}/*       Tenant-scoped data operations
/admin/t/{tid}/*     Tenant management (admin role)
/sys/*               Cross-tenant system operations (superuser)
```

## Proposed Endpoints

### Content Upload

```
PUT /api/t/{tid}/content
  Content-Type: application/octet-stream (or actual MIME type)
  Body: raw binary
  → 202 Accepted { "data": { "taskId": 42, "status": "open" } }
```

Batch upload (multiple files in one operation):
```
POST /api/t/{tid}/batches
  → 201 Created { "data": { "batchId": 7 } }

PUT /api/t/{tid}/content?batchId=7
  Content-Type: application/octet-stream
  Body: raw binary
  → 202 Accepted { "data": { "taskId": 43 } }

POST /api/t/{tid}/batches/7/complete
  → 202 Accepted
```

### Task Status

```
GET /api/t/{tid}/tasks/{taskId}
  → 200 { "data": { "id": 42, "status": "complete", "output": { ... } } }
```

### Query

```
GET /api/t/{tid}/blobs?q=search+terms&mime=application/pdf&limit=20
  → 200 {
      "data": [{ "hash": "...", "leafSize": 1234, "mimeType": "..." }],
      "pagination": { "next_cursor": "eyJ...", "has_more": true, "limit": 20 }
    }

GET /api/t/{tid}/blobs/{blobRef}
  Example: GET /api/t/1/blobs/a1b2c3...-1048576
  → 200 { "data": { "hash": "...", "leafSize": ..., "metadata": {...}, "containers": [...] } }
```

### Download / Reconstruct

```
GET /api/t/{tid}/content/{blobRef}
  Example: GET /api/t/1/content/a1b2c3...-1048576
  → 200 (streaming binary content)

GET /api/t/{tid}/content/{containerRef}?reconstruct=true
  Example: GET /api/t/1/content/f9e8d7...-524288_?reconstruct=true
  → 200 (streaming reconstructed container, small)
  → 202 Accepted { "data": { "taskId": 44 } } (large containers, async)
```

**Note:** BlobRef format is `{hash}-{leafSize}[_]` — no extension in URL.

### Admin

```
POST /admin/t/{tid}/rebuild-sql
  → 202 Accepted { "data": { "taskId": 45 } }
```

## Resumable Upload

For large files, support resumable uploads via `Content-Range`:

> **DEFERRED:** Resumable upload protocol (Content-Range vs tus vs S3
> multipart). Raw `PUT` is sufficient for v1. Resumable upload is
> a future enhancement for multi-GB files.

## Streaming Download

For blob retrieval and reconstruction, stream content rather than buffering:

```java
@GET
@Path("/t/{tid}/content/{blobRef}")
@Produces(MediaType.APPLICATION_OCTET_STREAM)
public Uni<InputStream> downloadBlob(
        @PathParam("tid") int tenantId,
        @PathParam("blobRef") String blobRefStr) {

    BlobRef ref = BlobRef.parse(blobRefStr);  // Parse "{hash}-{leafSize}[_]"

    return blobService.retrieve(tenantId, ref)
        .map(binaryData -> binaryData.inputStream(0));
}
```

## Pagination

Cursor-based pagination. No offset, no total counts.

```json
{
  "data": [...],
  "pagination": {
    "next_cursor": "eyJjcmVhdGVkX2F0IjoiMjAyNi0wMi0yMCIsImlkIjo0Mn0=",
    "has_more": true,
    "limit": 20
  }
}
```

Cursor encodes `(created_at, id)` as base64. Pass as `?cursor=...` on next request.

## Error Responses

[RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457) format:

```json
{
  "type": "https://docs.libragraph.com/errors/blob-not-found",
  "title": "Blob not found",
  "status": 404,
  "detail": "No blob matching ref a1b2c3...-1048576 in tenant 1",
  "instance": "/api/t/1/content/a1b2c3...-1048576"
}
```

## CORS

```properties
quarkus.http.cors=true
quarkus.http.cors.origins=http://localhost:3000
quarkus.http.cors.methods=GET,POST,PUT,DELETE
```

> **DEPENDENCY:** Depends on [Identity](Identity.md) and [Authentication](Authentication.md)
> for auth, [Tasks](Tasks.md) for async operations, [ObjectStore](ObjectStore.md)
> and [Database](Database.md) for data access.
