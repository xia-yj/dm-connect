package com.dmconnect.persistence;

import com.dmconnect.model.ConnectionProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileRepositoryTest {
    @TempDir
    Path temporary;

    @Test
    void readsLegacyProfileJsonThatPredatesDatabaseField() throws Exception {
        AppPaths paths = new AppPaths(
                temporary,
                temporary.resolve("drivers"),
                temporary.resolve("profiles.json"),
                temporary.resolve("drivers.json"),
                temporary.resolve("vault.json"));
        Files.writeString(paths.profilesFile(), """
                [{
                  "id": "legacy-profile",
                  "name": "旧版达梦连接",
                  "host": "127.0.0.1",
                  "port": 5236,
                  "username": "SYSDBA",
                  "driverId": "legacy-driver",
                  "advancedProperties": {"socketTimeout": "30000"},
                  "rememberPassword": true
                }]
                """);

        ConnectionProfile profile = new ProfileRepository(paths, new JsonStore()).findAll().get(0);

        assertThat(profile.id()).isEqualTo("legacy-profile");
        assertThat(profile.databaseType()).isEqualTo("dm");
        assertThat(profile.database()).isEmpty();
        assertThat(profile.advancedProperties()).containsEntry("socketTimeout", "30000");
    }
}
