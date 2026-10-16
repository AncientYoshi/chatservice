# Backend verification — 2026-10-16

Implemented the approved Java backend. Final clean verification command:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
firebase emulators:exec --project demo-chatservice --only auth,firestore \
  './mvnw -B -ntp -Pemulator-tests clean verify'
```

Result: **BUILD SUCCESS**, 14 default tests plus 13 integration tests, with zero
failures, errors, or skips. Integration tests start the real Spring HTTP/WebSocket
server and use the actual Firebase Admin/Firestore SDK against local emulators.

Coverage includes:

- Missing, invalid, and expired bearer tokens; public health; disallowed CORS origin.
- Sanitized unexpected API errors and input validation.
- Participant-only reads, sends, read receipts, and typing.
- Idempotent sends, conflicting reuse, concurrent retries, concurrent sequence allocation.
- Incoming-only unread counts, monotonic read receipts, concurrent reads and sends.
- History cursor scoping; stable profile/conversation pagination; emoji name prefixes.
- Actual private STOMP message/read/typing events, third-user exclusion, reconnect recovery.
- Forbidden subscriptions, client publishing, repeated CONNECT, and idle token-expiry disconnect.
- Broker failure after persistence does not turn a committed send into an API failure.

The OpenAPI artifact parses, schema references resolve, and its ten HTTP
operations match controller routes. A clean JAR inspection confirms that
`firebase-service-account.json` is not packaged. Sources and tests were formatted
with google-java-format 1.24.0.

## Review and implementation notes

A separate reviewer was requested but could not run because its account usage
limit was reached. A local code review was performed; it found and fixed reserved
Firestore document IDs, a Unicode prefix-search boundary, and missing generic
error sanitization. Regression tests reproduced those defects before the fixes.
There is no independent review result.

Tests from the planned separate Firestore/WebSocket integration classes are
consolidated in `ChatBackendIT`, sharing the same real-server fixture. FirebaseAuth
is obtained from the lifecycle-managed FirebaseApp inside the token verifier,
rather than eagerly constructing a separate bean. The implementation is in place
because this folder is not a Git repository; no commit or branch was created.

## Limits of this verification

No data was written to the live Firebase project. Production IAM permissions,
database availability, composite-index deployment, and real Google sign-in were
not verified by emulator tests. Firestore emulators do not enforce production
composite-index requirements; deploy the supplied index explicitly when needed.

The Angular frontend is now implemented in `/Users/paingswanpyae/Documents/chat-frontend`.
Browser verification against local emulators confirmed email signup, name search,
conversation creation, message sending, a real STOMP reply from a second test user,
read receipts, sound opt-in, and history recovery after reload. Desktop and mobile
layouts were inspected, including a 390px viewport without horizontal overflow.
The second user was an authenticated API/STOMP test peer, not a second browser session.
Google sign-in and actual audio output were not independently verified.

All 11 frontend unit tests pass. Frontend unit tests cover message deduplication/order, stale summary handling,
notification filtering, retry idempotency, logout isolation, stale searches, and login UI.
The production build succeeds with three component-style size warnings (under error limits).
Firebase dependencies were deduplicated to resolve a reproduced browser startup failure.
The npm audit retains five high dependency entries rooted in the unused Node Firestore/gRPC
package; the proposed forced downgrade was not applied. See the frontend README.

The live broker supports one backend instance. Events are best effort; clients
must reconcile via REST. Groups, files, calls, and closed-browser push are not
included. No hosting, Firestore rules, or indexes were deployed.
