# Authentication & Authorization Model

> Canonical reference for Vault's identity, token, and access control design.
> Supersedes auth-related sections of Identity.md and Platform.md.
> Derives from ADR-014, ADR-019, ADR-025, and e2e-open-questions.md decisions.

---

## Overview

Vault uses **reference tokens** with **PostgreSQL Row-Level Security (RLS)** for
enforcement. JWTs are signed session pointers — the database is the single
authority for session validity and authorization context.

- **Authentication:** Passwordless only (passkey, OIDC via Console, NAS
  delegation, device identity, recovery keys)
- **Authorization:** RBAC (roles) + permissions boundary (sandbox), enforced
  by RLS at the database layer
- **Tokens:** DB-backed sessions. JWT carries identity proof (sub, jti, exp),
  not claims. Authorization loaded fresh from DB per request.

---

## 1. Principal Model

A **principal** represents a person or service account. Principals can have
multiple linked **identities** (auth methods).

```
principal
├── identity_link (passkey credential)
├── identity_link (OIDC: Google via Console)
├── identity_link (OIDC: corporate IdP)
├── identity_link (NAS delegation: UID 1000)
└── identity_link (device: iPhone keychain)
```

### Tables

```sql
CREATE TABLE principal (
    id          SERIAL PRIMARY KEY,
    global_id   UUID NOT NULL DEFAULT gen_random_uuid(),
    display_name TEXT NOT NULL,
    email       TEXT,                   -- optional, from IdP
    status      SMALLINT NOT NULL DEFAULT 0,  -- 0=active, 1=suspended, 2=deactivated
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE identity_link (
    id              SERIAL PRIMARY KEY,
    principal_id    INT NOT NULL REFERENCES principal(id),
    method          TEXT NOT NULL,      -- 'passkey', 'oidc', 'nas', 'device', 'recovery'
    issuer          TEXT,               -- OIDC issuer URL, or NULL for local methods
    external_sub    TEXT,               -- subject at the IdP, credential ID, UID, etc.
    metadata        JSONB,              -- method-specific data (public key, claim mapping, etc.)
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (method, issuer, external_sub)
);
```

### Identity Resolution

On any auth request, Vault resolves `(method, issuer, external_sub)` to a
principal. If no match and auto-provisioning is enabled for that issuer,
a new principal is created with the configured default role.

---

## 2. Organization & Tenant

### Hierarchy

```
organization (1 per Vault instance minimum)
└── tenant (1 per org minimum, data isolation boundary)
    ├── entries, blobs, manifests (scoped by tenant_id)
    ├── saved queries
    └── sandboxes
```

### Initialization (OOBE)

First boot runs a setup wizard (interactive UI or headless config):

1. **Use case:** Personal or Business
2. **Account:** Create passkey (always) + optionally "Sign in with Libragraph
   Account" (redirects to Console for Cognito)
3. **Vault creates:** default organization + default tenant + admin principal
   linked to the enrolled passkey

Headless equivalent (Terraform / Docker Compose / env vars):
```yaml
vault:
  init:
    org-name: "Home"
    tenant-name: "default"
    admin-passkey: false          # skip interactive passkey enrollment
    admin-oidc-issuer: "https://auth.libragraph.com"  # auto-provision from Console
```

**Precondition:** Vault does not serve API requests until at least 1 org +
1 tenant + 1 admin principal exist.

### Tenant Isolation

- **ObjectStorage:** Hard partition. Bucket-per-tenant (S3 backend) or
  directory-per-tenant (filesystem backend). Dedup is per-tenant.
- **SQL:** Soft partition. All data tables carry `tenant_id`. RLS policies
  enforce filtering. Schema supports separate DB per tenant as a future
  enhanced/enterprise option.
- **Cross-tenant access:** Not possible without explicit sharing mechanism
  (future work).

---

## 3. Roles (RBAC)

Three roles, hierarchical:

| Role | Can do | Includes |
|------|--------|----------|
| `vault:admin` | Manage principals, roles, trusted issuers, org/tenant config, all data operations | vault:write |
| `vault:write` | Ingest, upload, create entries, modify metadata, delete entries | vault:read |
| `vault:read` | Browse, search, download, view metadata | — |

