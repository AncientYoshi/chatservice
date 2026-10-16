# Java chat backend design

## Goal and scope

Build a Java 21 / Spring Boot backend for the Angular project at
`/Users/paingswanpyae/Documents/chat-frontend`. The first release supports
authenticated one-to-one text chats, saved history, unread counts, read receipts,
and typing events. Angular plays an incoming-message sound with a mute setting.
The current frontend is an Angular starter with Firebase initialization; frontend
login, chat screens, transport integration, and audio playback still need work.
This implementation scope is the Java backend and its integration documentation.

Group chat, attachments, calls, and push notifications while the browser is closed
are future work. This first release runs one backend instance; its WebSocket
broker is in memory. Firestore persists all messages across restarts.

## Architecture

Use REST for mutations and paginated reads, STOMP over WebSocket for live events,
Firebase Authentication for user identity, and Firestore for persistence.
This keeps message retries and validation in HTTP while allowing private events
to update every connected tab for each participant. A direct Firestore client
would need less Java but would move authorization into Firestore rules; this
design keeps chat operations in the requested Java backend. SSE is another option,
but STOMP provides a standard client subscription and heartbeat protocol.

Angular sends a Firebase ID token as `Authorization: Bearer <token>` for REST
and in the STOMP CONNECT frame. Java verifies the token and derives the caller's
UID; request bodies cannot choose the sender. Invalid tokens return 401, and
non-members cannot read or mutate a conversation. Each socket is closed when its
token expires, so Angular reconnects with a refreshed token.

Only `/user/queue/events` subscriptions are allowed. Clients cannot subscribe to
another user's destination or publish directly to broker destinations. All writes
use REST. CORS and WebSocket origins allow configured Angular origins, initially
`http://localhost:4200`. Tokens are never put in URLs or logs.

## API contract

- `GET /api/health`: public liveness response, without claiming Firebase readiness.
- `PUT /api/me`: create/update the caller's public profile (display name).
- `GET /api/users?name=...&limit=...&cursor=...`: bounded display-name prefix lookup;
  return UID and public display name, not email addresses.
- `POST /api/conversations`: get or create a direct conversation with `recipientUid`.
- `GET /api/conversations?limit=...&cursor=...`: caller's conversation summaries.
- `GET /api/conversations/{id}/messages?limit=...&before=...`: paginated history.
- `POST /api/conversations/{id}/messages`: send `text` and `clientMessageId`.
- `PUT /api/conversations/{id}/read`: mark through a specified `messageId` as read.
- `POST /api/conversations/{id}/typing`: publish a transient `typing` boolean.

Responses use consistent error codes and messages without internal exception
details. Validate text (1–4000 characters after trimming), IDs, pagination, and
membership. Cap page size at 100. Document exact request/response schemas in
OpenAPI and WebSocket examples with the implementation.

## Data and consistency

Public profiles live in `users/{uid}`. Direct conversation IDs are derived from
the sorted pair of UIDs using an unambiguous encoding and hash, avoiding duplicate
conversations. `conversations/{id}` holds member UIDs, latest message summary,
monotonic message sequence, and per-member read sequence. Messages are stored in
its `messages` subcollection with sender UID, text, server time, and sequence.

Message creation and summary updates use a Firestore transaction. Deduplicate
retries using sender UID plus clientMessageId; conflicting reuse returns 409.
Only emit events after a successful commit. Read positions only move forward and
must reference a message in the conversation. Unread counts reflect incoming
messages after the caller's read position, not their own outgoing messages.
Provide the required Firestore indexes with the implementation.

## Events and reconnects

Private events: `message.created`, `conversation.read`, and `conversation.typing`.
Include conversation ID and the relevant message/user data; message events carry
a stable message ID for deduplication. Deliver message events to both participants
for multi-tab sync. Typing expires after five seconds and is rate-limited per user.

Live delivery is best effort, not durable. After reconnecting, Angular fetches
conversation summaries and history to recover missed events. A persisted message
remains successful if event delivery fails. The server logs that failure without
logging message contents or credentials.

Angular plays a sound only for a newly received message from another user, after
audio has been enabled by a user gesture, and respects mute settings. Historical
messages, retries, and the sender's own events must not trigger the sound.
Use an app-provided iMessage-style chime asset; Java does not play browser audio.

## Configuration and verification

Use existing ADC credentials with an explicit configurable Firebase project ID.
Support Firebase Auth and Firestore emulators for repeatable development tests.
Keep credentials external. Local real Firebase sign-in remains a user setup step.

Verify unauthorized requests, membership isolation, forbidden subscriptions,
token expiry, duplicate sends, concurrent sends, pagination, read-position
monotonicity, and private live delivery between two users. Run integration tests
against emulators and separately report any unverified live Firebase connection.
Deliver startup instructions and Angular HTTP/STOMP integration examples.
