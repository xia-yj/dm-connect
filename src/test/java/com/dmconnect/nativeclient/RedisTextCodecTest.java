package com.dmconnect.nativeclient;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RedisTextCodecTest {
    private final RedisTextCodec codec = new RedisTextCodec();

    @Test
    void decodesFstNullAndIntegerValuesUsedByLegacyRedisHashes() {
        assertThat(decode(0xFF)).isEqualTo("(null)");
        assertThat(decode(0xF7, 0x80, 0x10, 0x0E)).isEqualTo("3600");
    }

    @Test
    void decodesFstBooleanScalars() {
        assertThat(decode(0xF0)).isEqualTo("true");
        assertThat(decode(0xEF)).isEqualTo("false");
    }

    @Test
    void preservesPlainUtf8AndLegacyChineseText() {
        assertThat(codec.decodeValue(ByteBuffer.wrap("普通文本".getBytes(StandardCharsets.UTF_8))))
                .isEqualTo("普通文本");
        assertThat(codec.decodeValue(ByteBuffer.wrap("中文旧数据".getBytes(Charset.forName("GB18030")))))
                .isEqualTo("中文旧数据");
        assertThat(decode(0xF7, 0xA0)).isEqualTo("鳡");
    }

    @Test
    void keepsUnknownNonTextBytesVisibleAsHex() {
        assertThat(decode(0xFE)).isEqualTo("[binary FE]");
    }

    private String decode(int... values) {
        byte[] bytes = new byte[values.length];
        for (int index = 0; index < values.length; index++) bytes[index] = (byte) values[index];
        return codec.decodeValue(ByteBuffer.wrap(bytes));
    }
}
