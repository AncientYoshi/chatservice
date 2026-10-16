package com.messenger.chatservice.auth;

import java.security.Principal;
import java.time.Instant;

public record ChatPrincipal(String uid, Instant expiresAt) implements Principal {
  @Override
  public String getName() {
    return uid;
  }
}
