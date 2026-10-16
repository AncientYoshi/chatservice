package com.messenger.chatservice.store;

import static com.messenger.chatservice.store.FirestoreMapper.*;

import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.*;
import com.messenger.chatservice.chat.*;
import com.messenger.chatservice.chat.ChatModels.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Repository;

@Repository
public class FirestoreChatStore implements ChatStore {
  private final Firestore db;

  public FirestoreChatStore(Firestore db) {
    this.db = db;
  }

  private CollectionReference users() {
    return db.collection("users");
  }

  private DocumentReference conversationRef(String id) {
    return db.collection("conversations").document(ChatValidation.documentId(id));
  }

  private <T> T await(ApiFuture<T> f) {
    try {
      return f.get(15, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ChatException(503, "STORE_UNAVAILABLE", "Storage request interrupted");
    } catch (Exception e) {
      Throwable cause = e;
      while (cause != null) {
        if (cause instanceof ChatException c) throw c;
        cause = cause.getCause();
      }
      throw new ChatException(
          503,
          "STORE_UNAVAILABLE",
          "Storage is temporarily unavailable; retry with the same clientMessageId");
    }
  }

  private Map<String, Object> requireMember(DocumentSnapshot snapshot, String uid) {
    if (!snapshot.exists()) throw new ChatException(404, "NOT_FOUND", "Conversation not found");
    var d = snapshot.getData();
    if (!FirestoreMapper.members(d).contains(uid))
      throw new ChatException(403, "FORBIDDEN", "Conversation membership required");
    return d;
  }

  public Profile profile(String uid) {
    ChatValidation.uid(uid);
    var snapshot = await(users().document(uid).get());
    if (!snapshot.exists()) throw new ChatException(404, "NOT_FOUND", "Profile not found");
    return FirestoreMapper.profile(snapshot);
  }

  public Profile upsertProfile(String uid, String displayName) {
    ChatValidation.uid(uid);
    String name = ChatValidation.name(displayName);
    await(
        users()
            .document(uid)
            .set(Map.of("displayName", name, "searchName", name.toLowerCase(Locale.ROOT))));
    return new Profile(uid, name);
  }

  public Page<Profile> searchUsers(String name, int limit, String cursor) {
    String search = ChatValidation.name(name).toLowerCase(Locale.ROOT);
    ChatValidation.limit(limit);
    String scope = "users:" + search;
    var position = PageCursor.decode(cursor, scope);
    Query q =
        users()
            .whereGreaterThanOrEqualTo("searchName", search)
            .orderBy("searchName")
            .orderBy(FieldPath.documentId());
    String end = prefixEnd(search);
    if (end != null) q = q.whereLessThan("searchName", end);
    if (position != null) q = q.startAfter(position[0], position[1]);
    var docs = await(q.limit(limit + 1).get()).getDocuments();
    boolean more = docs.size() > limit;
    var page = docs.subList(0, Math.min(limit, docs.size()));
    var last = page.isEmpty() ? null : page.getLast();
    return new Page<>(
        page.stream().map(FirestoreMapper::profile).toList(),
        more ? PageCursor.encode(scope, last.getString("searchName"), last.getId()) : null);
  }

  // Firestore uses UTF-8 ordering. Increment the final available code point,
  // rather than using \uf8ff, which would exclude emoji and other supplementary characters.
  private static String prefixEnd(String prefix) {
    int[] points = prefix.codePoints().toArray();
    for (int i = points.length - 1; i >= 0; i--) {
      if (points[i] < Character.MAX_CODE_POINT) {
        points[i]++;
        if (points[i] == Character.MIN_SURROGATE) points[i] = Character.MAX_SURROGATE + 1;
        return new String(points, 0, i + 1);
      }
    }
    return null;
  }

  public Conversation getOrCreateConversation(String uid, String recipientUid) {
    ChatValidation.uid(uid);
    ChatValidation.uid(recipientUid);
    if (uid.equals(recipientUid)) throw ChatException.bad("Choose another user");
    String id = pair(uid, recipientUid);
    var ref = conversationRef(id);
    return await(
        db.runTransaction(
            tx -> {
              var existing = tx.get(ref).get();
              if (existing.exists()) return conversation(id, requireMember(existing, uid), uid);
              var first = tx.get(users().document(uid)).get();
              var second = tx.get(users().document(recipientUid)).get();
              if (!first.exists() || !second.exists())
                throw new ChatException(
                    404, "NOT_FOUND", "Both users must create their profile first");
              var members = new ArrayList<>(List.of(uid, recipientUid));
              Collections.sort(members);
              Map<String, Object> zero = Map.of(hash(uid), 0L, hash(recipientUid), 0L);
              Map<String, Object> d = new HashMap<>();
              d.put("memberUids", members);
              d.put("sequence", 0L);
              d.put("incoming", zero);
              d.put("readIncoming", zero);
              d.put("readSequences", zero);
              d.put("updatedAt", System.currentTimeMillis());
              tx.create(ref, d);
              return conversation(id, d, uid);
            }));
  }

  public Page<Conversation> listConversations(String uid, int limit, String cursor) {
    ChatValidation.limit(limit);
    String scope = "conversations:" + uid;
    var position = PageCursor.decode(cursor, scope);
    Query q =
        db.collection("conversations")
            .whereArrayContains("memberUids", uid)
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .orderBy(FieldPath.documentId(), Query.Direction.DESCENDING);
    if (position != null) q = q.startAfter(PageCursor.number(position[0]), position[1]);
    var docs = await(q.limit(limit + 1).get()).getDocuments();
    boolean more = docs.size() > limit;
    var page = docs.subList(0, Math.min(limit, docs.size()));
    var last = page.isEmpty() ? null : page.getLast();
    return new Page<>(
        page.stream().map(d -> conversation(d.getId(), d.getData(), uid)).toList(),
        more ? PageCursor.encode(scope, last.getLong("updatedAt").toString(), last.getId()) : null);
  }

  public Page<Message> listMessages(String uid, String id, int limit, String before) {
    ChatValidation.limit(limit);
    var ref = conversationRef(id);
    requireMember(await(ref.get()), uid);
    String scope = "messages:" + id;
    var position = PageCursor.decode(before, scope);
    Query q = ref.collection("messages").orderBy("sequence", Query.Direction.DESCENDING);
    if (position != null) q = q.startAfter(PageCursor.number(position[0]));
    var docs = await(q.limit(limit + 1).get()).getDocuments();
    boolean more = docs.size() > limit;
    var page = docs.subList(0, Math.min(limit, docs.size()));
    var last = page.isEmpty() ? null : page.getLast();
    return new Page<>(
        page.stream().map(d -> message(d.getId(), d.getData())).toList(),
        more ? PageCursor.encode(scope, last.getLong("sequence").toString(), last.getId()) : null);
  }

  public List<String> members(String uid, String id) {
    return FirestoreMapper.members(requireMember(await(conversationRef(id).get()), uid));
  }

  public SendResult send(String uid, String id, String text, String clientMessageId) {
    var content = ChatValidation.text(text);
    var key = ChatValidation.uuid(clientMessageId);
    var ref = conversationRef(id);
    String messageId = hash(uid.length() + ":" + uid + key);
    var messageRef = ref.collection("messages").document(messageId);
    return await(
        db.runTransaction(
            tx -> {
              var d = requireMember(tx.get(ref).get(), uid);
              var previous = tx.get(messageRef).get();
              if (previous.exists()) {
                if (!content.equals(previous.getString("text")))
                  throw new ChatException(
                      409,
                      "IDEMPOTENCY_CONFLICT",
                      "clientMessageId was already used for different text");
                return new SendResult(
                    message(messageId, previous.getData()), false, FirestoreMapper.members(d));
              }
              long sequence = number(d, "sequence") + 1, now = System.currentTimeMillis();
              // Snapshot incoming-message counts at each sequence. Read receipts use
              // these snapshots so own sends and concurrent sends do not corrupt unread counts.
              var incoming = map(d, "incoming");
              for (var member : FirestoreMapper.members(d))
                if (!member.equals(uid))
                  incoming.put(hash(member), number(incoming, hash(member)) + 1);
              Map<String, Object> m = new HashMap<>();
              m.put("id", messageId);
              m.put("conversationId", id);
              m.put("senderUid", uid);
              m.put("text", content);
              m.put("sequence", sequence);
              m.put("createdAt", now);
              m.put("incoming", incoming);
              tx.create(messageRef, m);
              tx.update(
                  ref,
                  Map.of(
                      "sequence",
                      sequence,
                      "incoming",
                      incoming,
                      "lastMessage",
                      m,
                      "updatedAt",
                      Math.max(now, number(d, "updatedAt"))));
              return new SendResult(message(messageId, m), true, FirestoreMapper.members(d));
            },
            TransactionOptions.create(10)));
  }

  public ReadResult markRead(String uid, String id, String messageId) {
    var ref = conversationRef(id);
    ChatValidation.documentId(messageId);
    return await(
        db.runTransaction(
            tx -> {
              var d = requireMember(tx.get(ref).get(), uid);
              var target = tx.get(ref.collection("messages").document(messageId)).get();
              if (!target.exists())
                throw new ChatException(404, "NOT_FOUND", "Message not found in this conversation");
              var read = map(d, "readSequences");
              String userKey = hash(uid);
              long sequence = target.getLong("sequence");
              boolean changed = sequence > number(read, userKey);
              if (changed) {
                read.put(userKey, sequence);
                var seen = map(d, "readIncoming");
                seen.put(userKey, number(map(target.getData(), "incoming"), userKey));
                tx.update(ref, Map.of("readSequences", read, "readIncoming", seen));
                d.put("readSequences", read);
                d.put("readIncoming", seen);
              }
              return new ReadResult(conversation(id, d, uid), changed);
            },
            TransactionOptions.create(10)));
  }
}
