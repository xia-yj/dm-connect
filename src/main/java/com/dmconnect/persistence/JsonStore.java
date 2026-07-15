package com.dmconnect.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class JsonStore {
    private final ObjectMapper mapper;

    public JsonStore() {
        mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public <T> T read(Path path, Class<T> type) throws IOException {
        return mapper.readValue(path.toFile(), type);
    }

    public <T> T read(Path path, TypeReference<T> type) throws IOException {
        return mapper.readValue(path.toFile(), type);
    }

    public synchronized void writeAtomic(Path path, Object value) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(path.toAbsolutePath().getParent(), path.getFileName().toString(), ".tmp");
        try {
            mapper.writeValue(temporary.toFile(), value);
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public ObjectMapper mapper() {
        return mapper;
    }
}
