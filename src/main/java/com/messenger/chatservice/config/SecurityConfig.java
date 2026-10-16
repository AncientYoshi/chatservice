package com.messenger.chatservice.config;

import com.messenger.chatservice.auth.*;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;

@Configuration
public class SecurityConfig {
  @Bean
  SecurityFilterChain filterChain(
      HttpSecurity http,
      TokenVerifier verifier,
      @org.springframework.beans.factory.annotation.Qualifier("corsConfigurationSource")
          CorsConfigurationSource cors)
      throws Exception {
    return http.csrf(c -> c.disable())
        .cors(c -> c.configurationSource(cors))
        .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            c -> c.requestMatchers("/api/health", "/ws").permitAll().anyRequest().authenticated())
        .exceptionHandling(
            c -> c.authenticationEntryPoint((req, res, ex) -> BearerTokenFilter.unauthorized(res)))
        .addFilterBefore(
            new BearerTokenFilter(verifier), UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource(
      @Value("${chat.allowed-origins}") List<String> origins) {
    var cors = new CorsConfiguration();
    cors.setAllowedOrigins(origins);
    cors.setAllowedMethods(List.of("GET", "POST", "PUT", "OPTIONS"));
    cors.setAllowedHeaders(List.of("Authorization", "Content-Type"));
    cors.setMaxAge(3600L);
    var source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", cors);
    return source;
  }
}
