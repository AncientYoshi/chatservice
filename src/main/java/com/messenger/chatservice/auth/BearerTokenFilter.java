package com.messenger.chatservice.auth;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class BearerTokenFilter extends OncePerRequestFilter {

  private final TokenVerifier verifier;

  public BearerTokenFilter(TokenVerifier verifier) {
    this.verifier = verifier;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    if (header != null) {
      try {
        if (!header.startsWith("Bearer ") || header.length() > 16384)
          throw new org.springframework.security.authentication.BadCredentialsException(
              "Invalid bearer token");
        var user = verifier.verify(header.substring(7));
        if (!user.expiresAt().isAfter(Instant.now()))
          throw new org.springframework.security.authentication.BadCredentialsException(
              "Expired token");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
      } catch (AuthenticationException ex) {
        unauthorized(response);
        return;
      }
    }
    chain.doFilter(request, response);
  }

  public static void unauthorized(HttpServletResponse response) throws IOException {
    response.setStatus(401);
    response.setContentType("application/json");
    response
        .getWriter()
        .write(
            "{\"code\":\"UNAUTHENTICATED\",\"message\":\"A valid Firebase ID token is required\"}");
  }
}
