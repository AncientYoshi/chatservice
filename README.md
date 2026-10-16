# Chatservice

Java 21 / Spring Boot backend for one-to-one real-time chat. Firebase Authentication
identifies users, Firestore stores messages, and STOMP over WebSocket delivers live
events. Angular remains a separate app at `../../chat-frontend` (or your chosen path).

## Run with your Firebase project

Install Google Cloud CLI and sign in once using an account with access to the
Firebase project's Firestore database:

```bash
gcloud auth application-default login
```

If the CLI is still in Downloads, use
`"$HOME/Downloads/google-cloud-sdk/bin/gcloud"` instead of `gcloud`.
From this project's directory:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
unset GOOGLE_APPLICATION_CREDENTIALS
export FIREBASE_PROJECT_ID=messenger-clone-60583
./mvnw spring-boot:run
```

The local backend uses **Application Default Credentials**. These credentials are
separate from each user's Firebase client ID token sent to the chat API. If you
instead use an authorized service-account JSON file, set
`GOOGLE_APPLICATION_CREDENTIALS` to its absolute path outside this repository.
Never put private credentials in `src/main/resources`.

Create a Firestore database and enable your chosen Firebase Authentication sign-in
provider in the Firebase Console. ADC authentication alone does not establish the
required IAM permissions. In IntelliJ, select Java 21, use Maven Wrapper, and add
`FIREBASE_PROJECT_ID` to the application run configuration.

- HTTP: `http://localhost:8090`
- Public liveness: `GET /api/health` (does not check Firebase)
- WebSocket: `ws://localhost:8090/ws` using STOMP 1.2
- Allowed browser origin defaults to `http://localhost:4200`; override with
  comma-separated `CHAT_ALLOWED_ORIGINS`.

The old `src/main/resources/firebase-service-account.json` is frontend web
configuration, not an Admin key. It is unused and excluded from the packaged JAR.

## Local emulators (no cloud credentials or writes)

Requires Java 21 and Firebase CLI. In terminal 1:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
firebase emulators:start --project demo-chatservice --only auth,firestore
```

In terminal 2, from this project:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export FIREBASE_AUTH_EMULATOR_HOST=127.0.0.1:9099
export FIRESTORE_EMULATOR_HOST=127.0.0.1:8085
./mvnw spring-boot:run -Dspring-boot.run.profiles=emulator
```

Emulator mode requires both loopback hosts and the `demo-chatservice` project.
The normal profile rejects emulator environment variables. In Angular, configure
its Firebase project as `demo-chatservice` and connect Firebase Auth to the Auth
emulator as shown in the integration guide. Do not send production tokens to the
emulator backend. Emulator data is temporary unless you explicitly export/import it.

## Tests

Fast tests without cloud credentials:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw test
```

Full HTTP, authentication, Firestore transactions, and real WebSocket integration:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
firebase emulators:exec --project demo-chatservice --only auth,firestore \
  './mvnw -Pemulator-tests verify'
```

The CLI starts/stops emulators and exports their host variables to Maven. Stop
manually running emulators first if these ports are occupied. Integration tests
use only the `demo-chatservice` project.

## API and Angular integration

See [OpenAPI contract](docs/openapi.yaml) and
[Angular integration guide](docs/angular-integration.md) for login, HTTP payloads,
STOMP subscriptions, retries, reconnection, and an opt-in notification chime.

The backend implements profiles, name-prefix lookup, direct conversations, message
history, idempotent sends, unread counts, read receipts, and typing events. The Angular
project now provides login/chat screens and the browser-side opt-in chime.

## Deployment considerations

This version runs **one backend instance** with an in-memory live event broker.
Messages are durable in Firestore, but events are best effort; clients reload
history after reconnect. Multiple backend instances require a shared broker and
consistent user-destination routing before scaling out.

`firestore.indexes.json` supplies the conversation-list composite index.
`firestore.rules` denies direct browser database access: all chat reads/writes go
through Java, which checks conversation membership. Admin SDK operations use IAM
and bypass Firestore rules. Review the rules against any other clients before
explicitly deploying to your project:

```bash
firebase deploy --only firestore:indexes,firestore:rules --project messenger-clone-60583
```

Deployment is not performed by the test suite. Configure HTTPS/WSS and allowed
origins for deployment. The typing route is rate-limited; use a deployment-level
request/connection limiter for public Internet traffic. Closed-browser push,
groups, attachments, calls, and multi-instance delivery are outside this release.
