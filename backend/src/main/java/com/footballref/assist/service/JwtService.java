package com.footballref.assist.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballref.assist.domain.UserAccount;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class JwtService {
    private final String secret;
    private final ObjectMapper objectMapper;

    public JwtService(@Value("${app.jwt-secret}") String secret, ObjectMapper objectMapper) {
        this.secret = secret;
        this.objectMapper = objectMapper;
    }

    public String issue(UserAccount user) {
        try {
            Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sub", user.getUsername());
            payload.put("uid", user.getId());
            payload.put("role", user.getRole().name());
            payload.put("name", user.getDisplayName());
            payload.put("exp", Instant.now().plusSeconds(8 * 60 * 60).getEpochSecond());
            String encodedHeader = base64Url(objectMapper.writeValueAsBytes(header));
            String encodedPayload = base64Url(objectMapper.writeValueAsBytes(payload));
            String signature = sign(encodedHeader + "." + encodedPayload);
            return encodedHeader + "." + encodedPayload + "." + signature;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot issue JWT", e);
        }
    }

    public String subject(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return null;
            }
            String expected = sign(parts[0] + "." + parts[1]);
            if (!MessageDigestLike.constantTimeEquals(expected, parts[2])) {
                return null;
            }
            byte[] payloadBytes = Base64.getUrlDecoder().decode(pad(parts[1]));
            Map<?, ?> payload = objectMapper.readValue(payloadBytes, Map.class);
            Object exp = payload.get("exp");
            if (exp instanceof Number number && number.longValue() < Instant.now().getEpochSecond()) {
                return null;
            }
            Object sub = payload.get("sub");
            return sub == null ? null : sub.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String sign(String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return base64Url(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    private static String base64Url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static String pad(String value) {
        int padding = (4 - value.length() % 4) % 4;
        return value + "=".repeat(padding);
    }

    private static class MessageDigestLike {
        static boolean constantTimeEquals(String left, String right) {
            if (left == null || right == null) {
                return false;
            }
            byte[] a = left.getBytes(StandardCharsets.UTF_8);
            byte[] b = right.getBytes(StandardCharsets.UTF_8);
            if (a.length != b.length) {
                return false;
            }
            int result = 0;
            for (int i = 0; i < a.length; i++) {
                result |= a[i] ^ b[i];
            }
            return result == 0;
        }
    }
}

