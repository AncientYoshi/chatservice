# Java Chat Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a tested Java backend for one-to-one real-time chat and a documented Angular integration contract.

**Architecture:** REST handles reads and mutations; authenticated STOMP subscriptions receive private events. Firebase Authentication identifies callers and Firestore transactions persist messages, summaries, and read positions. A single backend instance owns the live broker; reconnecting clients recover durable state through REST.

**Tech Stack:** Java 21, existing Spring Boot 4.1.1, Firebase Admin 9.11.0, Spring WebSocket/STOMP, Firebase Auth and Firestore emulators, JUnit.

**Spec:** `docs/chat-backend-design.md`

## Implementation status

Implemented and verified on 2026-10-16. See `docs/verification.md` for evidence
and remaining integration work. The checklist below preserves the original plan;
implementation consolidates Firestore and real WebSocket cases in `ChatBackendIT`.

## Global Constraints

- Backend implementation lives in `com.messenger.chatservice`; Angular code is integration documentation only.
- Use external ADC credentials and a configurable Firebase project ID; never package Admin keys.
- Allow configured origins, initially `http://localhost:4200`.
- Text length is 1–4000 characters after trimming; page size is 1–100 with default 30.
- Only authenticated conversation members may read messages, send, mark read, or publish typing events.
- Only `/user/queue/events` subscriptions are allowed; client STOMP SEND frames are denied.
- Events occur after commit and are best effort; retries cannot duplicate persisted messages.
- Close WebSockets at token expiry; typing expires after five seconds.
- This directory has no Git repository. Preserve changes in place; do not initialize Git or claim commits.

## Review Focus

1. A retried message with altered text must return 409 without changing the original message.
2. Concurrent read receipts and sends must not erase a newly arrived unread message.
3. A pagination cursor from another conversation must not disclose data or skip arbitrary history.
4. A connected but expired user must stop receiving events even without sending another frame.
5. A broker failure after commit must not make a successful send appear unsuccessful or lose history.

## File map

All Java paths below are relative to `src/main/java/com/messenger/chatservice`;
test counterparts use `src/test/java/com/messenger/chatservice`.

- `auth/`: `ChatPrincipal`, `TokenVerifier`, `FirebaseTokenVerifier`, `BearerTokenFilter`.
- `config/`: modify `FirebaseConfig` and `SecurityConfig`; add `WebSocketConfig`.
- `chat/`: `ChatModels`, `ChatException`, `ChatValidation`, `ChatService`, `ChatController`, `ChatExceptionHandler`.
- `store/`: `ChatStore`, `FirestoreChatStore`, `FirestoreMapper`, `PageCursor`.
- `realtime/`: `ChatEventPublisher`, `StompEventPublisher`, `StompAuthInterceptor`, `SocketSessionRegistry`, `TypingService`.
- `health/HealthController`: public process liveness endpoint.
- `docs/openapi.yaml`, `docs/angular-integration.md`, `firebase.json`, `firestore.indexes.json`, `firestore.rules`: contracts, local emulator configuration, and deny-direct-client rules.

## Task 1: Authentication and deterministic test infrastructure

**Modify:** `pom.xml`, both existing config classes, `application.properties`, existing context test.
**Create:** auth classes, health controller, emulator configuration, `auth/AuthenticationHttpTest`.

**Interfaces:**
- `ChatPrincipal(String uid, Instant expiresAt)` implements `Principal` with `getName()` returning UID.
- `TokenVerifier.verify(String token)` returns `ChatPrincipal` or throws an authentication exception.
- `FirebaseConfig` supplies lifecycle-managed `FirebaseApp`, `FirebaseAuth`, and `Firestore` beans.

