package com.dmconnect.backend;

import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class BackendErrorFormatterTest {
    @Test
    void includesJdbcDetailsAndRootCause() {
        SQLException error = new SQLException("建立数据库连接失败", "08001", -6007,
                new ConnectException("Connection refused"));

        assertThat(BackendErrorFormatter.format(error))
                .contains("建立数据库连接失败")
                .contains("Connection refused")
                .contains("SQLState: 08001")
                .contains("数据库错误码: -6007");
    }

    @Test
    void redactsPasswordsFromDriverMessages() {
        SQLException error = new SQLException("password=secret-value pwd:another-secret password2=second clientCertificateKeyStorePassword=store-secret");
        assertThat(BackendErrorFormatter.format(error))
                .doesNotContain("secret-value", "another-secret", "second", "store-secret")
                .contains("password=***", "pwd:***", "password2=***", "clientCertificateKeyStorePassword=***");
    }
}
