package com.dmconnect.persistence;

import com.dmconnect.model.ConnectionProfile;
import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class ProfileRepository {
    private static final TypeReference<List<ConnectionProfile>> PROFILE_LIST = new TypeReference<>() {
    };
    private final AppPaths paths;
    private final JsonStore store;

    public ProfileRepository(AppPaths paths, JsonStore store) {
        this.paths = paths;
        this.store = store;
    }

    public synchronized List<ConnectionProfile> findAll() throws IOException {
        if (!Files.exists(paths.profilesFile())) return List.of();
        List<ConnectionProfile> profiles = new ArrayList<>(store.read(paths.profilesFile(), PROFILE_LIST));
        profiles.sort(Comparator.comparing(ConnectionProfile::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(profiles);
    }

    public synchronized Optional<ConnectionProfile> findById(String id) throws IOException {
        return findAll().stream().filter(profile -> profile.id().equals(id)).findFirst();
    }

    public synchronized void save(ConnectionProfile profile) throws IOException {
        List<ConnectionProfile> profiles = new ArrayList<>(findAll());
        profiles.removeIf(existing -> existing.id().equals(profile.id()));
        profiles.add(profile);
        profiles.sort(Comparator.comparing(ConnectionProfile::name, String.CASE_INSENSITIVE_ORDER));
        store.writeAtomic(paths.profilesFile(), profiles);
    }

    public synchronized void delete(String id) throws IOException {
        List<ConnectionProfile> profiles = new ArrayList<>(findAll());
        if (profiles.removeIf(profile -> profile.id().equals(id))) {
            store.writeAtomic(paths.profilesFile(), profiles);
        }
    }
}
