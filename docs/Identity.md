# Identity

Authentication and authorization overview.

> **Canonical reference:** [Authentication.md](Authentication.md) is the single
> source of truth for the token model, session management, RLS enforcement, and
> bootstrap flow. This document is a summary and entry point.

See [ADR-019](../../pm/docs/decisions/adr-019-open-core-gateway-model.md) for the
full architectural rationale (open-core model, ISV white-labeling, deployment models).

## Architecture

Vault separates **authentication** (who are you?) from **authorization** (what can you do?).

Any OIDC-compliant IdP can authenticate users. Vault uses **reference tokens** —
JWTs are signed session pointers; the database is the single authority for session
validity and authorization context.

```
               ┌──────────────┐
               │ External IdP │  (Cognito, Okta, Google, etc.)
               │              │  Authenticates the user.
               └──────┬───────┘
                      │ IdP JWT (thin: sub, iss, email)
                      ▼
               ┌──────────────┐
               │    Vault     │  POST /auth/exchange
               │              │  Validates IdP JWT, looks up principal,
               │              │  creates DB session, returns Vault JWT.
               └──────┬───────┘
                      │ Vault JWT (reference: sub, jti, exp — no claims)
                      ▼
               ┌──────────────┐
               │  Vault API   │  Authorization: Bearer <vault-jwt>
               │              │  Looks up session by jti, loads authorization
               │              │  from DB, sets RLS session variables per request.
               └──────────────┘
```

- No local accounts (no password storage in Vault — see [ADR-014](../../pm/docs/decisions/adr-014-authentication-principals.md))
- Vault manages principals, role assignments, and tenant membership in its own DB
- Any OIDC provider can be registered as a trusted issuer
- Passkeys/WebAuthn for local auth (no internet needed)
- Identity sync via Gateway (SaaS deployments)

## Token Exchange

All authentication methods produce a Vault-issued JWT via token exchange.

### OIDC Exchange

```
POST /auth/exchange
Authorization: Bearer <idp-jwt>
X-Vault-Tenant: <optional-tenant-hint>

Response:
{
  "access_token": "<vault-jwt>",
  "refresh_token": "<refresh-token>",
  "token_type": "Bearer",
  "expires_in": 3600
}
```

Vault validates the IdP JWT against the trusted issuer's JWKS, looks up the
principal in its DB, and issues a Vault JWT.

### Other Auth Methods

| Method | Endpoint | Notes |
|--------|----------|-------|
| Passkey (FIDO2) | `POST /auth/passkey` | Challenge/response, no external IdP needed |
| NAS delegation | `POST /auth/nas` | Maps NAS UID → principal |
| Device identity | `POST /auth/device` | Signed challenge with device key |
| Recovery keys | `POST /auth/recover` | Emergency access, Shamir share reconstruction |

All methods produce the same Vault JWT format. See ADR-019 for details.

## Trusted Issuer Configuration

External IdPs are registered as trusted issuers (org-scoped). See
[Authentication.md §6c](Authentication.md#6c-trusted-issuers-isv--third-party-idp)
for the `trusted_issuer` table schema.

```
POST /sys/issuers
Authorization: Bearer <admin-vault-jwt>
{
  "name": "AcmeDocs Production",
  "discovery_url": "https://auth.acmedocs.com/.well-known/openid-configuration",
  "client_id": "vault-integration",
  "auto_provision": true,
  "default_role": "vault:read",
  "claim_mapping": {
    "email": "email",
    "display_name": "name",
    "external_org": "custom:customer_id"
  }
}
```

For private vaults, trusted issuers can also be configured via `application.properties`:

```properties
vault.auth.issuer.discovery-url=https://accounts.google.com/.well-known/openid-configuration
vault.auth.issuer.client-id=vault-app
vault.auth.issuer.auto-provision=true
vault.auth.issuer.default-role=vault:read
```

## Authentication Implementation Status

**Not yet implemented.** Authentication will use:

- **Cloud deployments:** Cognito via Console (passkey, social login, email OTP) — see [ADR-020](../../pm/docs/decisions/adr-020-global-identity-and-console.md), [ADR-030](../../pm/docs/decisions/adr-030-invitations-and-roles.md)
- **Local vaults:** Passkey/WebAuthn (primary), password+TOTP (fallback) — see [ADR-030](../../pm/docs/decisions/adr-030-invitations-and-roles.md) §10a

When implemented, Quarkus OIDC will validate Vault-issued JWTs on API endpoints.
See [Authentication.md](Authentication.md) for the complete reference token model.

## Vault JWT Claims

Vault JWTs are **reference tokens** — minimal signed pointers to a DB-backed session:

```json
{
  "iss": "vault:{instance_id}",
  "sub": "{principal_global_id}",
  "jti": "{session_id}",
  "exp": 1707500000,
  "iat": 1707496400
}
```

No roles, tenant, sandbox, or org claims in the JWT. Authorization context is
loaded from the database per request as part of RLS session variable setup.
See [Authentication.md §5](Authentication.md) for the full reference token model.

## Role Model

| Role | Scope | Permissions |
|------|-------|------------|
| `vault:admin` | Tenant | Full control, manage principals/issuers, all data operations |
| `vault:write` | Tenant | Ingest, upload, modify metadata, delete entries |
| `vault:read` | Tenant | Browse, search, download, view metadata |

Roles are hierarchical (`admin` ⊃ `write` ⊃ `read`), assigned per principal
per tenant, and stored in Vault's DB. Sandbox is a separate permissions boundary,
not a role — see [Authentication.md §4](Authentication.md).

## Token Types

Vault issues two kinds of JWTs:

### Session Tokens (from `/auth/exchange`)

Issued during authentication. Short-lived with refresh capability.

- **Lifetime:** 1 hour access token + 90-day refresh token
- **Scope:** Full principal access within the selected tenant
- **Refresh:** `POST /auth/refresh` issues a new access token and rotates the refresh token
- **Use case:** Interactive sessions (web UI, CLI, MCP)

### Delegation Tokens (from `/api/tokens`)

Created by authenticated users for limited-scope access.

```
POST /api/tokens
Authorization: Bearer <vault-jwt>
{
  "scope": "tenant:abc123",
  "permissions": ["read"],
  "expiresIn": "7d"
}
```

- **Lifetime:** Configurable (hours to months)
- **Scope:** Restricted permissions and/or resource scope
- **Use case:** Service tokens for LLMs, share links, automation, MCP access
- **Storage:** Token hash stored in DB for revocation

All token types are DB-backed sessions. Service tokens have no expiry but can
be revoked (delete the session row). See [Authentication.md §5](Authentication.md)
for the session table schema.

> **DEPENDENCY:** Delegation token issuance requires [REST](REST.md) API endpoints.

## Dev Mode

**Authentication not yet implemented.** For development, the DiagnosticResource
operates without authentication.

When authentication is implemented, dev mode will use the same authentication
methods as production:
- Mock Cognito tokens for OIDC exchange testing
- Passkey test credentials for WebAuthn flow testing

See [Authentication.md](Authentication.md) for implementation plan.
