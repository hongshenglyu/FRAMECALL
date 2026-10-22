package com.footballref.assist.controller;

import com.footballref.assist.domain.UserAccount;
import com.footballref.assist.domain.enums.Role;
import com.footballref.assist.repository.UserAccountRepository;
import com.footballref.assist.service.PasswordService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {
    private final UserAccountRepository users;
    private final PasswordService passwordService;

    public UserController(UserAccountRepository users, PasswordService passwordService) {
        this.users = users;
        this.passwordService = passwordService;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return users.findAll().stream().map(this::toMap).toList();
    }

    @PostMapping
    public Map<String, Object> create(@Valid @RequestBody CreateUserRequest request) {
        if (users.existsByUsername(request.username())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
        }
        UserAccount user = new UserAccount();
        user.setUsername(request.username());
        user.setPasswordHash(passwordService.hash(request.password()));
        user.setDisplayName(request.displayName());
        user.setRole(request.role());
        user.setEnabled(true);
        return toMap(users.save(user));
    }

    private Map<String, Object> toMap(UserAccount user) {
        return Map.of(
                "id", user.getId(),
                "username", user.getUsername(),
                "displayName", user.getDisplayName(),
                "role", user.getRole(),
                "enabled", user.isEnabled(),
                "createdAt", user.getCreatedAt()
        );
    }

    public record CreateUserRequest(
            @NotBlank String username,
            @NotBlank String password,
            @NotBlank String displayName,
            @NotNull Role role
    ) {
    }
}

