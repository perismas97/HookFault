# HookFault

A local webhook reliability lab built with Kotlin and Spring Boot.

HookFault is being developed to explore how webhook consumers handle duplicate
events, invalid signatures, and temporary delivery failures.

## Status

The current version supports webhook endpoint registration and paginated
inspection, a local destination allowlist, consistent API errors, Swagger UI,
bounded HTTP request bodies, a versioned event model, and a PostgreSQL schema
managed by Flyway. A standalone demo receiver exposes a local webhook route.

## Stack

- Kotlin targeting Java 21
- Spring Boot 4.1.1 with Spring JDBC and Actuator
- PostgreSQL 16
- Flyway database migrations
- Springdoc OpenAPI 3.2.0 with Swagger UI
- Maven 3.9.16 via Maven Wrapper
- Docker Compose

## Run With Docker

Requires Docker Desktop running with Linux containers. The container build
provides Java and Maven; no separate host installation is required.

From the repository root:

```powershell
.\scripts\New-DemoSigningKey.ps1
docker compose up --build
```

Check application health from another PowerShell terminal:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
Invoke-RestMethod http://localhost:8090/actuator/health
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

On Linux or macOS, ensure `unzip` is available so the wrapper uses the
ZIP distribution that matches the pinned checksum:

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

## Demo Webhook Receiver

The receiver is a separate Kotlin/Spring Boot application in `demo-receiver/`,
with its own executable JAR and no database dependency. To start only the
receiver, without starting the backend or PostgreSQL:

```powershell
.\scripts\New-DemoSigningKey.ps1
docker compose up --build -d demo-receiver
Invoke-RestMethod http://127.0.0.1:8090/actuator/health
curl.exe -i -H "Content-Type: application/json" -d '{}' http://127.0.0.1:8090/webhooks
```

The health endpoint reports `UP`. With the demo key configured, the unsigned
POST returns `401 Unauthorized`. The receiver reads raw bytes without parsing
the JSON and returns `413` for bodies larger than 16 KiB, including chunked
bodies. It does not log, echo, or persist the body or signing headers.

From another Compose service, use `http://demo-receiver:8090/webhooks`. From
the host, use `http://127.0.0.1:8090/webhooks`. Compose publishes the port on
loopback only.

Build and run the receiver's HTTP tests with the existing Maven wrapper
(no Docker or PostgreSQL needed):

```powershell
.\backend\mvnw.cmd -f demo-receiver/pom.xml clean verify
```

## Register A Webhook Endpoint

With the backend running in Compose, register the receiver by service name:

```powershell
$body = @{
    name = "Demo receiver"
    url = "http://demo-receiver:8090/webhooks"
} | ConvertTo-Json

$endpoint = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/webhook-endpoints -ContentType "application/json" -Body $body
$endpoint
```

A successful request returns HTTP `201 Created` with `id`, `name`, `url`, and
`createdAt`. The server generates the UUID, and the database provides the
creation timestamp. Both values are returned from the stored record.
The `Location` response header contains the relative URL for retrieving it.

`name` is required, non-blank, and limited to 100 characters. `url` is required,
limited to 2048 characters, and must be an absolute HTTP or HTTPS URL with a
host and a valid port if specified. URLs with credentials or fragments are
rejected. The URL's origin must also be explicitly allowed below. Invalid or
disallowed destinations return HTTP `400 Bad Request`.

Registration only stores the endpoint. It does not resolve the hostname,
contact the receiver, or send a webhook; the receiver need not be running.
For a backend running on the host, register `http://127.0.0.1:8090/webhooks`
instead.

## Inspect Webhook Endpoints

| Method | Path | Result |
| --- | --- | --- |
| `POST` | `/api/v1/webhook-endpoints` | Register an endpoint; `201 Created` |
| `GET` | `/api/v1/webhook-endpoints` | List a bounded page; `200 OK` |
| `GET` | `/api/v1/webhook-endpoints/{id}` | Retrieve a stored endpoint; `200 OK` |

List endpoints and retrieve the endpoint registered above:

```powershell
Invoke-RestMethod "http://localhost:8080/api/v1/webhook-endpoints?limit=20&offset=0"
Invoke-RestMethod "http://localhost:8080/api/v1/webhook-endpoints/$($endpoint.id)"
```

The list response has `items`, `limit`, `offset`, and `hasMore`. Each item has
only `id`, `name`, `url`, and `createdAt`. `limit` defaults to 20 and accepts
1 through 100; `offset` defaults to 0 and accepts non-negative 32-bit integers.
Use `offset + limit` to request the next page when `hasMore` is true. An empty
result returns an empty `items` array, not a `404`.

