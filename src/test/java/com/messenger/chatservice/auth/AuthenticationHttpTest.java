package com.messenger.chatservice.auth;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.google.cloud.firestore.Firestore;
import com.google.firebase.FirebaseApp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class AuthenticationHttpTest {
  @MockitoBean FirebaseApp firebaseApp;
  @MockitoBean Firestore firestore;
  @MockitoBean TokenVerifier verifier;
  @MockitoBean com.messenger.chatservice.store.ChatStore store;
  @Autowired WebApplicationContext context;
  MockMvc mvc;

  @BeforeEach
  void setup() {
    org.mockito.Mockito.when(verifier.verify(org.mockito.ArgumentMatchers.anyString()))
        .thenThrow(
            new org.springframework.security.authentication.BadCredentialsException("invalid"));
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void healthIsPublic() throws Exception {
    mvc.perform(get("/api/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  void missingTokenIs401() throws Exception {
    mvc.perform(get("/api/conversations"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
  }

  @Test
  void invalidTokenIs401() throws Exception {
    mvc.perform(get("/api/conversations").header("Authorization", "Bearer invalid"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void expiredTokenIs401() throws Exception {
    org.mockito.Mockito.doReturn(
            new ChatPrincipal("alice", java.time.Instant.now().minusSeconds(1)))
        .when(verifier)
        .verify("expired");
    mvc.perform(get("/api/conversations").header("Authorization", "Bearer expired"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void unknownCorsOriginIsDenied() throws Exception {
    mvc.perform(
            options("/api/conversations")
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isForbidden());
  }

  @Test
  void internalErrorsAreSanitized() throws Exception {
    org.mockito.Mockito.doReturn(
            new ChatPrincipal("alice", java.time.Instant.now().plusSeconds(60)))
        .when(verifier)
        .verify("valid");
    org.mockito.Mockito.when(store.upsertProfile("alice", "Alice"))
        .thenThrow(new IllegalStateException("private diagnostic"));
    mvc.perform(
            put("/api/me")
                .header("Authorization", "Bearer valid")
                .contentType("application/json")
                .content("{\"displayName\":\"Alice\"}"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
        .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
  }
}
