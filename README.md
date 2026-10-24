# HookFault

A local webhook reliability lab built with Kotlin and Spring Boot.

HookFault is being developed to explore how webhook consumers handle duplicate
events, invalid signatures, and temporary delivery failures.

## Status

The current version provides the backend foundation, PostgreSQL connectivity,
and a Docker Compose environment.

## Stack

- Kotlin targeting Java 21
- Spring Boot 4.1.1 with Spring JDBC and Actuator
- PostgreSQL 16
- Maven 3.9.16 via Maven Wrapper
- Docker Compose

## Run With Docker

Requires Docker Desktop running with Linux containers. The container build
provides Java and Maven; no separate host installation is required.

From the repository root:

```powershell
docker compose up --build
```

Check application health from another PowerShell terminal:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
```

The [health endpoint](http://localhost:8080/actuator/health) returns
`{"status":"UP"}` when the configured health checks pass.

View service status and logs:

```powershell
docker compose ps
docker compose logs backend
```

Stop the services while retaining database data:

```powershell
docker compose down
```

Adding `-v` deletes the database volume and its data.

## Build With Maven Wrapper

Requires JDK 21 or a compatible newer JDK. A separate Maven installation is not
required: the wrapper downloads Maven 3.9.16 on first use, verifies its checksum,
and caches it for subsequent runs. Internet access is required for the initial
download and any uncached build dependencies.

From the repository root, on Windows PowerShell:

```powershell
.\backend\mvnw.cmd -f backend/pom.xml clean verify
```

On Linux or macOS:

```sh
./backend/mvnw -f backend/pom.xml clean verify
```

The wrapper scripts and their version configuration are in `backend/`, next to
`pom.xml`. The Docker build uses the same wrapper and Maven version.

Alternatively, run PostgreSQL in Docker and the backend with Maven:

```powershell
docker compose up -d postgres
.\backend\mvnw.cmd -f backend/pom.xml spring-boot:run
```

Do not run the containerized backend at the same time on port 8080.

## Local Configuration And Safety

Both published ports (8080 and 5432) are bound to loopback only. The database is
named `hookfault` and persists in the `hookfault-postgres` Docker volume.

The default database password, `hookfault`, is a local development placeholder,
not a production credential. To override it, create a local `.env` using
`.env.example`; `.env` is ignored by Git. PostgreSQL only applies this password
when initializing an empty data volume. Changing `.env` alone does not rotate
an existing database password.

Intended for local development with fictional data and endpoints you own or
have permission to test. Do not expose this environment publicly or commit
real secrets.
