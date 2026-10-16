package com.messenger.chatservice.config;

import com.messenger.chatservice.realtime.*;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.messaging.converter.*;
import org.springframework.messaging.simp.config.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
  private final StompAuthInterceptor guard;
  private final SocketSessionRegistry sessions;
  private final String[] origins;

  public WebSocketConfig(
      StompAuthInterceptor guard,
      SocketSessionRegistry sessions,
      @Value("${chat.allowed-origins}") String[] origins) {
    this.guard = guard;
    this.sessions = sessions;
    this.origins = origins;
  }

  @Bean
  public ThreadPoolTaskScheduler chatHeartbeatScheduler() {
    var s = new ThreadPoolTaskScheduler();
    s.setPoolSize(1);
    s.setThreadNamePrefix("chat-heartbeat-");
    return s;
  }

  @Override
  public void registerStompEndpoints(StompEndpointRegistry registry) {
    registry.addEndpoint("/ws").setAllowedOrigins(origins);
  }

  @Override
  public void configureMessageBroker(MessageBrokerRegistry registry) {
    registry
        .enableSimpleBroker("/queue")
        .setHeartbeatValue(new long[] {10000, 10000})
        .setTaskScheduler(chatHeartbeatScheduler());
    registry.setUserDestinationPrefix("/user");
    registry.setApplicationDestinationPrefixes("/app");
    registry.setPreservePublishOrder(true);
  }

  @Override
  public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(guard);
  }

  @Override
  public boolean configureMessageConverters(List<MessageConverter> converters) {
    converters.add(new JacksonJsonMessageConverter());
    return false;
  }

  @Override
  public void configureWebSocketTransport(WebSocketTransportRegistration registry) {
    registry
        .setMessageSizeLimit(32 * 1024)
        .setSendBufferSizeLimit(256 * 1024)
        .setSendTimeLimit(10000);
    registry.addDecoratorFactory(
        handler ->
            new WebSocketHandlerDecorator(handler) {
              @Override
              public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sessions.add(session);
                super.afterConnectionEstablished(session);
              }

              @Override
              public void afterConnectionClosed(WebSocketSession session, CloseStatus status)
                  throws Exception {
                sessions.remove(session.getId());
                super.afterConnectionClosed(session, status);
              }
            });
  }
}
