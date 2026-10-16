package com.messenger.chatservice.chat;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ChatValidationTest {
  @Test
  void textIsTrimmedAndLimited() {
    assertEquals("hello", ChatValidation.text(" hello "));
    assertThrows(ChatException.class, () -> ChatValidation.text(" "));
    assertThrows(ChatException.class, () -> ChatValidation.text("x".repeat(4001)));
    assertEquals(4000, ChatValidation.text("x".repeat(4000)).length());
  }

  @Test
  void pagesAndDocumentIdsAreBounded() {
    assertThrows(ChatException.class, () -> ChatValidation.limit(101));
    assertThrows(ChatException.class, () -> ChatValidation.limit(0));
    assertThrows(ChatException.class, () -> ChatValidation.uid("../../bad"));
    assertThrows(ChatException.class, () -> ChatValidation.uid(".."));
    assertThrows(ChatException.class, () -> ChatValidation.uuid("bad"));
  }
}