Endpoints are ordered by `createdAt` descending, then UUID descending to break
ties. Offset pagination is not a snapshot: concurrent registrations can move
items between pages. Lookup IDs must use canonical UUID format; uppercase hex
digits are accepted. A valid but unknown UUID returns `404`.

Inspection returns stored records even if a URL is no longer allowed by the
current destination policy. Reading a record does not contact its receiver or
authorize delivery to it.

## API Errors

Application API errors use `application/problem+json` with a stable `code`:

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "No webhook endpoint exists with this ID.",
  "instance": "/api/v1/webhook-endpoints/00000000-0000-4000-8000-000000000001",
  "code": "ENDPOINT_NOT_FOUND",
  "errors": []
}
```

| HTTP status | Codes |
| --- | --- |
| `400` | `VALIDATION_FAILED`, `INVALID_JSON`, `DESTINATION_NOT_ALLOWED`, `INVALID_ENDPOINT_ID`, `INVALID_PAGINATION`, `INVALID_PARAMETER`, `INVALID_REQUEST` |
| `404` | `ENDPOINT_NOT_FOUND`, `ROUTE_NOT_FOUND` |
| `405` | `METHOD_NOT_ALLOWED` (includes the `Allow` header) |
| `406` | `NOT_ACCEPTABLE` |
| `413` | `REQUEST_TOO_LARGE` |
| `415` | `UNSUPPORTED_MEDIA_TYPE` |
| `500` | `INTERNAL_ERROR` |

Validation errors include an `errors` array of `{ "field": "name", "message":
"must not be blank" }` entries. Other errors return an empty array. Responses
omit rejected body/query values, exception messages, SQL, and stack traces.
`instance` is the request path without its query string. Use `code` for client
logic rather than parsing the explanatory English text.

## Swagger And OpenAPI

With the backend running, open [Swagger UI](http://localhost:8080/swagger-ui.html).
The [OpenAPI JSON](http://localhost:8080/v3/api-docs) describes the endpoint APIs,
request limits, pagination, public response fields, and error schemas. Actuator
endpoints are excluded from this specification.

Swagger UI loads its assets locally and uses the local API definition, without
the sample Petstore definition or remote schema validation. Its API calls use
the same destination policy and validation as any other client. A POST from
Swagger UI creates a real record in the local database.

## Webhook Event Envelope

The `com.hookfault.events` package provides versioned events for in-process
creation. A `demo.user.created` event has this structure (formatted here for
readability):

```json
{
  "schemaVersion": 1,
  "id": "00000000-0000-4000-8000-000000000001",
  "type": "demo.user.created",
  "occurredAt": "2026-11-06T10:00:00.123456789Z",
  "runId": "00000000-0000-4000-8000-000000000002",
  "payload": {
    "userId": "00000000-0000-4000-8000-000000000003",
    "displayName": "Demo user"
  }
}
```

| Field | Meaning |
| --- | --- |
| `schemaVersion` | Envelope format version; currently `1` |
| `id` | Logical event UUID, retained when the same event is sent again |
| `type` | Event kind; currently `demo.user.created` |
| `occurredAt` | Original event time as a UTC ISO-8601 instant |
| `runId` | Scenario-run UUID supplied by the caller |
| `payload` | Typed fictional user data: UUID `userId` and `displayName` |

All six envelope fields and both payload fields are required. Unsupported
versions and event types are rejected when reading the model. `displayName`
must be non-blank and at most 100 characters. Use fictional data only; the
built-in factory uses the label `Demo user`.

`WebhookEventFactory.userCreated(runId, userId)` generates a new event UUID and
uses a UTC clock for its timestamp. `WebhookEventSerializer.serialize(event)`
produces compact UTF-8 JSON with a fixed field order, UTC textual timestamps,
and the existing 16 KiB payload limit. These writer settings do not change the
application's REST JSON settings.

The returned `SerializedWebhookEvent` retains the envelope and its original
bytes. `bodyBytes()` returns defensive copies of those bytes, and `size` is
their byte length. Reuse this serialized result for retries or deliberate
duplicates. Calling the factory again creates a different logical event,
even if its user and run IDs are unchanged.

This is HookFault's wire format, not a general JSON canonicalization algorithm.
Equivalent JSON can have different bytes after changes to whitespace, escaping,
or property order; retain the original bytes rather than parse and reserialize
them for signature verification.

## Webhook Signing

`WebhookEventSigner.sign(event, secret, sentAt)` signs a serialized event using
HMAC-SHA256. The caller supplies raw secret bytes (at least 32 bytes) and a send
time. Use a randomly generated shared secret; never commit it or include it
in an HTTP header. The signer has no default key.

| Header | Value |
| --- | --- |
| `X-HookFault-Timestamp` | Send time in decimal Unix seconds, without fractional digits |
| `X-HookFault-Signature` | `v1=` followed by 64 lowercase hexadecimal digits |

The signed input is the ASCII timestamp, one literal period, then the exact
JSON body bytes, with no extra separator or trailing newline:

```text
HMAC-SHA256(secret, ASCII(timestamp + ".") || rawBody)
```

`||` means byte concatenation. The `v1=` prefix identifies the signing format,
independently of the event's `schemaVersion`. The returned `SignedWebhookEvent`
holds both the headers and the serialized event so the same body can be used
without re-encoding it. A retry can sign the original body with a new send
timestamp; it retains the original event ID and `occurredAt`.

### Shared Demo Signing Key

Run the key generator before starting Compose. It creates
`.secrets/demo-signing-key` with 32 cryptographically random raw bytes, prints
no key material, and leaves an existing key unchanged. On Linux or macOS, the
equivalent is:

```sh
(umask 077; mkdir -p .secrets && set -C && openssl rand 32 > .secrets/demo-signing-key)
```

Compose mounts this same file into the backend and receiver as
`/run/secrets/demo_signing_key`. The `HOOKFAULT_DEMO_SIGNING_KEY_FILE`
environment variable contains only the path. The secret directory is ignored
by Git and excluded from the Docker build contexts. Keep the file local, do
not put it in HTTP headers, and never reuse production credentials for this lab.

For direct launches outside Compose, set `HOOKFAULT_DEMO_SIGNING_KEY_FILE` to
the local file path in both processes. A configured file must be readable and
exactly 32 bytes; otherwise startup fails without logging its contents. If the
setting is absent, both apps can start, but configured signing is unavailable.
The backend signer can use the loaded key with `sign(event, sentAt)`.
Recreate both containers after replacing the key so both load the new bytes.

### Receiver Verification

The receiver requires exactly one of each signing header. The timestamp must
be a nonnegative decimal Unix second with no leading zeros (except `0`).
It must be within 300 seconds of the receiver's current Unix second, including
both boundaries. The receiver computes the HMAC over the raw body using the
format above and compares the two 32-byte digests with `MessageDigest.isEqual`.

| Result | Response |
| --- | --- |
| Valid signature and timestamp | `204 No Content` |
| Missing, duplicate, or malformed headers; invalid signature; timestamp outside the window | `401 Unauthorized` |
| No signing key configured | `503 Service Unavailable` |
| Body over 16 KiB | `413 Content Too Large` |

A `204` acknowledges verification only. It does not parse or store the event.
The timestamp window limits the age of accepted requests; it does not
deduplicate deliveries within the window.

## Destination And Request Policy

The default allowlist contains these exact origins:

| Origin | Intended receiver location |
| --- | --- |
| `http://localhost:8090` | Same host or container as the backend |
| `http://127.0.0.1:8090` | Same host or container, IPv4 loopback |
| `http://[::1]:8090` | Same host or container, IPv6 loopback |
| `http://demo-receiver:8090` | Docker service named `demo-receiver` |
| `http://host.docker.internal:8090` | Host machine from Docker Desktop |

