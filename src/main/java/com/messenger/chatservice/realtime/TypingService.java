package com.messenger.chatservice.realtime;

import com.messenger.chatservice.chat.*;
import com.messenger.chatservice.chat.ChatModels.ChatEvent;
import com.messenger.chatservice.store.ChatStore;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class TypingService {
  private final ChatStore store;
  private final ChatService chat;

  private record Window(long second, int count) {}

  private final Map<String, Window> windows = new HashMap<>();

  public TypingService(ChatStore store, ChatService chat) {
    this.store = store;
    this.chat = chat;
  }

  public void publish(String uid, String id, boolean typing) {
    // Rate-limit before storage reads to bound calls made by one authenticated user.
    synchronized (windows) {
      long second = System.currentTimeMillis() / 1000;
      windows.entrySet().removeIf(e -> e.getValue().second < second);
      var old = windows.get(uid);
      if (old != null && old.count >= 5)
        throw new ChatException(429, "RATE_LIMITED", "Too many typing updates");
      if (old == null && windows.size() >= 10000)
        throw new ChatException(429, "RATE_LIMITED", "Typing service is busy");
      windows.put(uid, new Window(second, old == null ? 1 : old.count + 1));
    }
    var recipients = store.members(uid, id).stream().filter(member -> !member.equals(uid)).toList();
    chat.publish(
        recipients,
        new ChatEvent(
            "conversation.typing",
            id,
            Map.of(
                "uid",
                uid,
                "typing",
                typing,
                "expiresAt",
                Instant.now().plusSeconds(typing ? 5 : 0).toString())));
  }
}
