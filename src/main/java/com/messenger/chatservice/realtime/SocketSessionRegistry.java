package com.messenger.chatservice.realtime;

import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;

@Component
public class SocketSessionRegistry {
  private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, ScheduledFuture<?>> expiry = new ConcurrentHashMap<>();
  private final ScheduledThreadPoolExecutor timer =
      new ScheduledThreadPoolExecutor(
          1,
          r -> {
            var t = new Thread(r, "chat-token-expiry");
            t.setDaemon(true);
            return t;
          });

  public SocketSessionRegistry() {
    timer.setRemoveOnCancelPolicy(true);
  }

  public void add(WebSocketSession session) {
    sessions.put(session.getId(), session);
    // Unauthenticated sockets cannot occupy a connection indefinitely.
    expires(session.getId(), Instant.now().plusSeconds(15));
  }

  public synchronized void expires(String id, Instant deadline) {
    if (id == null || !sessions.containsKey(id)) return;
    var old = expiry.remove(id);
    if (old != null) old.cancel(false);
    expiry.put(
        id,
        timer.schedule(
            () -> close(id),
            Math.max(0, Duration.between(Instant.now(), deadline).toMillis()),
            TimeUnit.MILLISECONDS));
  }

  private void close(String id) {
    var s = sessions.get(id);
    try {
      if (s != null && s.isOpen()) s.close(CloseStatus.POLICY_VIOLATION);
    } catch (java.io.IOException ignored) {
    } finally {
      remove(id);
    }
  }

  public synchronized void remove(String id) {
    sessions.remove(id);
    var f = expiry.remove(id);
    if (f != null) f.cancel(false);
  }

  @PreDestroy
  public void shutdown() {
    timer.shutdownNow();
    for (var id : sessions.keySet()) close(id);
  }
}
