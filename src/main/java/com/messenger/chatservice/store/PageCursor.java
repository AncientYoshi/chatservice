package com.messenger.chatservice.store;

import com.google.gson.Gson;
import com.messenger.chatservice.chat.ChatException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class PageCursor {

  private record Value(int version, String scope, String sort, String id) {}

  public static String encode(String scope, String sort, String id) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
            new Gson().toJson(new Value(1, scope, sort, id)).getBytes(StandardCharsets.UTF_8));
  }

  public static String[] decode(String cursor, String scope) {
    if (cursor == null) return null;
    try {
      if (cursor.length() > 2048) throw new IllegalArgumentException();
      var v =
          new Gson()
              .fromJson(
                  new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8),
                  Value.class);
      if (v.version != 1
          || !scope.equals(v.scope)
          || v.sort == null
          || v.id == null
          || v.id.contains("/")
          || v.id.isBlank()
          || v.id.length() > 128) throw new IllegalArgumentException();
      return new String[] {v.sort, v.id};
    } catch (Exception ex) {
      throw ChatException.bad("Invalid or out-of-scope cursor");
    }
  }

  public static long number(String value) {
    try {
      long n = Long.parseLong(value);
      if (n < 0) throw new IllegalArgumentException();
      return n;
    } catch (Exception e) {
      throw ChatException.bad("Invalid cursor position");
    }
  }
}
