package com.messenger.chatservice.realtime;

import static org.junit.jupiter.api.Assertions.*;

import com.messenger.chatservice.auth.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;

class StompSecurityTest {
  @Test
  void restrictsSubscriptionsAndClientPublishing() {
    var registry = new SocketSessionRegistry();
    try {
      var guard =
          new StompAuthInterceptor(
              t -> new ChatPrincipal("alice", Instant.now().plusSeconds(60)), registry);
      for (var destination : List.of("/topic/all", "/queue/events", "/user/bob/queue/events")) {
        var h = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        h.setUser(new ChatPrincipal("alice", Instant.now().plusSeconds(60)));
        h.setDestination(destination);
        assertThrows(
            AccessDeniedException.class,
            () ->
                guard.preSend(
                    MessageBuilder.createMessage(new byte[0], h.getMessageHeaders()), null));
      }
      var send = StompHeaderAccessor.create(StompCommand.SEND);
      send.setUser(new ChatPrincipal("alice", Instant.now().plusSeconds(60)));
      send.setDestination("/queue/events");
      assertThrows(
          AccessDeniedException.class,
          () ->
              guard.preSend(
                  MessageBuilder.createMessage(new byte[0], send.getMessageHeaders()), null));
      var valid = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
      valid.setUser(new ChatPrincipal("alice", Instant.now().plusSeconds(60)));
      valid.setDestination("/user/queue/events");
      assertNotNull(
          guard.preSend(
              MessageBuilder.createMessage(new byte[0], valid.getMessageHeaders()), null));
    } finally {
      registry.shutdown();
    }
  }

  @Test
  void rejectsUnauthenticatedAndExpiredFrames() {
    var registry = new SocketSessionRegistry();
    try {
      var guard =
          new StompAuthInterceptor(t -> new ChatPrincipal("alice", Instant.EPOCH), registry);
      var h = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
      h.setDestination("/user/queue/events");
      assertThrows(
          AccessDeniedException.class,
          () ->
              guard.preSend(
                  MessageBuilder.createMessage(new byte[0], h.getMessageHeaders()), null));
      var expired = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
      expired.setDestination("/user/queue/events");
      expired.setUser(new ChatPrincipal("alice", Instant.EPOCH));
      assertThrows(
          AccessDeniedException.class,
          () ->
              guard.preSend(
                  MessageBuilder.createMessage(new byte[0], expired.getMessageHeaders()), null));
    } finally {
      registry.shutdown();
    }
  }

  @Test
  void connectRequiresTokenAndCannotBeRepeated() {
    var registry = new SocketSessionRegistry();
    try {
      var guard =
          new StompAuthInterceptor(
              t -> new ChatPrincipal("alice", Instant.now().plusSeconds(60)), registry);
      var missing = StompHeaderAccessor.create(StompCommand.CONNECT);
      missing.setSessionAttributes(new HashMap<>());
      missing.setLeaveMutable(true);
      assertThrows(
          AccessDeniedException.class,
          () ->
              guard.preSend(
                  MessageBuilder.createMessage(new byte[0], missing.getMessageHeaders()), null));
      var attributes = new HashMap<String, Object>();
      var first = StompHeaderAccessor.create(StompCommand.CONNECT);
      first.setSessionAttributes(attributes);
      first.setNativeHeader("Authorization", "Bearer token");
      first.setLeaveMutable(true);
      guard.preSend(MessageBuilder.createMessage(new byte[0], first.getMessageHeaders()), null);
      assertEquals("alice", first.getUser().getName());
      var second = StompHeaderAccessor.create(StompCommand.CONNECT);
      second.setSessionAttributes(attributes);
      second.setNativeHeader("Authorization", "Bearer token");
      second.setLeaveMutable(true);
      assertThrows(
          AccessDeniedException.class,
          () ->
              guard.preSend(
                  MessageBuilder.createMessage(new byte[0], second.getMessageHeaders()), null));
    } finally {
      registry.shutdown();
    }
  }
}
