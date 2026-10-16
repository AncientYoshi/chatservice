package com.messenger.chatservice.chat;

public final class ChatValidation {
  private ChatValidation() {}

  public static String text(String value) {
    return trimmed(value, 4000, "text");
  }

  public static String name(String value) {
    return trimmed(value, 80, "displayName");
  }

  private static String trimmed(String value, int max, String field) {
    if (value == null || value.strip().isEmpty() || value.strip().length() > max)
      throw ChatException.bad(field + " must contain 1–" + max + " characters");
    return value.strip();
  }

  public static String uid(String value) {
    if (value == null
        || value.isBlank()
        || value.length() > 128
        || value.equals(".")
        || value.equals("..")
        || value.matches("__.*__")
        || value.contains("/")
        || value.chars().anyMatch(Character::isISOControl))
      throw ChatException.bad("Invalid user ID");
    return value;
  }

  public static String documentId(String value) {
    if (value == null || !value.matches("[a-f0-9]{64}"))
      throw ChatException.bad("Invalid document ID");
    return value;
  }

  public static String uuid(String value) {
    if (value == null
        || !value.matches(
            "[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"))
      throw ChatException.bad("clientMessageId must be a UUID");
    return value.toLowerCase(java.util.Locale.ROOT);
  }

  public static int limit(int value) {
    if (value < 1 || value > 100) throw ChatException.bad("limit must be 1–100");
    return value;
  }
}
