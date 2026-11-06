package com.footballref.assist.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.client.RestTemplate;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import com.footballref.assist.service.InternalCallbackService;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter,
                                          InternalCallbackService internalCallbacks) throws Exception {
        http.csrf(csrf -> csrf.disable());
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/login", "/error").permitAll()
                .anyRequest().authenticated());
        http.exceptionHandling(errors -> errors.defaultAuthenticationEntryPointFor(
                (request, response, exception) -> response.setStatus(401),
                new AntPathRequestMatcher("/api/files/**")));
        http.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        http.addFilterBefore(internalCallbacks, JwtAuthFilter.class);
        return http.build();
    }

    @Bean
    RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
