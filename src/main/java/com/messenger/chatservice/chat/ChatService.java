package com.messenger.chatservice.chat;

import com.messenger.chatservice.chat.ChatModels.*;
import com.messenger.chatservice.realtime.ChatEventPublisher;
import com.messenger.chatservice.store.ChatStore;
import java.util.*;
import org.slf4j.*;
import org.springframework.stereotype.Service;

@Service
public class ChatService {
  private static final Logger LOG = LoggerFactory.getLogger(ChatService.class);
  private final ChatStore store;
  private final ChatEventPublisher events;

  public ChatService(ChatStore store, ChatEventPublisher events) {
    this.store = store;
    this.events = events;
  }

  public Profile lookupProfile(String uid) {
    return store.profile(uid);
  }

  public Profile profile(String uid, String name) {
    return store.upsertProfile(uid, name);
  }

  public Page<Profile> users(String name, int limit, String cursor) {
    return store.searchUsers(name, limit, cursor);
  }

  public Conversation create(String uid, String recipient) {
    return store.getOrCreateConversation(uid, recipient);
  }

  public Page<Conversation> conversations(String uid, int limit, String cursor) {
    return store.listConversations(uid, limit, cursor);
  }

  public Page<Message> messages(String uid, String id, int limit, String before) {
    return store.listMessages(uid, id, limit, before);
  }

  public Message send(String uid, String id, String text, String key) {
    var result = store.send(uid, id, text, key);
    if (result.created())
      publish(result.members(), new ChatEvent("message.created", id, result.message()));
    return result.message();
  }

  public Conversation read(String uid, String id, String messageId) {
    var result = store.markRead(uid, id, messageId);
    if (result.changed())
      publish(
          result.conversation().memberUids(),
          new ChatEvent(
              "conversation.read",
              id,
              Map.of("uid", uid, "sequence", result.conversation().readSequences().get(uid))));
    return result.conversation();
  }

  public void publish(List<String> recipients, ChatEvent event) {
    try {
      events.publish(recipients, event);
    } catch (RuntimeException ex) {
      LOG.warn(
          "Live event delivery failed; clients must reconcile via REST (type={})", event.type());
    }
  }
}
