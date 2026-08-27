package com.dmconnect.security;

import java.io.IOException;
import java.util.Optional;

/** 本机加密存储使用的随机密钥保管处。 */
public interface LocalSecretStore {
    Optional<char[]> read() throws IOException, VaultException;

    void write(char[] secret) throws IOException, VaultException;

    void delete() throws IOException;
}
