# Identity

Authentication and authorization via OIDC token exchange.

See [ADR-019](../../pm/docs/decisions/adr-019-open-core-gateway-model.md) for the
full architectural rationale (open-core model, ISV white-labeling, deployment models).

## Architecture

Vault separates **authentication** (who are you?) from **authorization** (what can you do?).

Any OIDC-compliant IdP can authenticate users. Vault issues its own JWTs carrying
the full authorization context (org, tenant, roles, sandbox).

```
               ┌──────────────┐
               │ External IdP │  (Keycloak, Cognito, Okta, Google, etc.)
               │              │  Authenticates the user.
               └──────┬───────┘
                      │ IdP JWT (thin: sub, iss, email)
                      ▼
               ┌──────────────┐
               │    Vault     │  POST /auth/exchange
               │              │  Validates IdP JWT, looks up principal,
               │              │  loads org/tenant/roles/sandbox from DB.
               └──────┬───────┘
                      │ Vault JWT (fat: sub, org_id, tenant_id, roles, sandbox)
                      ▼
               ┌──────────────┐
               │  Vault API   │  Authorization: Bearer <vault-jwt>
               │              │  Stateless validation — no DB lookup per request.
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

External IdPs are registered as trusted issuers (org-scoped):

```
POST /admin/issuers
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

## Quarkus OIDC Integration

Quarkus OIDC validates the Vault-issued JWTs on API endpoints:

```properties
# Vault's own JWKS for API token validation
quarkus.oidc.auth-server-url=http://localhost:8443
quarkus.oidc.client-id=vault-api
quarkus.oidc.application-type=service  # API-only (no web login flow)
```

For dev mode with Keycloak as the external IdP:

```properties
# application-dev.properties
quarkus.keycloak.devservices.enabled=true
quarkus.keycloak.devservices.realm-path=dev-realm.json
vault.auth.issuer.discovery-url=http://localhost:8180/realms/vault-dev/.well-known/openid-configuration
```

See [Quarkus OIDC Guide](https://quarkus.io/guides/security-oidc-bearer-token-authentication).

## Vault JWT Claims

The Vault JWT carries the full security context for each request:

```java
@Inject SecurityIdentity identity;
@Inject JsonWebToken jwt;

// Standard claims
String principalId = jwt.getSubject();       // "principal_123"
Set<String> roles = identity.getRoles();     // ["vault:read", "vault:write"]

// Vault claims
String orgId = jwt.getClaim("org_id");       // "org_acmedocs"
String tenantId = jwt.getClaim("tenant_id"); // "t_acme_corp"
String sandbox = jwt.getClaim("sandbox");    // null or "sandbox_123"
```

Claims are populated from Vault's DB during token exchange — not from the
external IdP. The external IdP only provides authentication (`iss`, `sub`).

## Role Model

| Role | Scope | Permissions |
|------|-------|------------|
| `vault:admin` | Org | Full control, manage tenants/principals/issuers |
| `vault:write` | Tenant | Ingest, delete |
| `vault:read` | Tenant | Query, download, reconstruct |
| `vault:sandbox` | Sandbox | Read within sandbox filter |

Roles are assigned in Vault's DB and carried in the Vault JWT.
Managed via the Management API (`/admin/tenants/{id}/principals`).

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

Both are Vault-signed JWTs validated the same way. They differ in scope and lifetime.

> **DEPENDENCY:** Delegation token issuance requires [REST](REST.md) API endpoints.

## Dev Mode

Quarkus Dev Services can auto-provision a Keycloak container as the external IdP:

```properties
# application-dev.properties
quarkus.keycloak.devservices.enabled=true
quarkus.keycloak.devservices.realm-path=dev-realm.json
```

See [Quarkus Keycloak Dev Services](https://quarkus.io/guides/security-openid-connect-dev-services).
