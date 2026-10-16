package com.messenger.chatservice.realtime;

import com.messenger.chatservice.chat.ChatModels.ChatEvent;
import java.util.List;

public interface ChatEventPublisher {
  void publish(List<String> recipients, ChatEvent event);
}
