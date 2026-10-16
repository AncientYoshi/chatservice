package com.messenger.chatservice.realtime;

import com.messenger.chatservice.chat.ChatModels.ChatEvent;
import java.util.List;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
public class StompEventPublisher implements ChatEventPublisher {
  private final SimpMessagingTemplate template;

  public StompEventPublisher(SimpMessagingTemplate template) {
    this.template = template;
  }

  public void publish(List<String> recipients, ChatEvent event) {
    RuntimeException failure = null;
    for (var uid : recipients)
      try {
        template.convertAndSendToUser(uid, "/queue/events", event);
      } catch (RuntimeException e) {
        failure = e;
      }
    if (failure != null) throw failure;
  }
}
