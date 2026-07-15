package com.dmconnect.nativeclient;

import com.dmconnect.model.ConnectionProfile;
import com.mongodb.AuthenticationMechanism;
import com.mongodb.MongoClientSettings;
import com.mongodb.ReadPreference;
import com.mongodb.connection.ClusterConnectionMode;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MongoWorkspaceTest {
    @Test
    void buildsCredentialAndAdvancedConnectionSettingsWithoutEmbeddingCredentialsInAUri() {
        ConnectionProfile profile = profile("app_user", "application", Map.ofEntries(
                Map.entry("authSource", "admin"),
                Map.entry("authMechanism", "SCRAM-SHA-256"),
                Map.entry("tls", "true"),
                Map.entry("tlsAllowInvalidHostnames", "true"),
                Map.entry("connectTimeoutMS", "1234"),
                Map.entry("socketTimeoutMS", "5678"),
                Map.entry("serverSelectionTimeoutMS", "9012"),
                Map.entry("directConnection", "true"),
                Map.entry("applicationName", "DM Connect"),
                Map.entry("readPreference", "secondaryPreferred"),
                Map.entry("retryReads", "false"),
                Map.entry("retryWrites", "false")));

        MongoClientSettings settings = MongoWorkspace.settingsFor(profile, "p@ss:/word".toCharArray());

        assertThat(settings.getCredential().getUserName()).isEqualTo("app_user");
        assertThat(settings.getCredential().getSource()).isEqualTo("admin");
        assertThat(settings.getCredential().getPassword()).containsExactly("p@ss:/word".toCharArray());
        assertThat(settings.getCredential().getAuthenticationMechanism()).isEqualTo(AuthenticationMechanism.SCRAM_SHA_256);
        assertThat(settings.getSslSettings().isEnabled()).isTrue();
        assertThat(settings.getSslSettings().isInvalidHostNameAllowed()).isTrue();
        assertThat(settings.getSocketSettings().getConnectTimeout(TimeUnit.MILLISECONDS)).isEqualTo(1234);
        assertThat(settings.getSocketSettings().getReadTimeout(TimeUnit.MILLISECONDS)).isEqualTo(5678);
        assertThat(settings.getClusterSettings().getServerSelectionTimeout(TimeUnit.MILLISECONDS)).isEqualTo(9012);
        assertThat(settings.getClusterSettings().getMode()).isEqualTo(ClusterConnectionMode.SINGLE);
        assertThat(settings.getApplicationName()).isEqualTo("DM Connect");
        assertThat(settings.getReadPreference()).isEqualTo(ReadPreference.secondaryPreferred());
        assertThat(settings.getRetryReads()).isFalse();
        assertThat(settings.getRetryWrites()).isFalse();
    }

    @Test
    void defaultsAuthenticationSourceToSelectedDatabaseThenAdmin() {
        MongoClientSettings databaseSettings = MongoWorkspace.settingsFor(
                profile("user", "orders", Map.of()), "secret".toCharArray());
        MongoClientSettings adminSettings = MongoWorkspace.settingsFor(
                profile("user", "", Map.of()), "secret".toCharArray());

        assertThat(databaseSettings.getCredential().getSource()).isEqualTo("orders");
        assertThat(adminSettings.getCredential().getSource()).isEqualTo("admin");
    }

    @Test
    void rejectsAmbiguousOrUnsafeAdvancedSettings() {
        assertThatThrownBy(() -> MongoWorkspace.settingsFor(
                profile("user", "db", Map.of("tls", "true", "ssl", "false")), "secret".toCharArray()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tls/ssl");
        assertThatThrownBy(() -> MongoWorkspace.settingsFor(
                profile("user", "db", Map.of("connectTimeoutMS", "forever")), "secret".toCharArray()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connectTimeoutMS");
        assertThatThrownBy(() -> MongoWorkspace.settingsFor(
                profile("", "db", Map.of()), "secret".toCharArray()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("用户名");
        assertThatThrownBy(() -> MongoWorkspace.settingsFor(
                profile("user", "db", Map.of("socketTimoutMS", "1000")), "secret".toCharArray()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("socketTimoutMS");

        MongoClientSettings defaults = MongoWorkspace.settingsFor(profile("", "db", Map.of()), new char[0]);
        assertThat(defaults.getSocketSettings().getReadTimeout(TimeUnit.MILLISECONDS)).isEqualTo(30_000);
    }

    @Test
    void rendersGeneratedAndApplicationSuppliedDocumentIdsAsCanonicalExtendedJsonValues() {
        ObjectId objectId = new ObjectId();
        assertThat(MongoWorkspace.displayId(new Document("_id", objectId)))
                .isEqualTo("{\"$oid\": \"" + objectId.toHexString() + "\"}");
        assertThat(MongoWorkspace.displayId(new Document("_id", "customer-42"))).isEqualTo("\"customer-42\"");
        assertThat(MongoWorkspace.displayId(new Document("_id", 42))).isEqualTo("{\"$numberInt\": \"42\"}");
    }

    @Test
    void returnedIdsRoundTripThroughTheUpdateAndDeleteParserWithoutChangingBsonType() {
        assertIdRoundTrips(new ObjectId());
        assertIdRoundTrips("507f1f77bcf86cd799439011");
        assertIdRoundTrips(42);
        assertIdRoundTrips(4_294_967_296L);
        assertIdRoundTrips(new Date(1_725_000_000_123L));
    }

    @Test
    void parsesTypedExtendedJsonIdsWithoutGuessingHexStrings() {
        Object parsed = MongoWorkspace.parseId("{\"$oid\":\"507f1f77bcf86cd799439011\"}");
        assertThat(parsed).isInstanceOf(ObjectId.class);
        assertThat(((ObjectId) parsed).toHexString()).isEqualTo("507f1f77bcf86cd799439011");
        assertThat(MongoWorkspace.parseId("507f1f77bcf86cd799439011"))
                .isEqualTo("507f1f77bcf86cd799439011");
        assertThat(MongoWorkspace.parseId("42")).isEqualTo(42);
        assertThat(MongoWorkspace.parseId("\"customer-42\"")).isEqualTo("customer-42");
        assertThatThrownBy(() -> MongoWorkspace.parseId("{not-json}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Extended JSON");
    }

    private static ConnectionProfile profile(String username, String database, Map<String, String> properties) {
        return new ConnectionProfile("mongo-test", "Mongo", "mongo", "[::1]", 27017, database, username,
                "builtin:mongo", properties, false);
    }

    private static void assertIdRoundTrips(Object id) {
        String displayed = MongoWorkspace.displayId(new Document("_id", id));
        assertThat(MongoWorkspace.parseId(displayed)).isEqualTo(id);
    }
}
