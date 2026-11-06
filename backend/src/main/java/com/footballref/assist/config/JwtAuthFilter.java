package com.footballref.assist.config;

import com.footballref.assist.domain.UserAccount;
import com.footballref.assist.repository.UserAccountRepository;
import com.footballref.assist.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Cookie;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {
    public static final String MEDIA_COOKIE = "framecall_media";
    private final JwtService jwtService;
    private final UserAccountRepository users;

    public JwtAuthFilter(JwtService jwtService, UserAccountRepository users) {
        this.jwtService = jwtService;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        // A media cookie can never authorize writes or other business endpoints.
        if (header == null && ("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()))
                && request.getRequestURI().equals(request.getContextPath() + "/api/files") && request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (MEDIA_COOKIE.equals(cookie.getName())) {
                    header = "Bearer " + cookie.getValue();
                    break;
                }
            }
        }
        if (header != null && header.startsWith("Bearer ")) {
            String username = jwtService.subject(header.substring(7));
            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                users.findByUsername(username).filter(UserAccount::isEnabled).ifPresent(user -> {
                    var auth = new UsernamePasswordAuthenticationToken(
                            user.getUsername(),
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))
                    );
                    SecurityContextHolder.getContext().setAuthentication(auth);
                });
            }
        }
        filterChain.doFilter(request, response);
    }
}
