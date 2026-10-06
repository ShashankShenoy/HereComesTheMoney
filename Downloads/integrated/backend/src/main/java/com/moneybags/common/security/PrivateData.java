package com.moneybags.common.security;

import com.moneybags.common.api.BusinessException;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Local demonstration secret store; replace via a key-management adapter when deployed. */
@Service
public class PrivateData {

  private final SecretKey key;
  private final SecureRandom random = new SecureRandom();

  public PrivateData(
    @Value("${moneybags.cif.key-file:runtime/cif.key}") String location
  ) {
    try {
      Path path = Path.of(location).toAbsolutePath();
      Files.createDirectories(path.getParent());
      if (!Files.exists(path)) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        try {
          Files.writeString(
            path,
            Base64.getEncoder().encodeToString(bytes),
            StandardOpenOption.CREATE_NEW
          );
        } catch (FileAlreadyExistsException ignored) {}
      }
      byte[] bytes = Base64.getDecoder().decode(Files.readString(path).trim());
      if (bytes.length != 32) throw new IllegalStateException(
        "Invalid CIF key"
      );
      key = new SecretKeySpec(bytes, "AES");
    } catch (Exception e) {
      throw new IllegalStateException(
        "Cannot load local CIF encryption key",
        e
      );
    }
  }

  /** Encrypts each value with a fresh GCM nonce and authentication tag. */
  public byte[] encrypt(byte[] value) {
    try {
      byte[] iv = new byte[12];
      random.nextBytes(iv);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
      byte[] out = c.doFinal(value);
      return ByteBuffer.allocate(12 + out.length).put(iv).put(out).array();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Decryption fails closed if the original key or ciphertext is unavailable. */
  public byte[] decrypt(byte[] value) {
    try {
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(
        Cipher.DECRYPT_MODE,
        key,
        new GCMParameterSpec(128, Arrays.copyOf(value, 12))
      );
      return c.doFinal(value, 12, value.length - 12);
    } catch (Exception e) {
      throw new BusinessException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "CIF_KEY_UNAVAILABLE",
        "Restore the original CIF encryption key"
      );
    }
  }

  /** Keyed lookup avoids storing an identifier's plaintext or an unsalted hash. */
  public String lookup(String type, String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key.getEncoded(), "HmacSHA256"));
      return HexFormat.of().formatHex(
        mac.doFinal(
          (
            type +
            ":" +
            value.replaceAll("\\s+", "").trim().toUpperCase(Locale.ROOT)
          ).getBytes(StandardCharsets.UTF_8)
        )
      );
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static String hash(byte[] value) {
    try {
      return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value)
      );
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static String hint(String value) {
    return "••••" + value.substring(Math.max(0, value.length() - 4));
  }
}