Inside a container, `localhost` refers to that container, not to Windows. For a
receiver running on Windows while the backend runs in Docker Desktop, register
`http://host.docker.internal:8090/webhooks` instead of `localhost`.

Matching uses the parsed scheme, hostname, and effective port. Paths and query
strings do not grant access to a different origin. Other ports, private-network
addresses, public hosts, wildcard hosts, credentials, and fragments are not
accepted by default. Hostnames are case-insensitive; alternate numeric IP forms,
trailing-dot hostnames, and encoded hostnames are not aliases in the allowlist.

To use a different demo port, replace `hookfault.webhooks.allowed-origins` in
`backend/src/main/resources/application.yml` with explicit origins. Configuration
is restricted to the five supported hostnames above; an empty list or an origin
with a path, query, credentials, or fragment prevents application startup. There
is no API for changing this policy. Restrict the list to the receivers you use.

Incoming HTTP request bodies and prepared outbound JSON payloads are limited
to **16 KiB (16,384 bytes)**, independently of the field character limits. An
oversized incoming body returns HTTP `413 Content Too Large` before JSON
deserialization or database writes. The limit also applies when a request has
no `Content-Length`, including chunked requests.

The outbound request factory rechecks the destination, including URLs loaded
from existing database records. The HTTP client never follows redirects, even
to another allowed origin, and does not use system proxies. Its connection
timeout is 2 seconds; prepared requests have a 5-second HTTP request timeout.
These settings do not make the request timeout a deadline for consuming an
arbitrary streamed response body.

