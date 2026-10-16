package com.messenger.chatservice.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.messenger.chatservice.chat.ChatModels.*;
import com.messenger.chatservice.store.ChatStore;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessageRulesTest {
  @Test
  void deliveryFailureDoesNotUndoCommittedMessage() {
    var store = mock(ChatStore.class);
    var message = new Message("id", "conversation", "alice", "hello", 1, "2026-10-16T00:00:00Z");
    when(store.send("alice", "conversation", "hello", "key"))
        .thenReturn(new SendResult(message, true, List.of("alice", "bob")));
    var service =
        new ChatService(
            store,
            (members, event) -> {
              throw new IllegalStateException("broker unavailable");
            });
    assertEquals(message, service.send("alice", "conversation", "hello", "key"));
  }

  @Test
  void duplicateSendDoesNotEmitAnotherNotification() {
    var store = mock(ChatStore.class);
    var message = new Message("id", "conversation", "alice", "hello", 1, "2026-10-16T00:00:00Z");
    when(store.send("alice", "conversation", "hello", "key"))
        .thenReturn(new SendResult(message, false, List.of("alice", "bob")));
    var events = new java.util.ArrayList<ChatEvent>();
    var service = new ChatService(store, (members, event) -> events.add(event));
    assertEquals(message, service.send("alice", "conversation", "hello", "key"));
    assertTrue(events.isEmpty());
  }
}
