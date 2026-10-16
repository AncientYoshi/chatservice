package com.messenger.chatservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(
    exclude =
        org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class)
public class ChatserviceApplication {

  public static void main(String[] args) {
    SpringApplication.run(ChatserviceApplication.class, args);
  }
}