This is a local-demo policy, not a production SSRF sandbox. It trusts the
machine's loopback configuration and Docker's hostname resolution. Only run
receivers you control, and do not expose the backend to untrusted callers.

## Database Migrations

Flyway applies versioned SQL migrations automatically when the backend starts
and records their versions and checksums in `flyway_schema_history`.

Migration files are in `backend/src/main/resources/db/migration/`.
`V1__create_webhook_endpoints.sql` creates the `webhook_endpoints` table:

| Column | Type | Constraints |
| --- | --- | --- |
| `id` | UUID | Primary key |
| `name` | VARCHAR(100) | Required, non-blank |
| `url` | VARCHAR(2048) | Required, non-blank |
| `created_at` | TIMESTAMPTZ | Required, defaults to the database transaction timestamp |

Already-applied migrations are not executed again on restart. Add a new
versioned migration for subsequent schema changes rather than editing one
that has already been applied.

## Tests

With Docker Desktop running, execute from the repository root:

```powershell
.\backend\mvnw.cmd -f backend/pom.xml test
```

The startup integration tests launch the application on a random HTTP port and
use a real PostgreSQL 16 database managed by Testcontainers. They verify the
database connection and the HTTP health response, including database health.

Migration integration tests also verify the schema, its constraints, Flyway
validation, and preservation of existing data when migrations run again.

Request validation, controller, destination policy, request-size, and HTTP
client tests run without Docker:

```powershell
.\backend\mvnw.cmd -f backend/pom.xml "-Dtest=ApiErrorContractTest,RegisterWebhookEndpointRequestValidationTest,WebhookEndpointControllerTest,WebhookEndpointServiceTest,WebhookDestinationPolicyTest,ApiRequestBodyLimitFilterTest,WebhookHttpPolicyTest,RequestLimitsHttpTest" test
```

Controller tests use Mockito's subclass mock maker with reflection-based member
access for Spring service mocks, without dynamically attaching a Java agent.

Registration integration tests use real HTTP requests and PostgreSQL to verify
persisted responses, rejection of invalid input without database writes, and
absence of outgoing webhooks during registration.

Local HTTP stubs verify that 301, 302, 303, 307, and 308 responses never trigger
a second request, including relative redirects and redirects to allowed origins.
The tests also cover request timeouts, destination parser bypass attempts,
exact-size bodies, multibyte input, and bodies sent without `Content-Length`.

Query integration tests verify stored-record lookup, the registration `Location`
header, stable ordering, pagination boundaries, empty results, and invalid IDs.
Error contract tests verify the shared response shape and that sensitive input
is not echoed. Real HTTP tests also check the generated OpenAPI schemas and
that Swagger UI and its assets are served successfully.

Event tests run independently without Docker:

```powershell
.\backend\mvnw.cmd -f backend/pom.xml "-Dtest=WebhookEventEnvelopeTest,WebhookEventFactoryTest,WebhookEventSerializerTest" test
```

They verify required metadata, schema versions, fictional payload constraints,
the exact version-one JSON bytes, Unicode/escaping, round trips, and defensive
byte copies. A fixed test clock separates new-event identity from repeated
reads of the same serialized event.

Signing tests also run without Docker:

```powershell
.\backend\mvnw.cmd -f backend/pom.xml "-Dtest=WebhookEventSignerTest" test
```

They cover a known [RFC 4231 vector](https://www.rfc-editor.org/rfc/rfc4231#section-4.2),
an independent UTF-8 webhook vector, and changes to the body, timestamp, and key.

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

The published ports (8080, 8090, and 5432) are bound to loopback only. The database is
named `hookfault` and persists in the `hookfault-postgres` Docker volume.

The default database password, `hookfault`, is a local development placeholder,
not a production credential. To override it, create a local `.env` using
`.env.example`; `.env` is ignored by Git. PostgreSQL only applies this password
when initializing an empty data volume. Changing `.env` alone does not rotate
an existing database password.

Intended for local development with fictional data and endpoints you own or
have permission to test. Do not expose this environment publicly or commit
real secrets.
