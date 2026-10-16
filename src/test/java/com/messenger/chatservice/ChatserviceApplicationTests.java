package com.messenger.chatservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ChatserviceApplicationTests {
  @org.springframework.test.context.bean.override.mockito.MockitoBean
  com.google.firebase.FirebaseApp firebaseApp;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  com.google.cloud.firestore.Firestore firestore;

  @Test
  void contextLoads() {}
}
