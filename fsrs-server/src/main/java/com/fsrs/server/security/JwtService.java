package com.fsrs.server.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsrs.server.common.ApiException;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Issues and verifies HS256 access tokens.
 *
 * <p>Implemented directly on {@code javax.crypto} rather than by pulling in a JWT library:
 * the format is three base64url segments and one HMAC, the verification rules this service
 * needs are entirely standard (signature, {@code exp}, constant-time comparison), and an
 * extra dependency in the request path is a cost that buys nothing here. If refresh tokens,
 * key rotation or asymmetric keys were ever needed, swapping this class for Nimbus/JJWT
 * would be the right call - the surrounding filter would not change.
 *
 * <p>The payload carries only {@code sub} (user id), {@code username}, {@code iat} and
 * {@code exp}. Deliberately not the timezone or the FSRS weights: those change, a token
 * lives for a week, and a stale copy inside a token cannot be invalidated. They are read
 * from MySQL on every request instead.
 */
@Component
public class JwtService {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** Fixed header; a fixed string avoids re-encoding the same JSON on every login. */
    private static final String HEADER = ENCODER.encodeToString(
            "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    private final byte[] secret;
    private final long ttlSeconds;
    private final ObjectMapper objectMapper;

    public JwtService(com.fsrs.server.config.FsrsProperties properties, ObjectMapper objectMapper) {
        byte[] key = properties.jwt().secret().getBytes(StandardCharsets.UTF_8);
        if (key.length < 32) {
            // A short HS256 key is guessable, so this is a startup failure rather than a warning.
            throw new IllegalStateException("fsrs.jwt.secret must be at least 32 bytes for HS256");
        }
        this.secret = key;
        this.ttlSeconds = properties.jwt().ttlSeconds();
        this.objectMapper = objectMapper;
    }

    public String issue(long userId, String username) {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", Long.toString(userId));
        claims.put("username", username);
        claims.put("iat", now);
        claims.put("exp", now + ttlSeconds);

        String payload;
        try {
            payload = ENCODER.encodeToString(objectMapper.writeValueAsBytes(claims));
        } catch (Exception ex) {
            throw new IllegalStateException("unable to serialize JWT claims", ex);
        }
        String signingInput = HEADER + "." + payload;
        return signingInput + "." + ENCODER.encodeToString(hmac(signingInput));
    }

    /**
     * @return the authenticated principal
     * @throws ApiException 401 when the token is malformed, tampered with or expired
     */
    public AuthUser parse(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw ApiException.unauthorized("malformed token");
        }

        byte[] expected = hmac(parts[0] + "." + parts[1]);
        byte[] actual;
        try {
            actual = DECODER.decode(parts[2]);
        } catch (IllegalArgumentException ex) {
            throw ApiException.unauthorized("malformed token signature");
        }
        // Constant-time comparison: a byte-by-byte early exit leaks how much of the
        // signature was guessed correctly.
        if (!MessageDigest.isEqual(expected, actual)) {
            throw ApiException.unauthorized("invalid token signature");
        }

        JsonNode claims;
        try {
            claims = objectMapper.readTree(DECODER.decode(parts[1]));
        } catch (Exception ex) {
            throw ApiException.unauthorized("malformed token payload");
        }

        long exp = claims.path("exp").asLong(0);
        if (exp <= Instant.now().getEpochSecond()) {
            throw ApiException.unauthorized("token expired");
        }
        try {
            return new AuthUser(Long.parseLong(claims.path("sub").asText()), claims.path("username").asText());
        } catch (NumberFormatException ex) {
            throw ApiException.unauthorized("malformed token subject");
        }
    }

    private byte[] hmac(String signingInput) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", ex);
        }
    }

    /** The authenticated caller: an id and a name, no roles in this service. */
    public record AuthUser(long userId, String username) {
    }
}
