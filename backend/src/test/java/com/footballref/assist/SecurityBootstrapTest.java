package com.footballref.assist;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballref.assist.config.DataSeeder;
import com.footballref.assist.config.SecureEnvironmentPostProcessor;
import com.footballref.assist.controller.AuthController;
import com.footballref.assist.domain.UserAccount;
import com.footballref.assist.domain.enums.Role;
import com.footballref.assist.repository.UserAccountRepository;
import com.footballref.assist.service.JwtService;
import com.footballref.assist.service.PasswordService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class SecurityBootstrapTest {
    private final SecureEnvironmentPostProcessor policy = new SecureEnvironmentPostProcessor();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void defaultAndProductionNeverCreateAccountsWithoutExplicitAuthorization() throws Exception {
        for (String profile : List.of("", "prod", "development")) {
            MockEnvironment environment = environment(valid(), profile);
            policy.postProcessEnvironment(environment, new SpringApplication());
            UserAccountRepository users = mock(UserAccountRepository.class);
            new DataSeeder(users, new PasswordService(), environment).run();
            verifyNoInteractions(users);
        }
    }

    @Test
    void everyMandatoryNewSettingFailsClosedWithoutLegacyFallback() {
        for (String key : List.of("FRAMECALL_JWT_SECRET", "FRAMECALL_AI_SERVICE_TOKEN", "FRAMECALL_AI_SERVICE_URL",
                "FRAMECALL_CALLBACK_BASE_URL", "FRAMECALL_STORAGE_ROOT", "FRAMECALL_DATABASE_URL", "FRAMECALL_DB_USERNAME", "FRAMECALL_DB_PASSWORD")) {
            Map<String, Object> input = valid(); input.remove(key);
            input.put("app.jwt-secret", randomSecret()); input.put("app.ai-service-token", randomSecret());
            var failure = assertThrows(IllegalStateException.class, () -> policy.postProcessEnvironment(environment(input, "prod"), new SpringApplication()));
            assertTrue(failure.getMessage().contains(key));
        }
    }

    @Test
    void weakEmptyAndPlaceholderSecretsAreRejectedWithoutEchoingValues() {
        for (String key : List.of("FRAMECALL_JWT_SECRET", "FRAMECALL_AI_SERVICE_TOKEN", "FRAMECALL_DB_PASSWORD")) {
            for (String value : List.of("", "0".repeat(64), "CHANGE_ME_EXAMPLE_CREDENTIAL_0123456789", "${UNSET_SECRET}")) {
                Map<String, Object> input = valid(); input.put(key, value);
                if (key.equals("FRAMECALL_DB_PASSWORD")) input.put("FRAMECALL_DATABASE_URL", "jdbc:mysql://127.0.0.1:1/synthetic_not_opened");
                var failure = assertThrows(IllegalStateException.class, () -> policy.postProcessEnvironment(environment(input, "prod"), new SpringApplication()));
                assertEquals("Missing or unsafe security configuration: " + key, failure.getMessage());
            }
        }
    }

    @Test
    void demoRequiresExclusiveProfileExplicitIsolationAndMemoryDatabase() {
        for (String[] profiles : List.of(new String[]{"demo", "prod"}, new String[]{"demo", "development"}, new String[]{"demo"})) {
            Map<String, Object> input = valid();
            if (profiles.length > 1) input.put("FRAMECALL_DEMO_ISOLATED", "true");
            assertThrows(IllegalStateException.class, () -> policy.postProcessEnvironment(environment(input, profiles), new SpringApplication()));
        }
        Map<String, Object> file = valid(); file.put("FRAMECALL_DEMO_ISOLATED", "true");
        file.put("FRAMECALL_DATABASE_URL", "jdbc:h2:file:synthetic-not-opened"); file.put("FRAMECALL_DB_PASSWORD", randomSecret());
        assertThrows(IllegalStateException.class, () -> policy.postProcessEnvironment(environment(file, "demo"), new SpringApplication()));
        Map<String, Object> safe = valid(); safe.put("FRAMECALL_DEMO_ISOLATED", "true");
        MockEnvironment environment = environment(safe, "demo");
        policy.postProcessEnvironment(environment, new SpringApplication());
        UserAccountRepository users = mock(UserAccountRepository.class);
        new DataSeeder(users, new PasswordService(), environment).run(); verifyNoInteractions(users);
    }

    @Test
    void explicitConfirmedBootstrapCreatesOnlyCallerSuppliedAccount() throws Exception {
        for (String profile : List.of("prod", "demo")) {
            Map<String, Object> input = bootstrap(); input.put("FRAMECALL_DEMO_ISOLATED", "true");
            MockEnvironment environment = environment(input, profile); policy.postProcessEnvironment(environment, new SpringApplication());
            UserAccountRepository users = mock(UserAccountRepository.class); PasswordService passwords = new PasswordService();
            new DataSeeder(users, passwords, environment).run();
            ArgumentCaptor<UserAccount> account = ArgumentCaptor.forClass(UserAccount.class); verify(users).save(account.capture());
            assertEquals(input.get("FRAMECALL_BOOTSTRAP_USERNAME"), account.getValue().getUsername());
            assertEquals(Role.ADMIN, account.getValue().getRole());
            assertTrue(passwords.matches((String) input.get("FRAMECALL_BOOTSTRAP_PASSWORD"), account.getValue().getPasswordHash()));
        }
    }

    @Test
    void missingConfirmationOrExistingUsersCannotResetOrEscalateAccounts() {
        Map<String, Object> input = bootstrap(); input.put("FRAMECALL_BOOTSTRAP_CONFIRMED", "false");
        assertThrows(IllegalStateException.class, () -> policy.postProcessEnvironment(environment(input, "prod"), new SpringApplication()));
        input.put("FRAMECALL_BOOTSTRAP_CONFIRMED", "true"); MockEnvironment environment = environment(input, "prod");
        policy.postProcessEnvironment(environment, new SpringApplication());
        UserAccountRepository users = mock(UserAccountRepository.class); when(users.count()).thenReturn(1L);
        assertThrows(IllegalStateException.class, () -> new DataSeeder(users, new PasswordService(), environment).run());
        verify(users, never()).save(any()); verify(users, never()).deleteAll();
    }

    @Test
    void explicitNewKeysOverrideOldAliasesAndRotationInvalidatesOldTokens() {
        Map<String, Object> input = valid(); String old = randomSecret(); input.put("app.jwt-secret", old);
        MockEnvironment environment = environment(input, "prod"); policy.postProcessEnvironment(environment, new SpringApplication());
        assertEquals(input.get("FRAMECALL_JWT_SECRET"), environment.getProperty("app.jwt-secret"));
        JwtService jwt = new JwtService(environment, json); UserAccount account = new UserAccount();
        account.setUsername("synthetic-existing"); account.setDisplayName("synthetic"); account.setRole(Role.REFEREE);
        String token = jwt.issue(account); assertEquals(account.getUsername(), jwt.subject(token));
        input.put("FRAMECALL_JWT_SECRET", randomSecret());
        JwtService rotated = new JwtService(environment(input, "prod"), json); assertNull(rotated.subject(token));
        input.remove("FRAMECALL_JWT_SECRET"); assertThrows(IllegalStateException.class, () -> new JwtService(environment(input, "prod"), json));
    }

    @Test
    void jwtRejectsWrongAlgorithmAndMissingOrExpiredExpiryEvenWithValidSignature() throws Exception {
        Map<String, Object> input = valid(); JwtService jwt = new JwtService(environment(input, "prod"), json);
        String secret = (String) input.get("FRAMECALL_JWT_SECRET");
        for (String algorithm : List.of("none", "HS512")) assertNull(jwt.subject(token(algorithm, Map.of("sub", "fixture", "exp", Instant.now().plusSeconds(60).getEpochSecond()), secret)));
        assertNull(jwt.subject(token("HS256", Map.of("sub", "fixture"), secret)));
        assertNull(jwt.subject(token("HS256", Map.of("sub", "fixture", "exp", 1), secret)));
        assertEquals("fixture", jwt.subject(token("HS256", Map.of("sub", "fixture", "exp", Instant.now().plusSeconds(60).getEpochSecond()), secret)));
    }

    @Test
    void actualBootRejectsUnsafeConfigBeforeContextPreparationOrFilesystemWrites(CapturedOutput output) throws Exception {
        Path parent = Files.createTempDirectory("framecall-startup-guard-"); Path storage = parent.resolve("must-not-create");
        String rejected = "UNSAFE_EXAMPLE_CREDENTIAL_0123456789";
        SpringApplication application = new SpringApplication(FootballRefAssistApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE); application.setLogStartupInfo(false);
        AtomicBoolean prepared = new AtomicBoolean();
        application.addListeners((org.springframework.context.ApplicationListener<ApplicationPreparedEvent>) event -> prepared.set(true));
        assertThrows(IllegalStateException.class, () -> application.run(
                "--spring.config.location=optional:file:" + parent.resolve("absent.properties"),
                "--spring.profiles.active=prod", "--FRAMECALL_JWT_SECRET=" + rejected,
                "--FRAMECALL_STORAGE_ROOT=" + storage));
        assertFalse(prepared.get()); assertFalse(Files.exists(storage)); assertFalse(output.getAll().contains(rejected));
        Files.delete(parent);
    }

    @Test
    void securityProcessorIsRegisteredInActualTestClasspath() throws Exception {
        var resource = getClass().getClassLoader().getResourceAsStream("META-INF/spring.factories");
        assertNotNull(resource);
        try (resource) {
            Properties registration = new Properties(); registration.load(resource);
            assertEquals(SecureEnvironmentPostProcessor.class.getName(), registration.getProperty("org.springframework.boot.env.EnvironmentPostProcessor"));
        }
    }

    @Test
    void actualDefaultProductionAndDemoContextsUseOnlySyntheticH2AndRespectInitialization() throws Exception {
        Path work = Files.createTempDirectory("framecall-profile-proof-");
        try {
            for (String profile : List.of("", "prod", "demo")) {
                Map<String, Object> input = profile.equals("demo") ? bootstrap() : valid();
                input.put("FRAMECALL_DEMO_ISOLATED", "true");
                input.put("FRAMECALL_DATABASE_URL", "jdbc:h2:mem:profile_" + UUID.randomUUID());
                input.put("FRAMECALL_STORAGE_ROOT", work.resolve(profile.isEmpty() ? "default" : profile).toString().replace('\\', '/'));
                input.put("spring.datasource.driver-class-name", "org.h2.Driver");
                input.put("spring.jpa.hibernate.ddl-auto", "create-drop");
                input.put("spring.main.banner-mode", "off"); input.put("logging.level.root", "OFF");
                Properties properties = new Properties(); properties.putAll(input);
                Path configuration = work.resolve("synthetic-" + profile + ".properties");
                try (var stream = Files.newOutputStream(configuration)) { properties.store(stream, "Synthetic isolated fixture only"); }
                SpringApplication application = new SpringApplication(FootballRefAssistApplication.class);
                application.setWebApplicationType(WebApplicationType.SERVLET); application.setLogStartupInfo(false);
                List<String> arguments = new ArrayList<>(List.of("--spring.config.location=file:" + configuration.toString().replace('\\', '/'), "--spring.profiles.active=" + profile,
                        "--server.address=127.0.0.1", "--server.port=0"));
                input.forEach((key, value) -> { if (key.startsWith("FRAMECALL_")) arguments.add("--" + key + "=" + value); });
                try (var context = application.run(arguments.toArray(String[]::new))) {
                    UserAccountRepository users = context.getBean(UserAccountRepository.class);
                    assertEquals(profile.equals("demo") ? 1 : 0, users.count());
                    if (profile.equals("demo")) {
                        UserAccount admin = users.findByUsername((String) input.get("FRAMECALL_BOOTSTRAP_USERNAME")).orElseThrow();
                        assertEquals(Role.ADMIN, admin.getRole());
                        assertTrue(context.getBean(PasswordService.class).matches((String) input.get("FRAMECALL_BOOTSTRAP_PASSWORD"), admin.getPasswordHash()));
                    } else {
                        String password = randomSecret(); UserAccount existing = new UserAccount();
                        existing.setUsername("existing_" + UUID.randomUUID()); existing.setDisplayName("Synthetic existing user"); existing.setRole(Role.REFEREE);
                        existing.setPasswordHash(context.getBean(PasswordService.class).hash(password)); users.save(existing);
                        assertNotNull(context.getBean(AuthController.class).login(new AuthController.LoginRequest(existing.getUsername(), password)).get("token"));
                        context.getBean(DataSeeder.class).run(); assertEquals(1, users.count());
                    }
                }
            }
        } finally {
            Path temp = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
            assertTrue(work.toRealPath().startsWith(temp)); assertTrue(work.getFileName().toString().startsWith("framecall-profile-proof-"));
            try (var paths = Files.walk(work)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
        }
    }

    private Map<String, Object> valid() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("FRAMECALL_JWT_SECRET", randomSecret()); settings.put("FRAMECALL_AI_SERVICE_TOKEN", randomSecret());
        settings.put("FRAMECALL_AI_SERVICE_URL", "http://127.0.0.1:1"); settings.put("FRAMECALL_CALLBACK_BASE_URL", "http://127.0.0.1:1");
        settings.put("FRAMECALL_STORAGE_ROOT", Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("synthetic-not-created").toString());
        settings.put("FRAMECALL_DATABASE_URL", "jdbc:h2:mem:bootstrap_fixture"); settings.put("FRAMECALL_DB_USERNAME", "fixture"); settings.put("FRAMECALL_DB_PASSWORD", "");
        settings.put("FRAMECALL_BOOTSTRAP_ADMIN", "false"); settings.put("FRAMECALL_BOOTSTRAP_CONFIRMED", "false");
        return settings;
    }
    private Map<String, Object> bootstrap() {
        Map<String, Object> settings = valid(); settings.put("FRAMECALL_BOOTSTRAP_ADMIN", "true"); settings.put("FRAMECALL_BOOTSTRAP_CONFIRMED", "true");
        settings.put("FRAMECALL_BOOTSTRAP_USERNAME", "init_" + UUID.randomUUID()); settings.put("FRAMECALL_BOOTSTRAP_PASSWORD", randomSecret()); return settings;
    }
    private MockEnvironment environment(Map<String, Object> values, String... profiles) {
        MockEnvironment environment = new MockEnvironment(); environment.setActiveProfiles(Arrays.stream(profiles).filter(p -> !p.isBlank()).toArray(String[]::new));
        environment.getPropertySources().addFirst(new MapPropertySource("fixture-input", values)); return environment;
    }
    private String token(String algorithm, Map<String, Object> payload, String secret) throws Exception {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        String data = encoder.encodeToString(json.writeValueAsBytes(Map.of("alg", algorithm, "typ", "JWT"))) + "." + encoder.encodeToString(json.writeValueAsBytes(payload));
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return data + "." + encoder.encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
    private static String randomSecret() { byte[] bytes = new byte[40]; new SecureRandom().nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
}
