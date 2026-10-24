package com.dartwatch.kakao;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class KakaoCrypto {
    private final KakaoProperties properties;
    private final SecureRandom random = new SecureRandom();
    public KakaoCrypto(KakaoProperties properties) { this.properties = properties; }
    public void validateKey() { key(); }
    private byte[] key() {
        try {
            byte[] key = Base64.getDecoder().decode(properties.getEncryptionKey());
            if (key.length != 32) throw new IllegalArgumentException();
            return key;
        } catch (RuntimeException ex) { throw new KakaoFailure(KakaoFailure.Kind.CONFIGURATION); }
    }
    public String encrypt(String token, String purpose) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array());
        } catch (Exception ex) { throw new KakaoFailure(KakaoFailure.Kind.CONFIGURATION); }
    }
    public String decrypt(String value, String purpose) {
        try {
            if (!value.startsWith("v1:")) throw new IllegalArgumentException();
            byte[] bytes = Base64.getDecoder().decode(value.substring(3));
            var buffer = ByteBuffer.wrap(bytes);
            byte[] nonce = new byte[12]; buffer.get(nonce);
            byte[] encrypted = new byte[buffer.remaining()]; buffer.get(encrypted);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception ex) { throw new KakaoFailure(KakaoFailure.Kind.CONFIGURATION); }
    }
    public String clientFingerprint() {
        try {
            if (properties.getRestApiKey() == null || properties.getRestApiKey().isBlank()) throw new IllegalArgumentException();
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(properties.getRestApiKey().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) { throw new KakaoFailure(KakaoFailure.Kind.CONFIGURATION); }
    }
}
