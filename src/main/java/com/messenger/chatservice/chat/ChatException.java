package com.messenger.chatservice.chat;

public class ChatException extends RuntimeException {

  public final int status;
  public final String code;

  public ChatException(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public static ChatException bad(String message) {
    return new ChatException(400, "INVALID_REQUEST", message);
  }
}