Roles are assigned per principal per tenant:

```sql
CREATE TABLE role_assignment (
    id              SERIAL PRIMARY KEY,
    principal_id    INT NOT NULL REFERENCES principal(id),
    tenant_id       INT NOT NULL REFERENCES tenant(id),
    role            TEXT NOT NULL,       -- 'vault:admin', 'vault:write', 'vault:read'
    granted_by      INT REFERENCES principal(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (principal_id, tenant_id, role)
);
```

---

## 4. Sandbox (Permissions Boundary)

A **sandbox** is a restriction envelope attached to a token. It acts as a
**permissions boundary** (like AWS IAM) — the effective permission is the
intersection of the role's capabilities and the sandbox's restrictions.

### Structure

```
sandbox
├── data scope: collection of saved queries (defines visible entries)
└── capability scope: allowed operations (optional further restriction)
```

### Data Scope

A sandbox references one or more **saved queries**. Each saved query has
predicates that can be:

- **Hard:** explicit file/folder references (entry IDs, container paths)
- **Soft:** wildcards, date ranges, type filters, tag matches

The sandbox's data scope is the **union** of all its saved queries' result sets.

### Capability Scope

Optionally, a sandbox restricts which operations are available, independent of
the role. For example, a `vault:write` token with a sandbox might be limited
to `[read, search]` — the sandbox removes write capability even though the
role grants it.

