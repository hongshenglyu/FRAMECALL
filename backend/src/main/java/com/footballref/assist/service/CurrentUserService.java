package com.footballref.assist.service;

import com.footballref.assist.domain.UserAccount;
import com.footballref.assist.repository.UserAccountRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class CurrentUserService {
    private final UserAccountRepository users;

    public CurrentUserService(UserAccountRepository users) {
        this.users = users;
    }

    public UserAccount requireUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return users.findByUsername(username).orElseThrow(() -> new IllegalStateException("Current user not found"));
    }
}

