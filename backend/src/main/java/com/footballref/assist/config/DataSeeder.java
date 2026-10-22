package com.footballref.assist.config;

import com.footballref.assist.domain.UserAccount;
import com.footballref.assist.domain.enums.Role;
import com.footballref.assist.repository.UserAccountRepository;
import com.footballref.assist.service.PasswordService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class DataSeeder implements CommandLineRunner {
    private final UserAccountRepository users;
    private final PasswordService passwordService;

    public DataSeeder(UserAccountRepository users, PasswordService passwordService) {
        this.users = users;
        this.passwordService = passwordService;
    }

    @Override
    public void run(String... args) {
        seed("admin", "admin123", Role.ADMIN, "System Admin");
        seed("referee", "referee123", Role.REFEREE, "Demo Referee");
    }

    private void seed(String username, String password, Role role, String displayName) {
        if (users.existsByUsername(username)) {
            return;
        }
        UserAccount user = new UserAccount();
        user.setUsername(username);
        user.setPasswordHash(passwordService.hash(password));
        user.setRole(role);
        user.setDisplayName(displayName);
        user.setEnabled(true);
        users.save(user);
    }
}

