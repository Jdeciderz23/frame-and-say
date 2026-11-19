package com.groupphoto.consensus.api;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.AuthenticationEntryPoint;

@Configuration
class ApiSecurity {
    @Bean
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, OpaqueTokenIntrospector tokens,
                                               ObjectMapper json) throws Exception {
        AuthenticationEntryPoint unauthorized = (request, response, failure) -> {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            problem(json, response, HttpStatus.UNAUTHORIZED, "A valid bearer token is required");
        };

        return http
                // Requests authenticate with an explicit header. No browser-managed credentials are accepted.
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(routes -> routes
                        .requestMatchers(HttpMethod.GET, "/api/health", "/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler((request, response, failure) ->
                                problem(json, response, HttpStatus.FORBIDDEN, "Access denied")))
                .oauth2ResourceServer(resource -> resource
                        .authenticationEntryPoint(unauthorized)
                        .opaqueToken(config -> config.introspector(tokens)))
                .build();
    }

    private static void problem(ObjectMapper json, HttpServletResponse response, HttpStatus status, String detail)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), ProblemDetail.forStatusAndDetail(status, detail));
    }
}
