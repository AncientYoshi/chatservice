package com.messenger.chatservice.store;

import com.messenger.chatservice.chat.ChatModels.*;
import java.util.List;

public interface ChatStore {
  Profile profile(String uid);

  Profile upsertProfile(String uid, String displayName);

  Page<Profile> searchUsers(String name, int limit, String cursor);

  Conversation getOrCreateConversation(String uid, String recipientUid);

  Page<Conversation> listConversations(String uid, int limit, String cursor);

  Page<Message> listMessages(String uid, String id, int limit, String before);

  SendResult send(String uid, String id, String text, String clientMessageId);

  ReadResult markRead(String uid, String id, String messageId);

  List<String> members(String uid, String id);
}
