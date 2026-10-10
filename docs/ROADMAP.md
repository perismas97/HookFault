# HookFault Development Roadmap

## Target

A backend-focused, reproducible GitHub demo of webhook reliability:

- HookFault creates fictional events and delivers them to a local demo receiver.
- Scenarios cover normal delivery, duplicates, invalid signatures, temporary
  failures, timeouts, and out-of-order events.
- The receiver has SAFE and deliberately BROKEN behavior for comparison.
- Reports show delivery evidence AND observed consumer side effects.

An HTTP 200 is evidence of HTTP acceptance, not proof of idempotency or correct
business processing. A duplicate event should cause only one fictional welcome
notification in SAFE mode. No real emails or payments are involved.

## Commit Plan

This is a plan for 30 meaningful commits, including the initial bootstrap. It is
not a claim that all steps are implemented. Only step 1 belongs to the initial
snapshot. Later steps are implemented, explained, and verified progressively.
Tests should accompany each feature; dedicated test steps cover broader flows.

### Foundation (1-6)

1. `chore: bootstrap HookFault development environment`
   Minimal Kotlin/Spring Boot backend, JDBC configuration, Actuator, PostgreSQL,
   Docker Compose, README, and this roadmap. Check compilation, packaging,
   Compose configuration, and runtime health when Docker is available.
2. `build: add Maven wrapper and PostgreSQL startup test`
   Reproducible Maven entry point and a Testcontainers startup/health test with a
   real PostgreSQL database. Run it on Java 21.
3. `feat: manage endpoint schema with Flyway`
   Introduce the first business table and migration integration tests.
4. `feat: register webhook endpoints`
   Add validated endpoint registration, JDBC persistence, and tests. Keep
   signing secrets out of list responses and logs.
5. `feat: constrain webhook destinations for local testing`
   Permit only explicit demo destinations, reject credentials in URLs, disable
   redirects, and bound request/payload sizes. Test bypass attempts.
6. `feat: expose endpoint queries and documented API errors`
   Endpoint lookup/listing, consistent errors, and OpenAPI/Swagger documentation
   with tests for success, validation failures, and missing records.

### Events And Demo Receiver (7-13)

7. `feat: define versioned webhook event envelopes`
   Event ID, run ID, type, timestamp, and fictional payload, with serialization
   tests and stable raw bytes for signing and retries.
8. `feat: sign webhook payloads with HMAC`
   Specify signed timestamp/body bytes and headers. Add known-vector tests,
   including tampering and encoding cases.
9. `feat: add local demo receiver service`
   Separate Kotlin receiver and Compose service. Accept bounded raw requests;
   default to rejecting unverified messages until verification is added.
10. `feat: provision shared demo signing configuration`
    Provide a reproducible local-only signing-key setup used by both services.
    Test configuration errors without exposing secrets.
11. `feat: verify signatures and freshness in safe receiver`
    Constant-time signature comparison, bounded timestamp tolerance, and tests
    for altered bodies, malformed headers, stale timestamps, and wrong keys.
12. `feat: enforce atomic event idempotency in safe receiver`
    Persist processed IDs and fictional notification effects atomically. Test
    sequential and concurrent duplicate delivery against PostgreSQL.
13. `feat: inspect and reset isolated receiver runs`
    Local inspection/reset endpoints scoped by run ID, with integration tests
    proving that one run cannot clear or contaminate another.

### Delivery And Fault Scenarios (14-21)

14. `feat: deliver signed webhooks with bounded HTTP calls`
    HTTP client with connect/read timeouts, bounded response handling, redirect
    rejection, and deterministic HTTP-stub tests.
15. `feat: persist delivery attempts and outcomes`
    Record attempt number, timing, status, and bounded error details without
    secrets. Distinguish transport failures from HTTP responses.
16. `feat: execute and inspect happy-path test runs`
    Persist run lifecycle/events, invoke delivery, and inspect results. Verify
    the full flow with a real database and an HTTP stub.
17. `feat: add duplicate delivery scenario`
    Re-send identical event IDs and payloads. Verify the intended duplicate
    sequence and keep distinct delivery-attempt evidence.
18. `feat: add invalid signature scenario`
    Send a deliberately invalid signature. Separate explicit rejection from a
    timeout or unavailable receiver; neither is proof of signature validation.
19. `feat: inject temporary receiver failures`
    Isolated, bounded HTTP 500 and timeout behavior for selected demo runs, with
    tests that normal receiver behavior is restored afterward.
20. `feat: retry transient failures with bounded backoff`
    Retry policy with maximum attempts, delay, timeouts, and terminal outcomes.
    Do not retry permanent rejection; preserve event identity and body bytes.
21. `feat: add deliberately broken receiver mode`
    Explicit local-only BROKEN instance showing missing deduplication and
    signature checks. Default remains SAFE; test the observable differences.

### Evidence And Portfolio Demo (22-30)

22. `feat: add paginated run and attempt history`
    Queryable run history with stable ordering, bounded pages, and persisted
    attempt detail. Test empty, missing, and multi-page results.
23. `feat: verify observed consumer effects per run`
    Compare expected effects with receiver inspection. Mark unavailable or
    unsupported observation as UNVERIFIED, not a pass or an inferred failure.
24. `feat: generate evidence-based reliability reports`
    Per-scenario PASS/FAIL/UNVERIFIED results linked to delivery and consumer
    evidence. Test that HTTP success alone cannot produce an idempotency pass.
25. `feat: bound scenario execution and preserve report snapshots`
    Run deadlines, concurrency limits, explicit incomplete outcomes, and stable
    stored report evidence even after the demo receiver is reset.
26. `feat: add out-of-order event scenario`
    Versioned fictional state updates, SAFE stale-event protection, and BROKEN
    state regression. Verify final state rather than only HTTP status.
27. `test: cover safe and broken receivers end to end`
    Real PostgreSQL plus both receiver modes; exercise all scenarios, exhausted
    retries, restart persistence, and isolation between concurrent runs.
28. `feat: add reproducible local demo commands`
    PowerShell-friendly demo automation with readiness checks, useful output,
    failure exit codes, and non-destructive run-scoped resets.
29. `ci: verify HookFault on Java 21`
    GitHub Actions builds and runs the complete test suite, including database
    integration tests, without production credentials.
30. `docs: document architecture and verified MVP walkthrough`
    Architecture, example reports, real demo output, limitations, and exact
    commands verified from a clean checkout. Remove outdated instructions.

## Scope Boundaries

No payments, real notifications, arbitrary public target scanning, load testing,
Kafka, Kubernetes, or multi-tenant hosting. A React dashboard is optional future
work, not required to finish this backend MVP.

The MVP is a controlled lab, not a production webhook gateway. Durable queueing
and recovery of in-progress work after a process crash are not promised; report
incomplete work honestly. Do not describe bounded retries as exactly-once
delivery or a durable outbox.

The plan may be adjusted as the implementation teaches us more. Do not create
empty commits or split trivial edits just to reach a number. Future commits and
pushes require the repository owner's permission.
