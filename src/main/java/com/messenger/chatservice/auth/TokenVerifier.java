package com.messenger.chatservice.auth;

public interface TokenVerifier {
  ChatPrincipal verify(String token);
}
