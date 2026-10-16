# Connect the Angular app to Chatservice

The Angular implementation is now in `/Users/paingswanpyae/Documents/chat-frontend`.
Its README contains complete emulator and live-project startup commands. The snippets
below explain the API contract; the frontend services are the maintained implementation.

## 1. Firebase client sign-in

Export the app and Auth instance from your Angular `src/app/firebase.ts`, keeping
your existing web configuration. Call `connectAuthEmulator` only for local emulator
builds, before any Auth operation:

```ts
import { initializeApp } from 'firebase/app';
import { getAuth, connectAuthEmulator } from 'firebase/auth';

export const app = initializeApp(firebaseConfig);
export const auth = getAuth(app);
// Emulator builds ONLY: firebaseConfig.projectId must be 'demo-chatservice'.
// connectAuthEmulator(auth, 'http://127.0.0.1:9099');
```

For example, enable Google sign-in in Firebase Console and call this from a login
button. Add your development/production domains to Firebase Auth's authorized
domains. Email/password sign-in is also supported by the backend because it uses
Firebase ID tokens, independent of the client provider.

```ts
import { GoogleAuthProvider, signInWithPopup } from 'firebase/auth';
import { auth } from './firebase';

const credential = await signInWithPopup(auth, new GoogleAuthProvider());
await api('/api/me', 'PUT', {
  displayName: credential.user.displayName ?? 'New user'
});
```

## 2. Authenticated HTTP calls

Use the Firebase **ID token**, not the Google OAuth access token. This fetch helper
works in Angular's browser code; use environment configuration for `API_BASE` in
production. Do not save tokens in logs or URLs.

```ts
import { auth } from './firebase';
const API_BASE = 'http://localhost:8090';

export async function api<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
  const user = auth.currentUser;
  if (!user) throw new Error('Sign in first');
  for (let attempt = 0; attempt < 2; attempt++) {
    const token = await user.getIdToken(attempt === 1);
    const response = await fetch(`${API_BASE}${path}`, {
      method,
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body)
    });
    if (response.status === 401 && attempt === 0) continue;
    const result = await response.json();
    if (!response.ok) throw Object.assign(new Error(result.message), {
      status: response.status, code: result.code
    });
    return result as T;
  }
  throw new Error('Your session expired; sign in again');
}
```

Typical flow:

1. After sign-in, `PUT /api/me` with a display name.
2. Find another registered user: `GET /api/users?name=Bob&limit=30`.
3. `POST /api/conversations` with `{ "recipientUid": "their-firebase-uid" }`.
4. List chats using `GET /api/conversations`, and fetch history using
   `GET /api/conversations/{id}/messages`.
5. Send with `POST /api/conversations/{id}/messages` and a body like:
   `{ "text": "Hello!", "clientMessageId": "a-valid-uuid" }`.
6. After messages are actually visible, `PUT /api/conversations/{id}/read` with
   `{ "messageId": "last-visible-message-id" }`.
7. While typing, `POST /api/conversations/{id}/typing` with `{ "typing": true }`
   at most once per second; send false when finished. Clear indicators at
   `expiresAt`, even if no false event arrives.

Generate `clientMessageId` once with `crypto.randomUUID()` and keep it with the
pending message until success. Reuse it after a timeout or 503. Creating a new key
on retry can duplicate a message. A 409 means the key was reused with different text.

List responses are `{ items, nextCursor }`. Message history is newest first;
reverse it for chronological rendering. For older messages, pass `nextCursor` as
`before`. Other lists use `cursor`. Keep cursors unchanged and URL-encode them.
Conversation pages are a live view, so reload the first page after activity or
reconnect and merge by ID. Sort messages by `sequence`, since concurrent live
events can arrive in a different order from their committed sequence.

## 3. Private live events

Add `@stomp/stompjs` to the Angular project (`npm install @stomp/stompjs`). The
server uses native WebSocket, not SockJS. All message mutations use REST.

