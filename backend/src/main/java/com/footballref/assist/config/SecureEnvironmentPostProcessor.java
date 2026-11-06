package com.footballref.assist.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;

import java.net.URI;
import java.nio.file.Path;
import java.util.*;

/** Validate explicit security configuration before any database or filesystem bean exists. */
public class SecureEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    @Override
    public int getOrder() { return ConfigDataEnvironmentPostProcessor.ORDER + 1; }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("app.jwt-secret", secret(environment, "FRAMECALL_JWT_SECRET"));
        settings.put("app.ai-service-token", secret(environment, "FRAMECALL_AI_SERVICE_TOKEN"));
        settings.put("app.ai-service-url", origin(environment, "FRAMECALL_AI_SERVICE_URL"));
        settings.put("app.internal-callback-base-url", origin(environment, "FRAMECALL_CALLBACK_BASE_URL"));
        String root = required(environment, "FRAMECALL_STORAGE_ROOT");
        try {
            if (!Path.of(root).isAbsolute()) throw invalid("FRAMECALL_STORAGE_ROOT");
        } catch (java.nio.file.InvalidPathException ignored) { throw invalid("FRAMECALL_STORAGE_ROOT"); }
        settings.put("app.storage-root", root);
        String database = required(environment, "FRAMECALL_DATABASE_URL");
        if (!(database.startsWith("jdbc:h2:") || database.startsWith("jdbc:mysql://"))
                || database.toLowerCase(Locale.ROOT).matches(".*(password|user)=.*")) {
            throw invalid("FRAMECALL_DATABASE_URL");
        }
        settings.put("spring.datasource.url", database);
        settings.put("spring.datasource.username", required(environment, "FRAMECALL_DB_USERNAME"));
        String password = property(environment, "FRAMECALL_DB_PASSWORD", null);
        if (password == null || !database.startsWith("jdbc:h2:mem:") && password.length() < 16) {
            throw invalid("FRAMECALL_DB_PASSWORD");
        }
        settings.put("spring.datasource.password", password);
        if (!database.startsWith("jdbc:h2:mem:")) secret(environment, "FRAMECALL_DB_PASSWORD");
        validateProfiles(environment, database);
        if (enabled(environment, "FRAMECALL_BOOTSTRAP_ADMIN")) {
            if (!enabled(environment, "FRAMECALL_BOOTSTRAP_CONFIRMED")) throw invalid("FRAMECALL_BOOTSTRAP_CONFIRMED");
            String username = required(environment, "FRAMECALL_BOOTSTRAP_USERNAME");
            if (!username.matches("[A-Za-z0-9_.-]{3,60}")) throw invalid("FRAMECALL_BOOTSTRAP_USERNAME");
            if (secret(environment, "FRAMECALL_BOOTSTRAP_PASSWORD").length() > 72) throw invalid("FRAMECALL_BOOTSTRAP_PASSWORD");
        }
        // Explicit new inputs win over legacy app.* defaults, including command-line aliases.
        environment.getPropertySources().addFirst(new MapPropertySource("framecall-validated-security", settings));
    }

    public static void validateProfiles(Environment environment, String database) {
        Set<String> profiles = new HashSet<>(Arrays.asList(environment.getActiveProfiles()));
        if (profiles.contains("demo") && (profiles.size() != 1 || !database.startsWith("jdbc:h2:mem:")
                || !enabled(environment, "FRAMECALL_DEMO_ISOLATED"))) throw invalid("demo isolation");
    }

    public static boolean enabled(Environment environment, String key) {
        String value = property(environment, key, "false");
        if (!value.equals("true") && !value.equals("false")) throw invalid(key);
        return value.equals("true");
    }

    public static String secret(Environment environment, String key) {
        String value = required(environment, key);
        String lower = value.toLowerCase(Locale.ROOT);
        if (!value.matches("[A-Za-z0-9_+/=\\-]{32,256}") || value.chars().distinct().count() < 12
                || List.of("changeme", "change-me", "replace", "placeholder", "example", "default", "password", "demo").stream().anyMatch(lower::contains)) {
            throw invalid(key);
        }
        return value;
    }

    private static String required(Environment environment, String key) {
        String value = property(environment, key, null);
        if (value == null || value.isBlank() || value.contains("${")) throw invalid(key);
        return value;
    }

    private static String property(Environment environment, String key, String fallback) {
        try { return environment.getProperty(key, fallback); }
        catch (IllegalArgumentException ignored) { throw invalid(key); }
    }

    private static String origin(Environment environment, String key) {
        String value = required(environment, key);
        try {
            URI uri = URI.create(value); String host = uri.getHost();
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || host == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/")) || uri.getPort() == 0 || uri.getPort() > 65535) throw invalid(key);
            if (!host.equals("localhost") && !host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}") && !host.matches("\\[[0-9a-fA-F:]+]")) throw invalid(key);
            if (host.matches("[0-9.]+")) for (String part : host.split("\\.")) if (Integer.parseInt(part) > 255) throw invalid(key);
        } catch (IllegalArgumentException ignored) { throw invalid(key); }
        return value;
    }

    private static IllegalStateException invalid(String key) {
        return new IllegalStateException("Missing or unsafe security configuration: " + key);
    }
}
