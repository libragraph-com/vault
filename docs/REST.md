# REST

**Status:** Proposed design for Quarkus rebuild

REST API, JWT identity, and file upload/download.

## Framework

Quarkus RESTEasy Reactive — the default REST framework in Quarkus:

```xml
<!-- build.gradle -->
implementation("io.quarkus:quarkus-rest")
implementation("io.quarkus:quarkus-rest-jackson")
```

See [Quarkus REST Guide](https://quarkus.io/guides/rest).

## JWT Identity

Every request carries a JWT from Keycloak (see [Identity](Identity.md)):

```java
@Path("/api")
@Authenticated
public class VaultResource {

    @Inject SecurityIdentity identity;
    @Inject JsonWebToken jwt;

    String tenantId() {
        return jwt.getClaim("tenant_id");
    }
}
```

## Proposed Endpoints

**All endpoints require `tenantId` in path for explicit tenant isolation.**

### Ingest

```
POST /api/tenants/{tenantId}/containers
  Content-Type: multipart/form-data
  Body: file (binary)
  → 202 Accepted { "taskId": "uuid", "status": "pending" }

GET /api/tenants/{tenantId}/tasks/{taskId}
  → 200 { "status": "complete", "result": { ... } }
```

### Query

```
GET /api/tenants/{tenantId}/leaves?q=search+terms&mime=application/pdf&offset=0&limit=20
  → 200 {
      "items": [{ "hash": "...", "size": 1234, "mimeType": "..." }],
      "total": 1523,
      "offset": 0,
      "limit": 20
    }

GET /api/tenants/{tenantId}/leaves/{blobRef}
  Example: GET /api/tenants/123e4567.../leaves/a1b2c3...-1048576
  → 200 { "hash": "...", "size": ..., "metadata": {...}, "containers": [...] }
```

### Download / Reconstruct

```
GET /api/tenants/{tenantId}/blobs/{blobRef}
  Example: GET /api/tenants/123e4567.../blobs/a1b2c3...-1048576
  → 200 (streaming binary content)

POST /api/tenants/{tenantId}/containers/{containerRef}/reconstruct
  Example: POST /api/tenants/123e4567.../containers/f9e8d7...-524288_/reconstruct
  → 200 (streaming file download, small containers)
  → 202 Accepted { "taskId": "uuid" } (large containers, async)
```

**Note:** BlobRef format is `{hash}-{leafSize}[_]` — no extension in URL.

### Admin

```
POST /api/admin/tenants/{tenantId}/rebuild-sql
  → 202 Accepted { "taskId": "uuid" }
```

## Resumable Upload

For large files, support resumable uploads (tus protocol or chunked):

> **OPEN QUESTION:** Which resumable upload protocol?
> - **tus** (https://tus.io) — open protocol, well-supported
> - **Chunked multipart** — simpler, no extra library
> - **S3 multipart** — if uploading directly to object store
>
> For v1, regular multipart is likely sufficient. Resumable upload is a
> future enhancement for multi-GB files.

## Streaming Download

For blob retrieval and reconstruction, stream content rather than buffering:

```java
@GET
@Path("/tenants/{tenantId}/blobs/{blobRef}")
@Produces(MediaType.APPLICATION_OCTET_STREAM)
public Uni<InputStream> downloadBlob(
        @PathParam("tenantId") UUID tenantId,
        @PathParam("blobRef") String blobRefStr) {

    BlobRef ref = BlobRef.parse(blobRefStr);  // Parse "{hash}-{leafSize}[_]"

    return blobService.retrieve(tenantId, ref)
        .map(binaryData -> binaryData.inputStream(0));
}
```

## Error Responses

Standard JSON error format:

```json
{
  "error": "not_found",
  "message": "Leaf with hash abc123 not found",
  "status": 404
}
```

## CORS

```properties
quarkus.http.cors=true
quarkus.http.cors.origins=http://localhost:3000
quarkus.http.cors.methods=GET,POST,PUT,DELETE
```

> **DEPENDENCY:** Depends on [Identity](Identity.md) for authentication,
> [Tasks](Tasks.md) for async operations, [ObjectStore](ObjectStore.md)
> and [Database](Database.md) for data access.