- [ ] Add HTTP tests: `/api/health` returns 200 without a token; protected routes return JSON 401 for absent/invalid/expired tokens; valid tokens identify the verified UID. Inject a deterministic verifier only in tests; production always verifies Firebase ID tokens.
- [ ] Run `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=AuthenticationHttpTest test`; confirm failures reflect the missing auth behavior.
- [ ] Add Spring WebSocket and messaging security dependencies managed by the existing parent. Implement stateless bearer authentication, consistent error responses, configured CORS, and CSRF handling appropriate to bearer-only REST. Avoid duplicate servlet registration of the security filter.
- [ ] Configure project ID through `FIREBASE_PROJECT_ID`, defaulting to this project's known ID. Permit emulator-only credentials only under an explicit emulator profile with localhost emulator hosts; normal startup requires ADC.
- [ ] Configure Auth emulator port 9099 and Firestore emulator port 8085 with project `demo-chatservice`. Make default unit/context tests independent of live credentials; reserve real SDK/emulator tests for the Maven `emulator-tests` profile.
- [ ] Re-run authentication tests and the default test suite. Check normal and emulator profiles cannot silently mix real and emulator projects.

## Task 2: Profiles, direct conversations, and paginated reads

**Create:** store classes, chat models/validation/errors, service/controller/advice, `store/FirestoreQueriesIT`.

**Interfaces:**
- `ChatStore.upsertProfile(uid, displayName)` and `searchUsers(name, limit, cursor)` return public profiles containing only UID and display name.
- `ChatStore.getOrCreateConversation(uid, recipientUid)` and `listConversations(uid, limit, cursor)` return member IDs, latest message, read positions, and caller unread count.
- `ChatStore.listMessages(uid, conversationId, limit, before)` returns `Page<Message>` with `items` and nullable `nextCursor`.
- `Message` includes `id`, `conversationId`, `senderUid`, `text`, `sequence`, and ISO-8601 `createdAt`.

- [ ] Write emulator tests for symmetric direct-conversation creation, missing recipient profiles, self-chat rejection, and member-only reads. For equal display names/timestamps, assert cursor pagination has no duplicate results.
- [ ] Run the emulator test command and observe expected failures before implementing the store.
- [ ] Implement profile names with trimming, length 1–80, and a normalized search field. Restrict search prefix length to 1–80. Validate Firebase UIDs without allowing slashes or control characters; hash UIDs for Firestore map keys and compound document IDs.
- [ ] Create direct conversations transactionally using a SHA-256 hash of length-prefixed sorted UIDs; require both profiles to exist. Query caller membership with `array-contains`, order by activity time and document ID, and provide composite indexes.
- [ ] Implement versioned opaque cursors with bounded input length, query scope, and stable ordering fields. Reject malformed cursors and wrong query/conversation scope with 400; enforce membership independently of cursors.
- [ ] Implement REST routes through `ChatService`; return 400 validation, 403 membership, 404 missing resource, and sanitized 503 datastore failures. Apply bounded backend operation timeouts and preserve thread interruption.
- [ ] Re-run query integration tests, including forged cross-conversation cursors and unauthorized access to every route.

## Task 3: Transactional sends, retries, and read receipts

**Modify:** store/service/controller/models.
**Create:** `chat/MessageRulesTest`, `store/MessageTransactionsIT`.

**Interfaces:**
- `ChatStore.send(uid, conversationId, text, clientMessageId)` returns `SendResult(Message message, boolean created)`.
- `ChatStore.markRead(uid, conversationId, messageId)` returns updated member read position and unread count.
- `ChatEventPublisher.publish(List<String> recipients, ChatEvent event)` is the event boundary; persistence does not depend on broker success.

- [ ] Write tests for blank/4001-character text, duplicate sends, conflicting idempotency reuse, concurrent sends, backward read receipts, and own messages excluded from unread counts.
- [ ] Run the tests to expose missing behavior.
- [ ] Implement send transactions that read membership and deduplication records before any writes, allocate a unique increasing sequence, persist one message, and update the latest summary atomically. Scope idempotency to conversation and sender; require a UUID clientMessageId.
- [ ] Store per-member incoming counters plus each message's corresponding counter snapshot. Derive unread counts from the latest incoming counter minus the acknowledged snapshot; transactionally advance read positions so simultaneous sends are preserved.
- [ ] Reject read targets outside the conversation and ignore backward/equal read positions. Return the original message for same-payload retries and 409 for changed-payload retries.
- [ ] Emit newly committed message and advancing read events to both participants; catch/log event delivery failures without message text or tokens. No event is emitted from inside a retried transaction callback.
- [ ] Re-run tests with concurrent send/read interleavings and a publisher that throws; assert persisted messages remain retrievable and sends remain successful.

