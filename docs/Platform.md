# Platform

Configuration, deployment models, multi-tenancy, and partitioning.

See [ADR-019](../../pm/docs/decisions/adr-019-open-core-gateway-model.md) for the
full architectural rationale (open-core model, ISV white-labeling, Gateway protocol).

## Deployment Models

Vault serves five deployment models. Vault is always open source; Gateway is
proprietary (SaaS or licensed). See ADR-019 for details.

| Model | Vault hosted by | Gateway | Brand | Identity |
|-------|----------------|---------|-------|----------|
| **SaaS** | Us | Our Gateway (always) | Libragraph | Our Keycloak |
| **ISV (Gateway SaaS)** | ISV | Our Gateway (paid) | ISV's | ISV's IdP |
| **ISV (Gateway Licensed)** | ISV | ISV runs Gateway (licensed) | ISV's | ISV's IdP |
| **Private (public)** | User | Our Gateway (optional, paid) | User's choice | Passkey / OIDC |
| **Private (firewall)** | User | None | N/A | Passkey / NAS delegation |

**Vault without Gateway is a complete product.** A private NAS user gets full
ingestion, storage, search, MCP integration, and local auth with zero cost.
Gateway adds internet connectivity: public endpoint, SSL, DDoS, OAuth relay.

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

### OIDC Configuration per Profile

Each config profile specifies its trusted issuer:

```properties
# application-dev.properties (Keycloak via Dev Services)
vault.auth.issuer.discovery-url=http://localhost:8180/realms/vault-dev/.well-known/openid-configuration
vault.auth.issuer.client-id=vault-app

# application-prod.properties (any OIDC provider)
vault.auth.issuer.discovery-url=https://auth.acmedocs.com/.well-known/openid-configuration
vault.auth.issuer.client-id=vault-integration
```

Additional trusted issuers can be registered at runtime via the Management API.
See [Identity](Identity.md).

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

### Tenant

A data partition within an Organization. A single org has 1+ Tenants.

- All data records structurally belong to a Tenant
- Tenant is represented as a column in SQL and a folder prefix in Object Store
- Each Tenant has its own role assignments (read, write, admin)
- Managed via Management API (`/admin/tenants`)

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
> bucket-per-tenant) — needs more discussion. For ISVs hosting their own
> Vault (single org per instance), the org-id prefix may be unnecessary.

### Sandbox

A filtered view of a Tenant for access control and data masking.

- Has a list of files/features visible to it
- Rules can expand to cover any data in the parent tenant (search predicates)
- Structurally belongs to a Tenant
- Use cases: shared workspaces, client-facing views, compliance boundaries
- Sandbox scope is carried as a claim in the Vault JWT
- Managed via Management API (`/admin/tenants/{id}/sandboxes`)

> **DEFERRED:** Sandbox implementation (RLS vs application-level filtering vs
> materialized views). Sandbox scope is now a Vault JWT claim; enforcement
> mechanism is still open.

## Tokens

Vault issues two types of JWTs. See [Identity](Identity.md) for details.

- **Session tokens** — issued by `/auth/exchange` (1h + refresh), for interactive sessions
- **Delegation tokens** — issued by `/api/tokens` (configurable lifetime), for service access, shares, MCP

Both are Vault-signed JWTs carrying org_id, tenant_id, roles, and sandbox scope.
