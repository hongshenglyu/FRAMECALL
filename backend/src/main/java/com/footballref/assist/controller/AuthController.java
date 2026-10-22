package com.footballref.assist.controller;

import com.footballref.assist.domain.UserAccount;
import com.footballref.assist.repository.UserAccountRepository;
import com.footballref.assist.service.JwtService;
import com.footballref.assist.service.PasswordService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final UserAccountRepository users;
    private final PasswordService passwordService;
    private final JwtService jwtService;

    public AuthController(UserAccountRepository users, PasswordService passwordService, JwtService jwtService) {
        this.users = users;
        this.passwordService = passwordService;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest request) {
        UserAccount user = users.findByUsername(request.username())
                .filter(UserAccount::isEnabled)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
        if (!passwordService.matches(request.password(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
        return Map.of(
                "token", jwtService.issue(user),
                "user", Map.of(
                        "id", user.getId(),
                        "username", user.getUsername(),
                        "displayName", user.getDisplayName(),
                        "role", user.getRole()
                )
        );
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }
}

