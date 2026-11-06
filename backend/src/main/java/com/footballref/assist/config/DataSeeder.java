package com.footballref.assist.config;

import com.footballref.assist.domain.UserAccount;
import com.footballref.assist.domain.enums.Role;
import com.footballref.assist.repository.UserAccountRepository;
import com.footballref.assist.service.PasswordService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;

@Component
public class DataSeeder implements CommandLineRunner {
    private final UserAccountRepository users;
    private final PasswordService passwordService;
    private final Environment environment;

    public DataSeeder(UserAccountRepository users, PasswordService passwordService, Environment environment) {
        this.users = users;
        this.passwordService = passwordService;
        this.environment = environment;
    }

    @Override
    public void run(String... args) {
        if (!SecureEnvironmentPostProcessor.enabled(environment, "FRAMECALL_BOOTSTRAP_ADMIN")) return;
        if (!SecureEnvironmentPostProcessor.enabled(environment, "FRAMECALL_BOOTSTRAP_CONFIRMED")) {
            throw new IllegalStateException("Administrator bootstrap requires local confirmation");
        }
        SecureEnvironmentPostProcessor.validateProfiles(environment, environment.getRequiredProperty("spring.datasource.url"));
        if (users.count() != 0) throw new IllegalStateException("Bootstrap requires an empty account store; existing accounts were not modified");
        String username = environment.getRequiredProperty("FRAMECALL_BOOTSTRAP_USERNAME");
        if (!username.matches("[A-Za-z0-9_.-]{3,60}")) throw new IllegalStateException("Invalid bootstrap username");
        String password = SecureEnvironmentPostProcessor.secret(environment, "FRAMECALL_BOOTSTRAP_PASSWORD");
        seed(username, password, Role.ADMIN, "Initial administrator");
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
