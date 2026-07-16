package com.dmconnect.nativeclient;

import io.lettuce.core.codec.RedisCodec;
import org.nustaq.serialization.FSTObjectInput;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Decodes legacy Redis text safely without replacing non-UTF-8 bytes with �. */
final class RedisTextCodec implements RedisCodec<String, String> {
    private static final java.nio.charset.Charset LEGACY = java.nio.charset.Charset.forName("GB18030");
    private static final int FST_NULL = 0xFF;
    private static final int FST_STRING = 0xFC;
    private static final int FST_BIG_INT = 0xF7;
    private static final int FST_BIG_LONG = 0xF6;
    private static final int FST_BOOLEAN_FALSE = 0xEF;
    private static final int FST_BOOLEAN_TRUE = 0xF0;

    @Override public String decodeKey(ByteBuffer bytes) { return decode(bytes); }
    @Override public String decodeValue(ByteBuffer bytes) { return decode(bytes); }
    @Override public ByteBuffer encodeKey(String key) { return ByteBuffer.wrap(key.getBytes(StandardCharsets.UTF_8)); }
    @Override public ByteBuffer encodeValue(String value) { return ByteBuffer.wrap(value.getBytes(StandardCharsets.UTF_8)); }

    private static String decode(ByteBuffer source) {
        int firstByte = source.hasRemaining() ? source.get(source.position()) & 0xFF : -1;
        if (firstByte == FST_NULL || firstByte == FST_STRING
                || firstByte == FST_BOOLEAN_FALSE || firstByte == FST_BOOLEAN_TRUE) {
            String fst = decodeFstScalar(source.slice());
            if (fst != null) return fst;
        }
        String framed = decodeFramedText(source.slice());
        if (framed != null) return framed;
        try {
            String utf8 = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(source.slice()).toString();
            if (isPlausibleText(utf8, source.remaining())) return utf8;
        } catch (Exception ignored) {
            // Try the legacy Chinese encoding below.
        }
        try {
            String legacy = LEGACY.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(source.slice()).toString();
            if (isPlausibleText(legacy, source.remaining())) return legacy;
        } catch (Exception ignored) {
            // Binary values are represented as escaped bytes below.
        }
        String fst = decodeFstScalar(source.slice());
        if (fst != null) return fst;
        return escapedBytes(source);
    }

    private static boolean isPlausibleText(String value, int sourceLength) {
        if (sourceLength > 0 && value.isEmpty()) return false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isISOControl(character) && character != '\n' && character != '\r' && character != '\t') {
                return false;
            }
        }
        return !value.contains("\uFFFD");
    }

    private static String decodeFstScalar(ByteBuffer bytes) {
        if (!bytes.hasRemaining() || !isFstScalarTag(bytes.get(0) & 0xFF)) return null;
        byte[] raw = new byte[bytes.remaining()];
        bytes.get(raw);
        if (!hasValidFstScalarLength(raw)) return null;
        int tag = raw[0] & 0xFF;
        if (tag == FST_NULL) return "(null)";
        if (tag == FST_BOOLEAN_FALSE) return "false";
        if (tag == FST_BOOLEAN_TRUE) return "true";
        if (tag == FST_BIG_INT) return String.valueOf(decodeFstInt(raw));
        if (tag == FST_BIG_LONG) return String.valueOf(decodeFstLong(raw));
        try (FSTObjectInput input = new FSTObjectInput(new ByteArrayInputStream(raw))) {
            Object value = input.readObject();
            if (value == null) return "(null)";
            if (value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Character) {
                return String.valueOf(value);
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isFstScalarTag(int tag) {
        return tag == FST_NULL || tag == FST_STRING || tag == FST_BIG_INT || tag == FST_BIG_LONG
                || tag == FST_BOOLEAN_FALSE || tag == FST_BOOLEAN_TRUE;
    }

    private static boolean hasValidFstScalarLength(byte[] raw) {
        int tag = raw[0] & 0xFF;
        if (tag == FST_NULL || tag == FST_BOOLEAN_FALSE || tag == FST_BOOLEAN_TRUE) return raw.length == 1;
        if (tag == FST_STRING) return true;
        if (raw.length < 2) return false;
        int numberHead = raw[1];
        int encodedNumberLength;
        if (tag == FST_BIG_INT) {
            encodedNumberLength = numberHead > -127 ? 1 : numberHead == -128 ? 3 : 5;
        } else {
            encodedNumberLength = numberHead > -126 ? 1 : numberHead == -128 ? 3 : numberHead == -127 ? 5 : 9;
        }
        return raw.length == encodedNumberLength + 1;
    }

    private static int decodeFstInt(byte[] raw) {
        int head = raw[1];
        if (head > -127) return head;
        ByteBuffer payload = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        return head == -128 ? payload.getShort(2) : payload.getInt(2);
    }

    private static long decodeFstLong(byte[] raw) {
        int head = raw[1];
        if (head > -126) return head;
        ByteBuffer payload = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        if (head == -128) return payload.getShort(2);
        if (head == -127) return payload.getInt(2);
        return payload.getLong(2);
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
