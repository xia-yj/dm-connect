package com.dmconnect.nativeclient;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisCommandExecutorTest {
    @Test
    void parsesQuotedAndEscapedArgumentsWithoutUsingAShell() {
        RedisCommandExecutor.ParsedCommand command = RedisCommandExecutor.parse(
                "SET \"user key\" 'hello world'\\nnext");

        assertThat(command.operation()).isEqualTo("SET");
        assertThat(command.arguments()).containsExactly("user key", "hello world\nnext");
        assertThat(command.risk()).isEqualTo(RedisCommandExecutor.Risk.WRITE);
        assertThat(command.dangerous()).isTrue();
    }

    @Test
    void classifiesReadsWritesUnknownModulesAndSessionBreakingCommands() {
        assertThat(RedisCommandExecutor.parse("TTL key").risk()).isEqualTo(RedisCommandExecutor.Risk.READ_ONLY);
        assertThat(RedisCommandExecutor.parse("GET key").risk()).isEqualTo(RedisCommandExecutor.Risk.LARGE_RESULT);
        assertThat(RedisCommandExecutor.parse("GET key").blocked()).isTrue();
        assertThat(RedisCommandExecutor.parse("FLUSHDB").risk()).isEqualTo(RedisCommandExecutor.Risk.ADMIN);
        assertThat(RedisCommandExecutor.parse("BLPOP queue 0").risk()).isEqualTo(RedisCommandExecutor.Risk.BLOCKING);
        assertThat(RedisCommandExecutor.parse("BLPOP queue 0").blocked()).isTrue();
        RedisCommandExecutor.ParsedCommand unknownModule = RedisCommandExecutor.parse("JSON.GET doc $");
        assertThat(unknownModule.risk()).isEqualTo(RedisCommandExecutor.Risk.UNKNOWN);
        assertThat(unknownModule.blocked()).isTrue();
        assertThat(RedisCommandExecutor.parse("SORT users").risk()).isEqualTo(RedisCommandExecutor.Risk.LARGE_RESULT);
        assertThat(RedisCommandExecutor.parse("SORT users").blocked()).isTrue();
        assertThat(RedisCommandExecutor.parse("PUBSUB CHANNELS").risk()).isEqualTo(RedisCommandExecutor.Risk.LARGE_RESULT);
        assertThat(RedisCommandExecutor.parse("PUBSUB CHANNELS").blocked()).isTrue();
        assertThat(RedisCommandExecutor.parse("AUTH secret").risk()).isEqualTo(RedisCommandExecutor.Risk.BLOCKED);
        assertThat(RedisCommandExecutor.parse("AUTH secret").blocked()).isTrue();
        assertThat(RedisCommandExecutor.parse("CLIENT REPLY OFF").blocked()).isTrue();
    }

    @Test
    void rejectsMalformedCommands() {
        assertThatThrownBy(() -> RedisCommandExecutor.parse("SET key 'unterminated"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("引号");
        assertThatThrownBy(() -> RedisCommandExecutor.parse("SET key value\\"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("转义");
        assertThatThrownBy(() -> RedisCommandExecutor.parse("??? key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("命令名");
    }

    @Test
    void refusesUnknownCommandsBeforeDispatchingThem() {
        RedisCommandExecutor.ParsedCommand command = RedisCommandExecutor.parse("MODULE.CUSTOM key");

        assertThatThrownBy(() -> RedisCommandExecutor.execute(null, command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("安全允许列表");
    }
}
