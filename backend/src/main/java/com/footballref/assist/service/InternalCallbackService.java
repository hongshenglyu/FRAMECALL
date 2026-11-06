package com.footballref.assist.service;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Component
public class InternalCallbackService extends OncePerRequestFilter {
    private static final Pattern PROGRESS = Pattern.compile("/api/internal/tasks/([1-9][0-9]*)/progress");
    private final ConcurrentHashMap<Long, Grant> grants = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    public InternalCallbackService() { this(Clock.systemUTC()); }
    public InternalCallbackService(Clock clock) { this.clock = clock; }

    public String issue(Long taskId) {
        return issue(taskId, null);
    }

    public String issue(Long taskId, String attemptId) {
        if (taskId == null || taskId <= 0) throw new IllegalArgumentException("Invalid internal task");
        grants.entrySet().removeIf(entry -> !clock.instant().isBefore(entry.getValue().expires()));
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        grants.put(taskId, new Grant(token, clock.instant().plus(Duration.ofHours(2)), attemptId));
        return token;
    }

    public boolean permits(Long taskId, String token) {
        Grant grant = grants.get(taskId);
        return token != null && grant != null && clock.instant().isBefore(grant.expires())
                && MessageDigest.isEqual(grant.token().getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    public void revoke(Long taskId, String token) {
        grants.computeIfPresent(taskId, (id, grant) -> grant.token().equals(token) ? null : grant);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.equals("/api/internal") && !path.startsWith("/api/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var matcher = PROGRESS.matcher(request.getRequestURI().substring(request.getContextPath().length()));
        boolean allowed = false;
        if ("POST".equals(request.getMethod()) && matcher.matches()) {
            try {
                Long taskId = Long.valueOf(matcher.group(1));
                Grant grant = grants.get(taskId);
                String token = request.getHeader("X-Internal-Token");
                allowed = grant != null && token != null && clock.instant().isBefore(grant.expires())
                        && MessageDigest.isEqual(grant.token().getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
                if (allowed) request.setAttribute("analysisAttemptId", grant.attemptId());
            }
            catch (NumberFormatException ignored) { }
        }
        if (!allowed) {
            response.setStatus(401); response.setContentType("application/json");
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write("{\"code\":\"INTERNAL_AUTH_REQUIRED\"}");
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "ai-callback", null, List.of(new SimpleGrantedAuthority("ROLE_INTERNAL"))));
        chain.doFilter(request, response);
    }

    private record Grant(String token, Instant expires, String attemptId) { }
}
