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

The `verify` phase also runs the integration tests and requires Docker Desktop
with Linux containers to be running.

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

## Tests

With Docker Desktop running, execute from the repository root:

```powershell
.\backend\mvnw.cmd -f backend/pom.xml test
```

The startup integration tests launch the application on a random HTTP port and
use a real PostgreSQL 16 database managed by Testcontainers. They verify the
database connection and the HTTP health response, including database health.

Testcontainers starts an isolated database on an available port and cleans it
up after the test run. No `docker compose up` is needed, and the tests do not
use the Compose database or its persistent volume. The first run may take
longer while Docker downloads the required images. Without Docker, the tests
fail rather than being silently skipped.

Maven and the forked test JVM each use a 64 MiB initial heap and a 512 MiB
maximum heap. Kotlin compilation runs inside the Maven process without a
separate compiler daemon. These limits apply to Java heaps, not total process
memory; Docker and the operating system also need available memory.

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
