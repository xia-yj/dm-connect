package com.dmconnect.nativeclient;

import com.dmconnect.model.ConnectionProfile;
import io.lettuce.core.RedisURI;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisWorkspaceTest {
    @Test
    void buildsAuthenticatedTlsUriFromValidatedProperties() {
        ConnectionProfile profile = profile(Map.of(
                "database", "3",
                "tls", "true",
                "startTls", "true",
                "verifyPeer", "false",
                "commandTimeoutMS", "2500",
                "clientName", "DM Connect"));

        RedisURI uri = RedisWorkspace.uriFor(profile, "p@ss:/word".toCharArray());

        assertThat(uri.getUsername()).isEqualTo("redis_user");
        assertThat(uri.getPassword()).containsExactly("p@ss:/word".toCharArray());
        assertThat(uri.getDatabase()).isEqualTo(3);
        assertThat(uri.isSsl()).isTrue();
        assertThat(uri.isStartTls()).isTrue();
        assertThat(uri.isVerifyPeer()).isFalse();
        assertThat(uri.getTimeout()).isEqualTo(Duration.ofMillis(2500));
        assertThat(uri.getClientName()).isEqualTo("DM Connect");
    }

    @Test
    void acceptsSslAliasAndRejectsInvalidValues() {
        assertThat(RedisWorkspace.uriFor(profile(Map.of("ssl", "true")), new char[0]).isSsl()).isTrue();
        assertThatThrownBy(() -> RedisWorkspace.uriFor(profile(Map.of("startTls", "true")), new char[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startTls");
        assertThatThrownBy(() -> RedisWorkspace.uriFor(profile(Map.of("database", "-1")), new char[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("database");
        assertThatThrownBy(() -> RedisWorkspace.uriFor(profile(Map.of("verifyPeer", "yes")), new char[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("verifyPeer");
        assertThatThrownBy(() -> RedisWorkspace.uriFor(profile(Map.of("commandTimoutMS", "1000")), new char[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("commandTimoutMS");
    }

    private static ConnectionProfile profile(Map<String, String> properties) {
        return new ConnectionProfile("redis-test", "Redis", "redis", "localhost", 6379, "", "redis_user",
                "builtin:redis", properties, false);
    }
}