```ts
import { Client } from '@stomp/stompjs';
import { auth } from './firebase';

type ChatEvent = {
  type: 'message.created' | 'conversation.read' | 'conversation.typing';
  conversationId: string;
  data: any; // Replace with the response types from docs/openapi.yaml.
};

export function connectChat(
  onEvent: (event: ChatEvent) => void,
  reconcile: () => Promise<void>,
  onError: (error: unknown) => void
): Client {
  const client = new Client({
    brokerURL: 'ws://localhost:8090/ws', // wss:// for HTTPS deployments
    reconnectDelay: 3000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    beforeConnect: async () => {
      const user = auth.currentUser;
      if (!user) { await client.deactivate(); return; }
      client.connectHeaders = { Authorization: `Bearer ${await user.getIdToken()}` };
    },
    onConnect: () => {
      client.subscribe('/user/queue/events', frame => {
        try { onEvent(JSON.parse(frame.body)); } catch (error) { onError(error); }
      });
      // Always reload summaries/history: notifications are not a durable queue.
      void reconcile().catch(onError);
    },
    onStompError: () => onError(new Error('Chat connection rejected')),
    onWebSocketError: () => onError(new Error('Chat connection unavailable'))
  });
  client.activate();
  return client;
}
```

Keep a single client per tab and deactivate it on logout/component teardown.
The server closes connections at token expiry; reconnection fetches a current
token. Persistent authentication errors should show a sign-in prompt instead of
leaving a reconnect loop running. Add a window-focus refresh to recover from an
event delivery failure even if the socket never disconnects.

Event data:

| Type | `data` | Client action |
| --- | --- | --- |
| `message.created` | Full Message from OpenAPI | Merge by ID, sort by sequence, update summary/unread state |
| `conversation.read` | `{ uid, sequence }` | Advance the displayed receipt; never move backward |
| `conversation.typing` | `{ uid, typing, expiresAt }` | Show/hide and expire the typing indicator |

Both participants receive message/read events, including the sender's other tabs.
Typing events go only to the other participant. Never subscribe to another UID's
queue or publish STOMP SEND messages; those commands are rejected.

## 4. Opt-in notification chime

Browsers generally require user interaction before audio playback. Call
`enableSound()` from an explicit button click. This small original two-note chime
provides an iMessage-style feel; it is not Apple's audio recording. Store the mute
preference in your Angular settings. The example runs only in browser components,
not during server rendering.

```ts
let audio: AudioContext | undefined;
let muted = localStorage.getItem('chat-muted') !== 'false';
let lastSound = -Infinity;
const notified = new Set<string>();

export async function enableSound() {
  audio ??= new AudioContext();
  await audio.resume();
  muted = false;
  localStorage.setItem('chat-muted', 'false');
}

export function muteSound() {
  muted = true;
  localStorage.setItem('chat-muted', 'true');
}

export function notifyIncoming(message: { id: string; senderUid: string }) {
  if (notified.has(message.id)) return;
  notified.add(message.id);
  if (notified.size > 2000) notified.delete(notified.values().next().value!);
  if (message.senderUid === auth.currentUser?.uid || muted || audio?.state !== 'running') return;
  const now = audio.currentTime;
  if (now - lastSound < 0.4) return;
  lastSound = now;
  for (const [frequency, delay] of [[880, 0], [1174.66, 0.09]]) {
    const tone = audio.createOscillator();
    const gain = audio.createGain();
    tone.type = 'sine';
    tone.frequency.value = frequency;
    tone.connect(gain); gain.connect(audio.destination);
    const start = now + delay;
    gain.gain.setValueAtTime(0, start);
    gain.gain.linearRampToValueAtTime(0.08, start + 0.01);
    gain.gain.exponentialRampToValueAtTime(0.001, start + 0.2);
    tone.start(start); tone.stop(start + 0.21);
    tone.onended = () => { tone.disconnect(); gain.disconnect(); };
  }
}
```

Call `notifyIncoming(event.data)` only from a `message.created` live event. Do not
call it when rendering REST history or optimistic outgoing messages. For multiple
open tabs, coordinate sound ownership with `BroadcastChannel` or permit sound in
only one tab, so they do not all chime. The backend supplies events while connected;
closed-browser notification delivery requires a separate push-notification feature.
