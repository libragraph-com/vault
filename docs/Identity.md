# Identity

Authentication and authorization via Keycloak.

## Architecture

```
User ──► Keycloak ──► JWT ──► Vault API
                                 │
                           SecurityIdentity
                           (tenantId, orgId, roles)
```

Vault does **not** manage user accounts. Keycloak is the sole identity provider.

- No local accounts (no password storage in Vault)
- Passkeys/WebAuthn for local system admin (OS permissions to create/manage Keycloak)
- Identity sync via Gateway (SaaS deployments)
- OAuth/OIDC callback via Gateway

## Quarkus OIDC Integration

Quarkus has first-class OIDC support via `quarkus-oidc`:

```properties
quarkus.oidc.auth-server-url=http://localhost:8180/realms/vault-dev
quarkus.oidc.client-id=vault-app
quarkus.oidc.credentials.secret=${OIDC_SECRET}
quarkus.oidc.application-type=service  # API-only (no web login flow)
```

See [Quarkus OIDC Guide](https://quarkus.io/guides/security-oidc-bearer-token-authentication).

## JWT Claims

The JWT carries the security context for each request:

```java
@Inject SecurityIdentity identity;
@Inject JsonWebToken jwt;

// Standard claims
String userId = jwt.getSubject();
Set<String> roles = identity.getRoles();

// Custom claims (Keycloak mapper)
String orgId = jwt.getClaim("org_id");
String tenantId = jwt.getClaim("tenant_id");
```

> **OPEN QUESTION:** Should org_id and tenant_id be JWT claims (set by
> Keycloak mapper) or request headers (set by gateway)? JWT claims are
> cryptographically signed. Headers are simpler but require gateway trust.

## Role Model

| Role | Scope | Permissions |
|------|-------|------------|
| `vault:admin` | Org | Full control |
| `vault:write` | Tenant | Ingest, delete |
| `vault:read` | Tenant | Query, download, reconstruct |
| `vault:sandbox` | Sandbox | Read within sandbox filter |

Roles are assigned in Keycloak and carried in the JWT.

## Token API

Users can create scoped access tokens:

```
POST /api/tokens
{
  "scope": "tenant:abc123",
  "permissions": ["read"],
  "expiresIn": "7d"
}
```

Tokens are JWTs signed by Vault (not Keycloak) for limited-scope delegation.

> **DEPENDENCY:** Token issuance requires [REST](REST.md) API endpoints.
> Token validation needs a shared signing key or Vault-as-issuer in Keycloak.

## Dev Mode

Quarkus Dev Services can auto-provision a Keycloak container:

```properties
# application-dev.properties
quarkus.keycloak.devservices.enabled=true
quarkus.keycloak.devservices.realm-path=dev-realm.json
```

See [Quarkus Keycloak Dev Services](https://quarkus.io/guides/security-openid-connect-dev-services).
