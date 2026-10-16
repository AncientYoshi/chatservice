package com.messenger.chatservice.store;

import com.google.cloud.firestore.DocumentSnapshot;
import com.messenger.chatservice.chat.ChatModels.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

final class FirestoreMapper {
  static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static String pair(String first, String second) {
    var sorted = new ArrayList<>(List.of(first, second));
    Collections.sort(sorted);
    return hash(
        sorted.get(0).length()
            + ":"
            + sorted.get(0)
            + sorted.get(1).length()
            + ":"
            + sorted.get(1));
  }

  static long number(Map<String, Object> data, String key) {
    var v = data.get(key);
    return v instanceof Number n ? n.longValue() : 0L;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> map(Map<String, Object> data, String key) {
    return new HashMap<>((Map<String, Object>) data.get(key));
  }

  @SuppressWarnings("unchecked")
  static List<String> members(Map<String, Object> data) {
    return (List<String>) data.get("memberUids");
  }

  static Message message(String id, Map<String, Object> m) {
    return new Message(
        id,
        (String) m.get("conversationId"),
        (String) m.get("senderUid"),
        (String) m.get("text"),
        number(m, "sequence"),
        Instant.ofEpochMilli(number(m, "createdAt")).toString());
  }

  static Profile profile(DocumentSnapshot d) {
    return new Profile(d.getId(), d.getString("displayName"));
  }

  @SuppressWarnings("unchecked")
  static Conversation conversation(String id, Map<String, Object> d, String uid) {
    var read = map(d, "readSequences");
    var received = map(d, "incoming");
    var seen = map(d, "readIncoming");
    Map<String, Long> readable = new LinkedHashMap<>();
    for (var member : members(d)) readable.put(member, number(read, hash(member)));
    var last = (Map<String, Object>) d.get("lastMessage");
    return new Conversation(
        id,
        members(d),
        last == null ? null : message((String) last.get("id"), last),
        number(received, hash(uid)) - number(seen, hash(uid)),
        readable,
        Instant.ofEpochMilli(number(d, "updatedAt")).toString());
  }
}
