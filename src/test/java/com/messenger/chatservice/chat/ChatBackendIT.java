package com.messenger.chatservice.chat;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("emulator")
class ChatBackendIT {
  @LocalServerPort int port;
  static final HttpClient HTTP = HttpClient.newHttpClient();

  record User(String uid, String token) {}

  record Reply(int status, JsonObject body) {}

  User alice, bob, eve;

  @BeforeEach
  void users() throws Exception {
    alice = user("Alice");
    bob = user("Bob");
    eve = user("Eve");
  }

  User user(String name) throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://"
                        + System.getenv("FIREBASE_AUTH_EMULATOR_HOST")
                        + "/identitytoolkit.googleapis.com/v1/accounts:signUp?key=fake"))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"email\":\""
                        + UUID.randomUUID()
                        + "@example.com\",\"password\":\"test-password-123\",\"returnSecureToken\":true}"))
            .build();
    var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), response.body());
    var body = JsonParser.parseString(response.body()).getAsJsonObject();
    var u = new User(body.get("localId").getAsString(), body.get("idToken").getAsString());
    assertEquals(200, call(u, "PUT", "/api/me", Map.of("displayName", name)).status);
    return u;
  }

  Reply call(User user, String method, String path, Object data) throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(java.time.Duration.ofSeconds(20))
            .header("Content-Type", "application/json");
    if (user != null) builder.header("Authorization", "Bearer " + user.token);
    var r =
        HTTP.send(
            builder
                .method(
                    method,
                    data == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(new Gson().toJson(data)))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    return new Reply(
        r.statusCode(),
        r.body().isBlank() ? new JsonObject() : JsonParser.parseString(r.body()).getAsJsonObject());
  }

  String conversation() throws Exception {
    var r = call(alice, "POST", "/api/conversations", Map.of("recipientUid", bob.uid));
    assertEquals(200, r.status, r.body.toString());
    return r.body.get("id").getAsString();
  }

  Reply send(User u, String id, String text, String key) throws Exception {
    return call(
        u,
        "POST",
        "/api/conversations/" + id + "/messages",
        Map.of("text", text, "clientMessageId", key));
  }

  JsonObject summary(User u, String id) throws Exception {
    var r = call(u, "GET", "/api/conversations", null);
    assertEquals(200, r.status, r.body.toString());
    for (var x : r.body.getAsJsonArray("items"))
      if (x.getAsJsonObject().get("id").getAsString().equals(id)) return x.getAsJsonObject();
    throw new AssertionError("Conversation missing");
  }

  @Test
  void memberIsolationAndIdempotency() throws Exception {
    var id = conversation();
    assertEquals(
        id,
        call(bob, "POST", "/api/conversations", Map.of("recipientUid", alice.uid))
            .body
            .get("id")
            .getAsString());
    var key = UUID.randomUUID().toString();
    var first = send(alice, id, " hello ", key);
    assertEquals(200, first.status, first.body.toString());
    assertEquals(first.body, send(alice, id, "hello", key).body);
    assertEquals(409, send(alice, id, "changed", key).status);
    assertEquals(403, call(eve, "GET", "/api/conversations/" + id + "/messages", null).status);
    assertEquals(403, send(eve, id, "intrusion", UUID.randomUUID().toString()).status);
    assertEquals(
        403,
        call(
                eve,
                "PUT",
                "/api/conversations/" + id + "/read",
                Map.of("messageId", first.body.get("id").getAsString()))
            .status);
    assertEquals(
        403,
        call(eve, "POST", "/api/conversations/" + id + "/typing", Map.of("typing", true)).status);
    assertEquals(401, call(null, "GET", "/api/conversations", null).status);
    assertEquals(
        1,
        call(bob, "GET", "/api/conversations/" + id + "/messages", null)
            .body
            .getAsJsonArray("items")
            .size());
  }

  @Test
  void unreadCountsAndReadPositionsAreMonotonic() throws Exception {
    var id = conversation();
    var a = send(alice, id, "one", UUID.randomUUID().toString()).body;
    send(bob, id, "reply", UUID.randomUUID().toString());
    var b = send(alice, id, "two", UUID.randomUUID().toString()).body;
    assertEquals(2, summary(bob, id).get("unreadCount").getAsInt());
    assertEquals(1, summary(alice, id).get("unreadCount").getAsInt());
    var path = "/api/conversations/" + id + "/read";
    assertEquals(
        200, call(bob, "PUT", path, Map.of("messageId", b.get("id").getAsString())).status);
    call(bob, "PUT", path, Map.of("messageId", a.get("id").getAsString()));
    assertEquals(0, summary(bob, id).get("unreadCount").getAsInt());
    send(alice, id, "three", UUID.randomUUID().toString());
    assertEquals(1, summary(bob, id).get("unreadCount").getAsInt());
  }

  @Test
  void validationAndCursorScope() throws Exception {
    var id = conversation();
    assertEquals(400, send(alice, id, " ", UUID.randomUUID().toString()).status);
    assertEquals(400, send(alice, id, "x".repeat(4001), UUID.randomUUID().toString()).status);
    assertEquals(400, send(alice, id, "valid", "bad-key").status);
    assertEquals(
        400, call(alice, "POST", "/api/conversations", Map.of("recipientUid", alice.uid)).status);
    assertEquals(400, call(alice, "GET", "/api/conversations?limit=101", null).status);
    for (int n = 0; n < 3; n++) send(alice, id, "m" + n, UUID.randomUUID().toString());
    var page = call(bob, "GET", "/api/conversations/" + id + "/messages?limit=2", null).body;
    assertEquals(2, page.getAsJsonArray("items").size());
    var cursor = page.get("nextCursor").getAsString();
    assertEquals(
        1,
        call(bob, "GET", "/api/conversations/" + id + "/messages?before=" + cursor, null)
            .body
            .getAsJsonArray("items")
            .size());
    var other =
        call(alice, "POST", "/api/conversations", Map.of("recipientUid", eve.uid))
            .body
            .get("id")
            .getAsString();
    assertEquals(
        400,
        call(alice, "GET", "/api/conversations/" + other + "/messages?before=" + cursor, null)
            .status);
    assertEquals(
        400,
        call(alice, "GET", "/api/conversations/" + id + "/messages?before=broken", null).status);
    assertEquals(
        404,
        call(
                alice,
                "PUT",
                "/api/conversations/" + other + "/read",
                Map.of(
                    "messageId",
                    page.getAsJsonArray("items").get(0).getAsJsonObject().get("id").getAsString()))
            .status);
  }

  @Test
  void concurrentSendsAllocateUniqueSequences() throws Exception {
    var id = conversation();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Reply>> futures = new ArrayList<>();
      for (int n = 0; n < 8; n++)
        futures.add(
            executor.submit(() -> send(alice, id, "parallel", UUID.randomUUID().toString())));
      Set<Long> sequences = new HashSet<>();
      for (var f : futures) {
        var r = f.get(30, TimeUnit.SECONDS);
        assertEquals(200, r.status, r.body.toString());
        sequences.add(r.body.get("sequence").getAsLong());
      }
      assertEquals(8, sequences.size());
      assertEquals(8, summary(bob, id).get("unreadCount").getAsInt());
    }
  }

  @org.springframework.beans.factory.annotation.Autowired
  org.springframework.messaging.simp.user.SimpUserRegistry userRegistry;

  final class Socket implements WebSocket.Listener, AutoCloseable {
    final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
    final StringBuilder buffer = new StringBuilder();
    final CompletableFuture<Integer> closed = new CompletableFuture<>();
    WebSocket socket;

    Socket(User user) throws Exception {
      socket =
          HTTP.newWebSocketBuilder()
              .header("Origin", "http://localhost:4200")
              .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws"), this)
              .get(5, TimeUnit.SECONDS);
      socket
          .sendText(
              "CONNECT\naccept-version:1.2\nhost:localhost\nAuthorization:Bearer "
                  + user.token
                  + "\nheart-beat:0,0\n\n\0",
              true)
          .join();
      assertTrue(frame().startsWith("CONNECTED"));
    }

    @Override
    public void onOpen(WebSocket ws) {
      ws.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence text, boolean last) {
      buffer.append(text);
      int end;
      while ((end = buffer.indexOf("\0")) >= 0) {
        frames.add(buffer.substring(0, end).stripLeading());
        buffer.delete(0, end + 1);
      }
      ws.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
      closed.complete(code);
      return null;
    }

    @Override
    public void onError(WebSocket ws, Throwable error) {
      closed.completeExceptionally(error);
    }

    String frame() throws Exception {
      String f = frames.poll(5, TimeUnit.SECONDS);
      assertNotNull(f, "Expected STOMP frame");
      return f;
    }

    void subscribe(User u) throws Exception {
      socket.sendText("SUBSCRIBE\nid:events\ndestination:/user/queue/events\n\n\0", true).join();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (System.nanoTime() < deadline) {
        var user = userRegistry.getUser(u.uid);
        if (user != null
            && user.getSessions().stream().anyMatch(s -> !s.getSubscriptions().isEmpty())) return;
        Thread.sleep(10);
      }
      fail("Subscription not registered");
    }

    JsonObject event() throws Exception {
      var f = frame();
      assertTrue(f.startsWith("MESSAGE"), f);
      return JsonParser.parseString(f.substring(f.indexOf("\n\n") + 2)).getAsJsonObject();
    }

    public void close() {
      if (socket != null) socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
    }
  }

  @Test
  void privateEventsTypingReadAndReconnect() throws Exception {
    var id = conversation();
    try (var a = new Socket(alice);
        var b = new Socket(bob);
        var e = new Socket(eve)) {
      a.subscribe(alice);
      b.subscribe(bob);
      e.subscribe(eve);
      var msg = send(alice, id, "live", UUID.randomUUID().toString());
      assertEquals(200, msg.status);
      assertEquals("message.created", a.event().get("type").getAsString());
      assertEquals(
          msg.body.get("id").getAsString(),
          b.event().getAsJsonObject("data").get("id").getAsString());
      assertNull(e.frames.poll(300, TimeUnit.MILLISECONDS));
      assertEquals(
          200,
          call(
                  bob,
                  "PUT",
                  "/api/conversations/" + id + "/read",
                  Map.of("messageId", msg.body.get("id").getAsString()))
              .status);
      assertEquals("conversation.read", a.event().get("type").getAsString());
      b.event();
      assertEquals(
          200,
          call(alice, "POST", "/api/conversations/" + id + "/typing", Map.of("typing", true))
              .status);
      var typing = b.event();
      assertEquals("conversation.typing", typing.get("type").getAsString());
      assertTrue(
          java.time.Instant.parse(typing.getAsJsonObject("data").get("expiresAt").getAsString())
              .isAfter(java.time.Instant.now()));
      assertNull(a.frames.poll(100, TimeUnit.MILLISECONDS));
    }
    send(alice, id, "while offline", UUID.randomUUID().toString());
    try (var reconnect = new Socket(bob)) {
      reconnect.subscribe(bob);
      assertEquals(
          2,
          call(bob, "GET", "/api/conversations/" + id + "/messages", null)
              .body
              .getAsJsonArray("items")
              .size());
    }
  }

  @Test
  void foreignSubscriptionAndClientSendAreRejected() throws Exception {
    try (var e = new Socket(eve)) {
      e.socket
          .sendText("SUBSCRIBE\nid:bad\ndestination:/user/" + bob.uid + "/queue/events\n\n\0", true)
          .join();
      assertTrue(e.frame().startsWith("ERROR"));
    }
    try (var a = new Socket(alice)) {
      a.socket.sendText("SEND\ndestination:/queue/events\n\nforged\0", true).join();
      assertTrue(a.frame().startsWith("ERROR"));
    }
  }

  @Test
  void idleSocketClosesAtTokenExpiry() throws Exception {
    String[] parts = alice.token.split("\\.", -1);
    var payload =
        JsonParser.parseString(
                new String(
                    Base64.getUrlDecoder().decode(parts[1]),
                    java.nio.charset.StandardCharsets.UTF_8))
            .getAsJsonObject();
    payload.addProperty("exp", java.time.Instant.now().plusSeconds(3).getEpochSecond());
    String token =
        parts[0]
            + "."
            + Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                    payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))
            + "."
            + parts[2];
    try (var a = new Socket(new User(alice.uid, token))) {
      assertEquals(1008, a.closed.get(6, TimeUnit.SECONDS));
    }
  }

  @Test
  void concurrentReadAndSendPreservesNewUnread() throws Exception {
    var id = conversation();
    var first = send(alice, id, "old", UUID.randomUUID().toString()).body.get("id").getAsString();
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var read =
          pool.submit(
              () ->
                  call(
                      bob,
                      "PUT",
                      "/api/conversations/" + id + "/read",
                      Map.of("messageId", first)));
      var send = pool.submit(() -> send(alice, id, "new", UUID.randomUUID().toString()));
      assertEquals(200, read.get().status);
      assertEquals(200, send.get().status);
    }
    assertEquals(1, summary(bob, id).get("unreadCount").getAsInt());
  }

  @Test
  void userSearchHasStablePagesWithoutEmails() throws Exception {
    String name = "Test" + UUID.randomUUID();
    user(name);
    user(name);
    user(name);
    String query = "/api/users?name=" + name + "&limit=2";
    var first = call(alice, "GET", query, null).body;
    assertEquals(2, first.getAsJsonArray("items").size());
    var second =
        call(alice, "GET", query + "&cursor=" + first.get("nextCursor").getAsString(), null).body;
    assertEquals(1, second.getAsJsonArray("items").size());
    var ids = new HashSet<String>();
    for (var page : List.of(first, second))
      for (var item : page.getAsJsonArray("items")) {
        assertEquals(Set.of("uid", "displayName"), item.getAsJsonObject().keySet());
        ids.add(item.getAsJsonObject().get("uid").getAsString());
      }
    assertEquals(3, ids.size());
  }

  @Test
  void prefixSearchIncludesEmojiSuffixes() throws Exception {
    String prefix = "Emoji" + UUID.randomUUID();
    user(prefix + "😀");
    var result = call(alice, "GET", "/api/users?name=" + prefix, null);
    assertEquals(200, result.status);
    assertEquals(1, result.body.getAsJsonArray("items").size());
  }

  @Test
  void duplicateConcurrentRetriesPersistOnceAndRejectSenderSpoofing() throws Exception {
    var id = conversation();
    String key = UUID.randomUUID().toString();
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var one = pool.submit(() -> send(alice, id, "retry", key));
      var two = pool.submit(() -> send(alice, id, "retry", key));
      var a = one.get();
      var b = two.get();
      assertEquals(200, a.status);
      assertEquals(200, b.status);
      assertEquals(a.body, b.body);
    }
    assertEquals(1, summary(bob, id).get("unreadCount").getAsInt());
    assertEquals(
        400,
        call(
                alice,
                "POST",
                "/api/conversations/" + id + "/messages",
                Map.of(
                    "text",
                    "spoof",
                    "clientMessageId",
                    UUID.randomUUID().toString(),
                    "senderUid",
                    bob.uid))
            .status);
  }

  @org.springframework.beans.factory.annotation.Autowired
  com.google.cloud.firestore.Firestore firestore;

  @Test
  void conversationPaginationUsesStableTieBreaker() throws Exception {
    String a = conversation();
    String b =
        call(alice, "POST", "/api/conversations", Map.of("recipientUid", eve.uid))
            .body
            .get("id")
            .getAsString();
    var fourth = user("Fourth");
    String c =
        call(alice, "POST", "/api/conversations", Map.of("recipientUid", fourth.uid))
            .body
            .get("id")
            .getAsString();
    for (String id : List.of(a, b, c))
      firestore.collection("conversations").document(id).update("updatedAt", 1000L).get();
    var first = call(alice, "GET", "/api/conversations?limit=2", null).body;
    var second =
        call(
                alice,
                "GET",
                "/api/conversations?limit=2&cursor=" + first.get("nextCursor").getAsString(),
                null)
            .body;
    var ids = new HashSet<String>();
    for (var page : List.of(first, second))
      for (var item : page.getAsJsonArray("items"))
        ids.add(item.getAsJsonObject().get("id").getAsString());
    assertEquals(Set.of(a, b, c), ids);
    assertTrue(second.get("nextCursor").isJsonNull());
    assertEquals(
        400,
        call(bob, "GET", "/api/conversations?cursor=" + first.get("nextCursor").getAsString(), null)
            .status);
  }

  @Test
  void profileLookupReturnsPublicNameWithoutEmail() throws Exception {
    var result = call(alice, "GET", "/api/users/" + bob.uid, null);
    assertEquals(200, result.status);
    assertEquals("Bob", result.body.get("displayName").getAsString());
    assertEquals(Set.of("uid", "displayName"), result.body.keySet());
    assertEquals(401, call(null, "GET", "/api/users/" + bob.uid, null).status);
  }
}
