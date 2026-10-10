# HookFault

A local webhook reliability lab, built step by step with Kotlin and Spring Boot.

The planned MVP will send fictional events to an included demo receiver, inject
controlled failures, and compare delivery attempts with the receiver's actual
side effects. The receiver will have SAFE and deliberately BROKEN modes.

## Current Stage

Initial bootstrap only:

- Kotlin application targeting Java 21, with Spring Boot 4.1.1
- Maven build and executable JAR
- PostgreSQL 16 connection configuration
- Spring Boot Actuator health endpoint
- Docker Compose for the backend and PostgreSQL

There are no webhook APIs, business tables, scenarios, automated tests, Swagger
UI, or dashboard yet. These are future work, not completed features.
See the [30-commit roadmap](docs/ROADMAP.md).

## Run With Docker

Prerequisite: Docker Desktop running with Linux containers. Java, Kotlin, Maven,
and PostgreSQL do not need to be installed on the host for this path; the images
provide the build tools and runtime.

From the repository root:

```powershell
docker compose up --build
```

Compose starts PostgreSQL, waits for its health check, and starts the backend.
The first build downloads images and Maven dependencies and can take a while.
The terminal stays attached to the logs; this is normal.

In another terminal:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
```

Or open [the health endpoint](http://localhost:8080/actuator/health) in a browser.
An HTTP 200 response with `{"status":"UP"}` means the health checks, including
the database connection, passed. The root URL has no page yet.

Useful commands from the repository root:

```powershell
docker compose ps
docker compose logs backend
docker compose down
```

`up --build` builds the application image and starts the services. `down` stops
and removes their containers, but keeps the database volume. Do not add `-v`
unless you intend to delete the local database data.

## Build Without Docker

Prerequisites: JDK 21 or a compatible newer JDK, and Maven 3.6.3 or newer.
The Kotlin compiler is downloaded by Maven; no separate Kotlin installation is
required.

```powershell
cd backend
mvn clean verify
```

Maven resolves dependencies, compiles Kotlin for Java 21, runs available tests,
and packages the executable JAR. At this initial stage there are no tests, so a
successful build does not establish runtime or database connectivity.

To run the backend outside Docker, first start just PostgreSQL from the root:

```powershell
docker compose up -d postgres
cd backend
mvn spring-boot:run
```

Do not run the containerized backend at the same time on port 8080.

## Structure

```text
HookFault/
  backend/
    src/main/kotlin/com/hookfault/HookFaultApplication.kt
    src/main/resources/application.yml
    pom.xml
    Dockerfile
    .dockerignore
  docs/ROADMAP.md
  docker-compose.yml
  .env.example
  .gitignore
  README.md
```

`backend/` contains the Kotlin application, not the whole repository. Compose
coordinates the local services. `docs/` holds the development plan. A demo
receiver will be added as a separate component later.

## Local Configuration And Safety

Both published ports (8080 and 5432) are bound to loopback only. The database is
named `hookfault` and persists in the `hookfault-postgres` Docker volume.

The default database password, `hookfault`, is a local development placeholder,
not a production credential. To override it, create a local `.env` using
`.env.example`; `.env` is ignored by Git. PostgreSQL only applies this password
when initializing an empty data volume. Changing `.env` alone does not rotate
an existing database password.

Use fictional payloads and endpoints you own or have permission to test. This
is not a public service, production security product, or load-testing tool.
Never commit real secrets or use real personal, financial, or production data.
