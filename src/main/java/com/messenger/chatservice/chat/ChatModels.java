package com.messenger.chatservice.chat;

import java.util.*;

public final class ChatModels {
  private ChatModels() {}

  public record Profile(String uid, String displayName) {}

  public record Message(
      String id,
      String conversationId,
      String senderUid,
      String text,
      long sequence,
      String createdAt) {}

  public record Conversation(
      String id,
      List<String> memberUids,
      Message lastMessage,
      long unreadCount,
      Map<String, Long> readSequences,
      String updatedAt) {}

  public record Page<T>(List<T> items, String nextCursor) {}

  public record SendResult(Message message, boolean created, List<String> members) {}

  public record ReadResult(Conversation conversation, boolean changed) {}

  public record ChatEvent(String type, String conversationId, Object data) {}

  public record ProfileRequest(String displayName) {}

  public record ConversationRequest(String recipientUid) {}

  public record SendRequest(String text, String clientMessageId) {}

  public record ReadRequest(String messageId) {}

  public record TypingRequest(Boolean typing) {}
}
