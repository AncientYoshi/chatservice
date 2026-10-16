package com.messenger.chatservice.config;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.*;
import com.google.firebase.cloud.FirestoreClient;
import java.io.IOException;
import java.util.Date;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

@Configuration
public class FirebaseConfig {

  @Bean(destroyMethod = "delete")
  public FirebaseApp firebaseApp(
      @Value("${firebase.project-id}") String projectId,
      @Value("${firebase.emulator:false}") boolean emulator)
      throws IOException {
    GoogleCredentials credentials;
    if (emulator) {
      if (!projectId.startsWith("demo-")
          || !localHost(System.getenv("FIRESTORE_EMULATOR_HOST"))
          || !localHost(System.getenv("FIREBASE_AUTH_EMULATOR_HOST")))
        throw new IllegalStateException(
            "Emulator mode requires a demo- project and both localhost emulator hosts");
      credentials = GoogleCredentials.create(new AccessToken("owner", new Date(Long.MAX_VALUE)));
    } else {
      if (System.getenv("FIRESTORE_EMULATOR_HOST") != null
          || System.getenv("FIREBASE_AUTH_EMULATOR_HOST") != null)
        throw new IllegalStateException(
            "Use the emulator profile with emulator environment variables");
      credentials = GoogleCredentials.getApplicationDefault();
    }
    return FirebaseApp.initializeApp(
        FirebaseOptions.builder().setProjectId(projectId).setCredentials(credentials).build(),
        "chat-" + java.util.UUID.randomUUID());
  }

  private boolean localHost(String host) {
    return host != null && host.matches("(localhost|127[.]0[.]0[.]1):[0-9]{1,5}");
  }

  // FirebaseApp owns and closes this client.
  @Bean(destroyMethod = "")
  public Firestore firestore(FirebaseApp app) {
    return FirestoreClient.getFirestore(app);
  }
}
