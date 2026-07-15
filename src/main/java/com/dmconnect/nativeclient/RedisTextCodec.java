package com.dmconnect.nativeclient;

import io.lettuce.core.codec.RedisCodec;
import org.nustaq.serialization.FSTObjectInput;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Decodes legacy Redis text safely without replacing non-UTF-8 bytes with �. */
final class RedisTextCodec implements RedisCodec<String, String> {
    private static final java.nio.charset.Charset LEGACY = java.nio.charset.Charset.forName("GB18030");

    @Override public String decodeKey(ByteBuffer bytes) { return decode(bytes); }
    @Override public String decodeValue(ByteBuffer bytes) { return decode(bytes); }
    @Override public ByteBuffer encodeKey(String key) { return ByteBuffer.wrap(key.getBytes(StandardCharsets.UTF_8)); }
    @Override public ByteBuffer encodeValue(String value) { return ByteBuffer.wrap(value.getBytes(StandardCharsets.UTF_8)); }

    private static String decode(ByteBuffer source) {
        ByteBuffer bytes = source.slice();
        String fst = decodeFst(bytes);
        if (fst != null) return fst;
        String framed = decodeFramedText(bytes);
        if (framed != null) return framed;
        try {
            String utf8 = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(bytes).toString();
            if (!utf8.contains("\uFFFD")) return utf8;
        } catch (Exception ignored) {
            // Try the legacy Chinese encoding below.
        }
        try {
            String legacy = LEGACY.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(source.slice()).toString();
            if (!legacy.contains("\uFFFD")) return legacy;
        } catch (Exception ignored) {
            // Binary values are represented as escaped bytes below.
        }
        return escapedBytes(source);
    }

    private static String decodeFst(ByteBuffer bytes) {
        if (bytes.remaining() < 2 || (bytes.get(0) & 0xFF) != 0xFC) return null;
        byte[] raw = new byte[bytes.remaining()];
        bytes.get(raw);
        try (FSTObjectInput input = new FSTObjectInput(new ByteArrayInputStream(raw))) {
            Object value = input.readObject();
            if (value == null) return "(null)";
            if (value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Character) {
                return String.valueOf(value);
            }
            return "[FST object " + value.getClass().getName() + "] " + String.valueOf(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Handles the small FC-prefixed string envelope used by the existing Redis data. */
    private static String decodeFramedText(ByteBuffer bytes) {
        if (bytes.remaining() < 2 || (bytes.get(0) & 0xFF) != 0xFC) return null;
        int length = bytes.get(1) & 0xFF;
        if (length == 0xFF && bytes.remaining() == 2) return "(null)";
        if (length != bytes.remaining() - 2) return null;
        byte[] payload = new byte[length];
        bytes.position(2);
        bytes.get(payload);
        for (byte value : payload) {
            int unsigned = value & 0xFF;
            if (unsigned < 0x20 || unsigned > 0x7E) return null;
        }
        return new String(payload, StandardCharsets.UTF_8);
    }

    private static String escapedBytes(ByteBuffer source) {
        StringBuilder result = new StringBuilder("[binary ");
        ByteBuffer bytes = source.slice();
        while (bytes.hasRemaining()) result.append(String.format("%02X", bytes.get() & 0xFF));
        return result.append(']').toString();
    }
}
