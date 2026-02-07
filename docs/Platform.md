# Platform

Configuration, multi-tenancy, and partitioning.

## App Configuration

Quarkus uses [SmallRye Config](https://quarkus.io/guides/config-reference) with profile support.

```
/opt/vault/
  ├── vault-runner.jar
  └── config/
      ├── application.properties         # Base config
      ├── application-dev.properties     # Dev overrides
      ├── application-qa.properties      # QA overrides
      └── application-prod.properties    # Prod overrides
```

Run with profile:
```bash
java -Dquarkus.profile=qa -jar vault-runner.jar
```

Or specify custom config location:
```bash
java -Dquarkus.config.locations=/etc/vault/config \
     -Dquarkus.profile=prod \
     -jar vault-runner.jar
```

Environment variables override properties (standard Quarkus behavior):
```bash
export QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://prod-db:5432/vault
```

See [Quarkus Configuration Guide](https://quarkus.io/guides/config).

### Pluggable Implementations

Object storage backend is selected via config:

```properties
# application-dev.properties
vault.object-store.type=filesystem
vault.object-store.base-path=/tmp/vault-blobs

# application-prod.properties
vault.object-store.type=s3
vault.object-store.bucket=vault-prod-blobs
```

Quarkus CDI selects the right `@Alternative` or `@LookupIfProperty` bean
based on profile. See [ObjectStore](ObjectStore.md).

> **DECISION:** All implementations are always available; selected via
> YAML config property (`vault.object-store.type`). CDI producer reads
> config and returns the correct `ObjectStorage` implementation. No
> annotation-based selection — config-driven only.

### Keycloak Realm per Profile

Each config profile specifies its Keycloak realm:

```properties
# application-dev.properties
quarkus.oidc.auth-server-url=http://localhost:8180/realms/vault-dev

# application-prod.properties
quarkus.oidc.auth-server-url=https://auth.libragraph.com/realms/vault-prod
```

## Users / Identity

Users are defined in a global namespace (enabling SaaS / federated identities)
and authenticated via Keycloak upstream of the application.

- No local accounts (no saved passwords in Vault)
- Keycloak handles all authentication (OIDC)
- Vault receives a JWT and trusts it

See [Identity](Identity.md) for full auth details.

## Partitioning

Inside a running Vault instance, data is partitioned hierarchically:

```
Organization
  └── Tenant(s)
        └── Sandbox(es)
```

### Organization

A Keycloak concept. Users, Groups, and Roles are defined in Keycloak.
Vault doesn't create users — it stores which users have which Roles
within a given Organization.

### Tenant

A data partition within an Organization. A single org has 1+ Tenants.

- All data records structurally belong to a Tenant
- Tenant is represented as a column in SQL and a folder prefix in Object Store
- Each Tenant has its own role assignments (read, write, admin)

```sql
-- Every data table includes tenant scoping
SELECT * FROM leaves WHERE tenant_id = ? AND content_hash = ?
```

```
# Object store layout
{org-id}/{tenant-id}/{hash}-{leafSize}      # leaf
{org-id}/{tenant-id}/{hash}-{leafSize}_     # container (manifest)
```

> **DEFERRED:** Tenant_id path structure (path component vs flat key vs
> bucket-per-tenant) — needs more discussion with identity model.

### Sandbox

A filtered view of a Tenant for access control and data masking.

- Has a list of files/features visible to it
- Rules can expand to cover any data in the parent tenant (search predicates)
- Structurally belongs to a Tenant
- Use cases: shared workspaces, client-facing views, compliance boundaries

> **DEFERRED:** Sandbox implementation (RLS vs application-level filtering vs
> materialized views). Depends on tenant/identity model decisions.

## Tokens

Users can create scoped tokens for limited access on their behalf.

- Tokens scoped to specific regions (org, tenant, sandbox)
- Represented as JWTs
- Served as the security principal for REST requests

See [Identity](Identity.md) and [REST](REST.md).
