package com.messenger.chatservice.chat;

import com.messenger.chatservice.auth.ChatPrincipal;
import com.messenger.chatservice.chat.ChatModels.*;
import com.messenger.chatservice.realtime.TypingService;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ChatController {

  private final ChatService chat;
  private final TypingService typing;

  public ChatController(ChatService chat, TypingService typing) {
    this.chat = chat;
    this.typing = typing;
  }

  @PutMapping("/me")
  public Profile profile(
      @AuthenticationPrincipal ChatPrincipal u, @RequestBody ProfileRequest body) {
    return chat.profile(u.uid(), body.displayName());
  }

  @GetMapping("/users/{uid}")
  public Profile lookupProfile(@PathVariable String uid) {
    return chat.lookupProfile(uid);
  }

  @GetMapping("/users")
  public Page<Profile> users(
      @RequestParam String name,
      @RequestParam(defaultValue = "30") int limit,
      @RequestParam(required = false) String cursor) {
    return chat.users(name, limit, cursor);
  }

  @PostMapping("/conversations")
  public Conversation create(
      @AuthenticationPrincipal ChatPrincipal u, @RequestBody ConversationRequest body) {
    return chat.create(u.uid(), body.recipientUid());
  }

  @GetMapping("/conversations")
  public Page<Conversation> conversations(
      @AuthenticationPrincipal ChatPrincipal u,
      @RequestParam(defaultValue = "30") int limit,
      @RequestParam(required = false) String cursor) {
    return chat.conversations(u.uid(), limit, cursor);
  }

  @GetMapping("/conversations/{id}/messages")
  public Page<Message> messages(
      @AuthenticationPrincipal ChatPrincipal u,
      @PathVariable String id,
      @RequestParam(defaultValue = "30") int limit,
      @RequestParam(required = false) String before) {
    return chat.messages(u.uid(), id, limit, before);
  }

  @PostMapping("/conversations/{id}/messages")
  public Message send(
      @AuthenticationPrincipal ChatPrincipal u,
      @PathVariable String id,
      @RequestBody SendRequest body) {
    return chat.send(u.uid(), id, body.text(), body.clientMessageId());
  }

  @PutMapping("/conversations/{id}/read")
  public Conversation read(
      @AuthenticationPrincipal ChatPrincipal u,
      @PathVariable String id,
      @RequestBody ReadRequest body) {
    return chat.read(u.uid(), id, body.messageId());
  }

  @PostMapping("/conversations/{id}/typing")
  public Map<String, Boolean> typing(
      @AuthenticationPrincipal ChatPrincipal u,
      @PathVariable String id,
      @RequestBody TypingRequest body) {
    if (body.typing() == null) throw ChatException.bad("typing is required");
    typing.publish(u.uid(), id, body.typing());
    return Map.of("accepted", true);
  }
}
