# Dev Setup

## Prerequisites

- **Java 25+** (toolchain auto-downloads if needed via Gradle)
- **Docker Desktop** with WSL integration enabled (or Docker Engine on Linux)

## Quick Start

### 1. Start persistent dev services

```bash
cd vault/
docker compose up -d
docker compose ps     # verify all services healthy
```

### 2. Run the application (dev mode)

```bash
./gradlew :app:quarkusDev -Dquarkus.profile=dev
```

### 3. Verify

```bash
curl localhost:8080/api/diagnostic/ping    # {"status":"ok","message":"Vault is running"}
curl localhost:8080/api/diagnostic/info    # name, version, java, profile
curl localhost:8080/q/health/ready         # {"status":"UP",...}
```

### 4. Run tests (no Docker Compose needed)

```bash
./gradlew test
```

Tests use Quarkus DevServices (Testcontainers) to auto-provision ephemeral PostgreSQL.

## Services

| Service | URL | Credentials |
|---------|-----|-------------|
| PostgreSQL | `localhost:5432` | `vault` / `vault_dev_password` / db: `vault` |
| MinIO API | `localhost:9000` | `minioadmin` / `minioadmin` |
| MinIO Console | `localhost:9001` | `minioadmin` / `minioadmin` |
| Keycloak Admin | `localhost:8180/admin` | `admin` / `admin` |

### Direct database access

```bash
psql -h localhost -U vault -d vault
# password: vault_dev_password
```

## Profiles

| Profile | Data source | Object store | Use case |
|---------|------------|--------------|----------|
| `dev` | Docker Compose PostgreSQL | Docker Compose MinIO | Local development |
| `test` | Testcontainers (ephemeral) | Filesystem (temp dir) | Automated tests |

## Cleanup

```bash
docker compose down       # stop services (data preserved)
docker compose down -v    # stop services AND delete volumes
```