## Task 4: Private WebSocket delivery and typing

**Create:** realtime classes, WebSocket configuration, `realtime/StompSecurityTest`, `realtime/RealtimeDeliveryIT`.

**Interfaces:**
- STOMP endpoint `/ws`, broker destination `/queue`, user destination prefix `/user`.
- CONNECT accepts `Authorization: Bearer <Firebase ID token>`; subscribe only to `/user/queue/events`.
- `ChatEvent(String type, String conversationId, Object data)` carries `message.created`, `conversation.read`, or `conversation.typing`.
- `TypingService.publish(uid, conversationId, typing)` validates membership before emitting to the other member.

- [ ] Write tests denying unauthenticated CONNECT/SUBSCRIBE, foreign subscriptions, client SEND, and repeated CONNECT; assert user A cannot receive a B/C conversation event.
- [ ] Run tests to confirm the security boundary is absent before implementation.
- [ ] Implement CONNECT verification before messaging authorization. Whitelist the user's event subscription and protocol lifecycle frames, rejecting all other destinations/commands. Configure heartbeats, bounded message buffers, and configured allowed origins.
- [ ] Track transport sessions and schedule closure at token expiry, cleaning scheduled jobs on disconnect. Deny expired frames as well; test closure without subsequent client traffic.
- [ ] Publish events with `convertAndSendToUser` to each participant. Support multiple tabs and remove sessions safely on disconnect.
- [ ] Rate-limit typing to five updates per user per second with bounded, expiring rate state. Events include `uid`, `typing`, and `expiresAt`; active typing expires after five seconds without a refresh.
- [ ] Run a real-server integration test with three authenticated STOMP clients: A sends to B, A/B receive, C does not; reconnect B and recover missed messages through REST.

## Task 5: Contract, integration guide, and final verification

**Create/modify:** OpenAPI contract, Angular integration guide, README, Firestore indexes/rules, verification report.

- [ ] Document every route's schema, auth, limits, pagination, status codes, and examples in OpenAPI 3.0.3; parse the file and compare paths against controller mappings.
- [ ] Document Angular Firebase sign-in, bearer HTTP calls, `@stomp/stompjs` connection headers, reconnect/token refresh, and private subscription handling. Include idempotent sends and message-ID deduplication examples.
- [ ] Provide an opt-in Web Audio chime example for incoming live messages only, mute handling, and user-gesture audio initialization. Explain that the Java backend alone does not add frontend screens, audio, or closed-browser push notifications.
- [ ] Update README for ADC and emulator startup. Include explicit project-ID and host variables, emulator test commands, index deployment instructions, and the single-instance live-delivery limit. Do not deploy rules/indexes automatically to the live project.
- [ ] Run all default tests, then `firebase emulators:exec --project demo-chatservice --only auth,firestore 'JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Pemulator-tests verify'`. Use the actual resulting build output to record test counts and failures.
- [ ] Review authentication, membership, transaction ordering, cursor boundaries, and resource cleanup against the spec. Fix issues and re-run affected tests, then the full suite.
- [ ] Report delivered Java functionality, exact verification performed, frontend integration remaining, and any live Firebase checks that were not performed. Do not equate emulator success with production connectivity.

## Execution

Recommended method: implement inline in this chat. The tasks share authentication,
store, and event interfaces, so sequential implementation avoids parallel edits
to those boundaries. Plan review and inline execution were approved in chat. Implementation is now complete; see the verification report.
