package com.messenger.chatservice.realtime;

import com.messenger.chatservice.auth.*;
import java.time.Instant;
import org.springframework.messaging.*;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
public class StompAuthInterceptor implements ChannelInterceptor {
  private final TokenVerifier verifier;
  private final SocketSessionRegistry sessions;

  public StompAuthInterceptor(TokenVerifier verifier, SocketSessionRegistry sessions) {
    this.verifier = verifier;
    this.sessions = sessions;
  }

  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    var h = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
    if (h == null) throw new AccessDeniedException("STOMP required");
    var command = h.getCommand();
    if (command == null || command == StompCommand.DISCONNECT) return message;
    if (command == StompCommand.CONNECT) {
      var attributes = h.getSessionAttributes();
      if (attributes == null || attributes.putIfAbsent("chat.connected", true) != null)
        throw new AccessDeniedException("Invalid connection");
      var header = h.getFirstNativeHeader("Authorization");
      if (header == null || !header.startsWith("Bearer ") || header.length() > 16384)
        throw new AccessDeniedException("Firebase ID token required");
      var user = verifier.verify(header.substring(7));
      if (!user.expiresAt().isAfter(Instant.now()))
        throw new AccessDeniedException("Expired token");
      h.setUser(user);
      sessions.expires(h.getSessionId(), user.expiresAt());
      return message;
    }
    if (!(h.getUser() instanceof ChatPrincipal user) || !user.expiresAt().isAfter(Instant.now()))
      throw new AccessDeniedException("Authentication required");
    if (command == StompCommand.SUBSCRIBE && "/user/queue/events".equals(h.getDestination()))
      return message;
    if (command == StompCommand.UNSUBSCRIBE) return message;
    throw new AccessDeniedException("Destination or command not allowed");
  }
}
