# Platform

Configuration, deployment models, multi-tenancy, and partitioning.

See [ADR-019](../../pm/docs/decisions/adr-019-open-core-gateway-model.md) for the
full architectural rationale (open-core model, ISV white-labeling, Gateway protocol).

## Deployment Models

Vault serves five deployment models. Vault is always open source; Gateway is
proprietary (SaaS or licensed). See ADR-019 for details.

| Model | Vault hosted by | Gateway | Brand | Authentication | License |
|-------|----------------|---------|-------|----------------|---------|
| **SaaS** | Us | Our Gateway (always) | LibRAGraph | Cognito (Console) | Vault: AGPL / Console+Gateway: BSL 1.1 |
| **ISV (Gateway SaaS)** | ISV | Our Gateway (paid) | ISV's | ISV's IdP | Vault: AGPL / Gateway: BSL 1.1 |
| **ISV (Gateway Licensed)** | ISV | ISV runs Gateway (licensed) | ISV's | ISV's IdP | Vault: AGPL / Gateway: BSL 1.1 commercial |
| **Private (public)** | User | Our Gateway (optional, paid) | LibRAGraph | Passkey (primary), Password+TOTP (fallback) | Vault: AGPL / Gateway: BSL 1.1 |
| **Private (firewall)** | User | None | N/A | Passkey (primary), Password+TOTP (fallback) | Vault: AGPL |

**Vault without Gateway is a complete product.** A private NAS user gets full
ingestion, storage, search, MCP integration, and local auth with zero cost.
Gateway adds internet connectivity: public endpoint, SSL, DDoS, OAuth relay.

See [ADR-025](../../pm/docs/decisions/adr-025-platform-deployment-and-code-sharing.md)
for deployment architecture and [ADR-028](../../pm/docs/decisions/adr-028-commercial-model.md)
for the commercial model (cloud credits, subscriptions, accounts).

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

Quarkus CDI selects the right implementation at build time via
`@IfBuildProperty(name = "vault.object-store.type", stringValue = "s3")`
(or `"filesystem"`). See [ObjectStore](ObjectStore.md).

> **DECISION:** All implementations are compiled in; activated via
> build-time config property (`vault.object-store.type`). Quarkus
> `@IfBuildProperty` eliminates unused beans at build time.

### OIDC Configuration per Profile

**Note:** Authentication is not yet implemented. When implemented, each config
profile will specify its trusted issuer:

```properties
# application-prod.properties (OIDC provider for cloud-connected vaults)
vault.auth.issuer.discovery-url=https://auth.libragraph.com/.well-known/openid-configuration
vault.auth.issuer.client-id=vault-integration

# Local vaults use passkey/WebAuthn (no external IdP required)
```

See [Identity](Identity.md) and [Authentication](Authentication.md) for the
complete authentication model (ADR-014, ADR-030).

### Gateway Configuration

Gateway connectivity is optional, enabled via config:

```properties
# Enable Gateway client (outbound WebSocket to Gateway service)
vault.gateway.enabled=true
vault.gateway.endpoint=wss://gateway.libragraph.com
vault.gateway.instance-id=${INSTANCE_ID}
vault.gateway.api-key=${GATEWAY_API_KEY}
```

See ADR-019 for the full Vault↔Gateway protocol.

## Users / Identity

Users authenticate via external IdPs (any OIDC provider, passkeys, NAS delegation).
Vault manages principals, role assignments, and tenant membership in its own DB.

- No local accounts (no password storage in Vault)
- Any OIDC provider can be registered as a trusted issuer
- Vault issues its own JWTs via token exchange (`/auth/exchange`)
- Principals are auto-provisioned on first login (configurable per issuer)

See [Identity](Identity.md) for full auth details.

## Partitioning

Inside a running Vault instance, data is partitioned hierarchically:

```
Organization
  └── Tenant(s)
        └── Sandbox(es)
```

### Organization

The top-level administrative boundary. Managed in Vault's DB via the
Management API (`/admin/*`).

- Represents a service provider (ISV), a company, or an individual
- Trusted issuers are scoped to an org (all tenants share the same IdPs)
- Role assignments and principals are org-scoped
- For private vaults: typically one org created during `vault init`
- For ISVs: one org per ISV, with tenants for each of the ISV's customers

**Note on Console integration:** Vault's Organization (content/data boundary)
is independent from Console's Org and Account (billing/admin boundary). See
[ADR-028](../../pm/docs/decisions/adr-028-commercial-model.md) §4 for the
Console entity model (User ↔ Org → Account → Service). A Console account owns
a gateway service; that gateway can route to one or more Vault organizations.

### Tenant

A data partition within an Organization. A single org has 1+ Tenants.

- All data records structurally belong to a Tenant
- Tenant is represented as a column in SQL and a folder prefix in Object Store
- Each Tenant has its own role assignments (read, write, admin)
- Managed via Management API (`/admin/tenants`)

```sql
-- Every data table includes tenant scoping
SELECT br.* FROM blob b
  JOIN blob_ref br ON b.blob_ref_id = br.id
  WHERE b.tenant_id = ? AND br.content_hash = ?
```

```
# Object store layout (bucket-per-tenant)
{bucket-prefix}{tenantId}/{hash}-{leafSize}      # leaf
{bucket-prefix}{tenantId}/{hash}-{leafSize}_     # container (manifest)
```

Tenant isolation is bucket-per-tenant in S3/MinIO, directory-per-tenant in
filesystem backend. See [ObjectStore](ObjectStore.md) for details.

### Sandbox

A filtered view of a Tenant for access control and data masking.

- Has a list of files/features visible to it
- Rules can expand to cover any data in the parent tenant (search predicates)
- Structurally belongs to a Tenant
- Use cases: shared workspaces, client-facing views, compliance boundaries
- Sandbox is a property of the DB-backed session (not a JWT claim)
- Enforced via RLS at the database layer
- See [Authentication.md §4](Authentication.md) for sandbox design

## Tokens

Vault uses **reference tokens** — JWTs are signed session pointers, not
self-contained authorization documents. The database is the single authority for
session validity and authorization context. See [Authentication.md](Authentication.md)
for the full model.

- **Session tokens** — issued by `/auth/exchange` or `/auth/passkey`, for interactive sessions
- **Delegation tokens** — created by authenticated users via API, for MCP agents, share links, automation
- **Service tokens** — created by admins, no expiry (revocable), for machine-to-machine access

All token types are DB-backed sessions. JWT carries only `sub`, `jti`, `exp` —
authorization is loaded from DB per request.
