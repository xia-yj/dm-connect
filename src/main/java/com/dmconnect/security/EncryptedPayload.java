package com.dmconnect.security;

public record EncryptedPayload(String nonce, String ciphertext) {
}
