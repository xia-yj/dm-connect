package com.dmconnect.security;

import com.dmconnect.persistence.AppPaths;

import java.util.Locale;

public final class LocalSecretStores {
    private LocalSecretStores() {
    }

    public static LocalSecretStore forApp(AppPaths paths) {
        FileLocalSecretStore fileStore = new FileLocalSecretStore(paths);
        if (System.getProperty(AppPaths.DATA_DIR_PROPERTY) != null) return fileStore;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return new MigratingSecretStore(fileStore, new MacKeychainSecretStore());
        }
        if (os.contains("win")) {
            return new MigratingSecretStore(fileStore, new WindowsDpapiSecretStore(paths.root().resolve("vault.key.dpapi")));
        }
        return fileStore;
    }
}
