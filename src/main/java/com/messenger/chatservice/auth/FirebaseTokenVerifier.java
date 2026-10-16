package com.messenger.chatservice.auth;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import java.time.Instant;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Component;

@Component
public class FirebaseTokenVerifier implements TokenVerifier {
  private final FirebaseApp app;

  public FirebaseTokenVerifier(FirebaseApp app) {
    this.app = app;
  }

  public ChatPrincipal verify(String token) {
    try {
      var decoded = FirebaseAuth.getInstance(app).verifyIdToken(token);
      var expiry = Instant.ofEpochSecond(((Number) decoded.getClaims().get("exp")).longValue());
      if (!expiry.isAfter(Instant.now())) throw new BadCredentialsException("Expired token");
      return new ChatPrincipal(decoded.getUid(), expiry);
    } catch (Exception e) {
      throw new BadCredentialsException("Invalid Firebase ID token");
    }
  }
}
