package com.mojie.common.pagination;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Opaque keyset cursor bound to a list's owner, resource and filters. */
public final class PageCursor {
    private static final int MAC_LENGTH = 32;
    private final byte[] secret;
    public PageCursor(byte[] secret) {
        if (secret == null || secret.length < 16) throw new IllegalArgumentException("Cursor key must contain at least 16 bytes");
        this.secret = secret.clone();
    }
    public static PageCursor configured() {
        String value = System.getenv().getOrDefault("MOJIE_CURSOR_KEY", "mojie-local-dev-cursor-key-change-me");
        return new PageCursor(value.getBytes(StandardCharsets.UTF_8));
    }
    public String encode(String scope, String marker) {
        byte[] body = (scope + "\0" + marker).getBytes(StandardCharsets.UTF_8);
        byte[] signed = Arrays.copyOf(body, body.length + MAC_LENGTH);
        System.arraycopy(mac(body), 0, signed, body.length, MAC_LENGTH);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signed);
    }
    public String decode(String scope, String token) {
        if (token == null || token.isBlank()) return null;
        try {
            byte[] signed = Base64.getUrlDecoder().decode(token);
            if (signed.length <= MAC_LENGTH) throw invalid();
            byte[] body = Arrays.copyOf(signed, signed.length - MAC_LENGTH);
            byte[] signature = Arrays.copyOfRange(signed, body.length, signed.length);
            if (!MessageDigest.isEqual(mac(body), signature)) throw invalid();
            String text = new String(body, StandardCharsets.UTF_8);
            String prefix = scope + "\0";
            if (!text.startsWith(prefix) || text.length() == prefix.length()) throw invalid();
            return text.substring(prefix.length());
        } catch (IllegalArgumentException error) { throw invalid(); }
    }
    private byte[] mac(byte[] input) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return hmac.doFinal(input);
        } catch (Exception e) { throw new IllegalStateException("HMAC unavailable", e); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid cursor"); }
}