If no capability restrictions are defined, the sandbox only restricts data
scope (the role's full capabilities apply within the data boundary).

### Enforcement

- Token carries a single sandbox ID (or none = full tenant access)
- Vault resolves sandbox → saved queries → RLS policies at the DB layer
- Entries outside the sandbox's data scope are invisible to all queries
- Operations outside the sandbox's capability scope return 403
- Admin tokens typically have no sandbox

### Tables

```sql
CREATE TABLE saved_query (
    id          SERIAL PRIMARY KEY,
    tenant_id   INT NOT NULL REFERENCES tenant(id),
    name        TEXT NOT NULL,
    predicates  JSONB NOT NULL,         -- {type: "hard"|"soft", conditions: [...]}
    created_by  INT NOT NULL REFERENCES principal(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE sandbox (
    id              SERIAL PRIMARY KEY,
    tenant_id       INT NOT NULL REFERENCES tenant(id),
    name            TEXT NOT NULL,
    capabilities    TEXT[],             -- NULL = unrestricted, or ['read','search','ingest']
    created_by      INT NOT NULL REFERENCES principal(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE sandbox_query (
    sandbox_id      INT NOT NULL REFERENCES sandbox(id),
    saved_query_id  INT NOT NULL REFERENCES saved_query(id),
    PRIMARY KEY (sandbox_id, saved_query_id)
);
```

---

## 5. Tokens (Reference Token Model)

### Design Principle

JWTs are **signed session pointers**, not self-contained authorization
documents. The database is the single authority for:

- Is this session still valid?
- What are the current roles, tenant, sandbox for this principal?

This means:
- Role changes take effect immediately (no waiting for token refresh)
- Sandbox reassignment is instant
- Revocation = delete the session row
- No stale permissions, ever

### JWT Format

Signed with ES256 (instance keypair). Minimal claims:

```json
{
  "iss": "vault:{instance_id}",
  "sub": "{principal_global_id}",
  "jti": "{session_id}",
  "exp": 1707500000,
  "iat": 1707496400
}
```

No roles, tenant_id, sandbox, or org_id in the JWT. All loaded from DB per
request as part of RLS context setup.

### Request Flow

```
1. Request arrives with Bearer JWT
2. Vault verifies ES256 signature (is this token from this instance?)
3. Vault looks up session by jti:
   - Session exists? Not expired? Principal active?
   - Load: principal_id, tenant_id, org_id, role, sandbox_id
4. Set RLS session variables:
   SET LOCAL vault.tenant_id = '...';
   SET LOCAL vault.principal_id = '...';
   SET LOCAL vault.sandbox_id = '...';   -- NULL if no sandbox
   SET LOCAL vault.role = '...';
5. Execute the API query — RLS automatically filters results
6. Return response
```

### Token Types

| Type | Issued by | Lifetime | Sandbox | Use case |
|------|-----------|----------|---------|----------|
| **Session** | `/auth/exchange`, `/auth/passkey` | Configurable (default 24h) | Optional | Interactive user sessions |
| **Delegation** | Authenticated user via API | Configurable (hours to months) | Typically yes | MCP agents, share links, third-party apps |
| **Service** | Admin via API | No expiry (revocable) | Optional | Machine-to-machine, automation, CI/CD |

All token types are DB-backed sessions. Service tokens have no expiry but can
be revoked (delete the row).

### Session Table

```sql
CREATE TABLE session (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),  -- = jti in JWT
    principal_id    INT NOT NULL REFERENCES principal(id),
    tenant_id       INT NOT NULL REFERENCES tenant(id),
    sandbox_id      INT REFERENCES sandbox(id),                 -- NULL = full access
    token_type      TEXT NOT NULL,       -- 'session', 'delegation', 'service'
    expires_at      TIMESTAMPTZ,         -- NULL for service tokens
    refresh_token   TEXT,                -- hashed, for session tokens only
    refresh_expires TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at    TIMESTAMPTZ
);
```

### Revocation

- **Single token:** Delete the session row. Next request with that jti fails
  at step 3.
- **All tokens for a principal:** `DELETE FROM session WHERE principal_id = ?`
- **Deactivate principal:** Set `principal.status = 2`. Session lookup checks
  principal status.
- No revocation lists, no generation counters, no cache invalidation.
  The DB is the authority.

---

## 6. Authentication Methods

All methods end the same way: Vault creates a session row and returns a
signed JWT.

### 6a. Passkey (FIDO2/WebAuthn)

Primary auth method for local Vault access.

- **Registration:** User enrolls passkey during OOBE or via admin UI.
  Public key stored in `identity_link.metadata`.
- **Authentication:** `POST /auth/passkey` — challenge/response.
  Vault verifies signature against stored public key.
- **Resolution:** `(method='passkey', external_sub=credential_id)` →
  principal.

### 6b. OIDC (via Console)

For cloud access. Vault never talks to Cognito directly.

- **Flow:** Console authenticates user via Cognito, then calls
  `POST /auth/exchange` on Vault (server-side, through Gateway) with the
  Cognito ID token.
- **Vault validates:** Signature against trusted issuer's JWKS.
  Issuer must be in Vault's configured trusted issuers list.
- **Resolution:** `(method='oidc', issuer=<cognito_url>, external_sub=<cognito_sub>)`
  → principal. Auto-provision if configured.
- **Result:** Vault creates session, returns JWT to Console. Console passes
  JWT to SPA.

### 6c. Trusted Issuers (ISV / third-party IdP)

Any OIDC-compliant IdP can be configured as a trusted issuer.

```sql
CREATE TABLE trusted_issuer (
    id              SERIAL PRIMARY KEY,
    org_id          INT NOT NULL REFERENCES organization(id),
    name            TEXT NOT NULL,
    discovery_url   TEXT NOT NULL,       -- .well-known/openid-configuration
    client_id       TEXT,                -- expected aud claim
    auto_provision  BOOLEAN NOT NULL DEFAULT false,
    default_role    TEXT DEFAULT 'vault:read',
    claim_mapping   JSONB,              -- {email: "email", display_name: "name", ...}
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Org-scoped: all tenants within an org share the same trusted issuers.
Cognito (via Console) is just one entry, added during instance registration.
ISVs add their own IdP. Fully open — Vault doesn't privilege any issuer.

### 6d. NAS Delegation

For NAS-deployed Vault (Synology, TrueNAS, etc.) where the host OS manages
user identity.

- **Flow:** `POST /auth/nas` with host OS UID.
- **Resolution:** `(method='nas', external_sub=<uid>)` → principal.
- **Trust:** Only accepted from localhost or configured trusted sources.

### 6e. Device Identity

For mobile/desktop apps that register a device keypair.

- **Registration:** On first use, device generates keypair in secure storage
  (Keychain/KeyStore). Public key registered with principal.
- **Authentication:** `POST /auth/device` — signed challenge.
- **Resolution:** `(method='device', external_sub=<device_public_key_fingerprint>)`
  → principal.

### 6f. Recovery Keys

Emergency access via Shamir's Secret Sharing.

- **Setup:** Admin generates recovery key set (3-of-5 shares).
- **Recovery:** `POST /auth/recover` with sufficient shares.
- **Result:** Session with `vault:admin` role. For emergency use only.

---

## 7. RLS Enforcement

### Session Variables

Every API request sets these before executing queries:

```sql
SET LOCAL vault.tenant_id = '42';
SET LOCAL vault.principal_id = '7';
SET LOCAL vault.sandbox_id = '3';       -- or '' if no sandbox
SET LOCAL vault.role = 'vault:write';
```

### Example RLS Policies

```sql
-- Tenant isolation: entries only visible within session's tenant
ALTER TABLE entry ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON entry
    USING (tenant_id = current_setting('vault.tenant_id')::int);

-- Sandbox filtering: if sandbox active, only show matching entries
-- (joins through sandbox → sandbox_query → saved_query → evaluates predicates)
CREATE POLICY sandbox_filter ON entry
    USING (
        current_setting('vault.sandbox_id') = ''
        OR id IN (SELECT entry_id FROM sandbox_visible_entries(
            current_setting('vault.sandbox_id')::int
        ))
    );
```

The `sandbox_visible_entries()` function evaluates the sandbox's saved query
predicates and returns matching entry IDs. Implementation details TBD —
may use materialized views for hard predicates, dynamic queries for soft.

### Write Operations

For write operations (ingest, delete, modify), the sandbox's capability
scope is checked at the application layer before executing:

```java
if (session.sandbox() != null) {
    Sandbox sb = sandboxDao.findById(session.sandboxId());
    if (sb.capabilities() != null && !sb.capabilities().contains(operation)) {
        throw new ForbiddenException("Operation not allowed in sandbox");
    }
}
```

RLS handles data scope; application code handles capability scope.

---

## 8. Gateway Interaction

- **Gateway validates JWT signature** (ES256, via Vault's JWKS at
  `/.well-known/jwks.json`) — confirms token is from this Vault instance.
- **Gateway does NOT resolve authorization** — it can't, since claims are
  DB-backed. Gateway is a pass-through proxy for MVP.
- **Future:** Gateway could cache a minimal permission summary for rate
  limiting by role, but authorization always happens at Vault.

---

## 9. JWKS and Key Management

- **Algorithm:** ES256 (ECDSA P-256)
- **Keypair storage:** Encrypted row in Vault's PostgreSQL database.
  Generated on first boot (`vault init`).
- **Same keypair used for:**
  - Signing Vault JWTs
  - Instance identity (registered with Console, verified by Gateway on
    WebSocket upgrade)
- **JWKS endpoint:** `GET /.well-known/jwks.json` — returns the public key.
  Gateway caches this for signature verification.
- **Rotation:** Generate new keypair, publish both old and new in JWKS
  (overlap period), stop signing with old after all outstanding tokens
  expire. Update public key in Console.

---

## 10. Bootstrap Flow

### Interactive (Web UI)

```
1. User navigates to https://vault.local:8080 (first boot)
2. Setup wizard:
   a. "Welcome to Vault. Let's set up your instance."
   b. "How will you use Vault?" → Personal / Business
   c. "Create your admin passkey" → WebAuthn enrollment
   d. (Optional) "Connect to Libragraph Cloud?" → redirects to Console
3. Vault creates:
   - Organization (name from wizard or default)
   - Tenant (default)
   - Principal (linked to enrolled passkey)
   - Role assignment (vault:admin for default tenant)
   - ES256 keypair (stored in DB)
4. Vault is ready to serve API requests.
```

### Headless (Docker Compose / Terraform / env vars)

```yaml
vault:
  init:
    org-name: "Acme Corp"
    tenant-name: "production"
    auto-provision-issuer: "https://auth.acmedocs.com"
    auto-provision-role: "vault:admin"
```

On first boot, Vault detects no org exists, creates org + tenant + keypair,
and configures the trusted issuer. First user to authenticate via that
issuer is auto-provisioned as admin.
