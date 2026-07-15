package com.dmconnect.nativeclient;

import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.output.ObjectOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.ProtocolKeyword;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Parses and executes one Redis protocol command without invoking a shell. */
public final class RedisCommandExecutor {
    private static final int MAX_COMMAND_LENGTH = 65_536;
    private static final int MAX_ARGUMENTS = 1_024;
    private static final Pattern OPERATION = Pattern.compile("[A-Z][A-Z0-9_.-]{0,63}");

    private static final Set<String> READ_ONLY = Set.of(
            "PING", "ECHO", "COMMAND", "DBSIZE", "INFO", "LASTSAVE", "ROLE", "TIME",
            "GET", "MGET", "STRLEN", "GETRANGE", "GETBIT", "BITCOUNT", "BITPOS",
            "EXISTS", "TYPE", "TTL", "PTTL", "EXPIRETIME", "PEXPIRETIME", "DUMP", "RANDOMKEY",
            "HGET", "HGETALL", "HEXISTS", "HKEYS", "HLEN", "HMGET", "HSTRLEN", "HVALS", "HRANDFIELD",
            "LINDEX", "LLEN", "LPOS", "LRANGE",
            "SCARD", "SDIFF", "SINTER", "SISMEMBER", "SMEMBERS", "SMISMEMBER", "SRANDMEMBER", "SUNION",
            "ZCARD", "ZCOUNT", "ZDIFF", "ZINTER", "ZLEXCOUNT", "ZMSCORE", "ZRANDMEMBER", "ZRANGE",
            "ZRANK", "ZREVRANGE", "ZREVRANK", "ZSCORE", "ZUNION",
            "GEODIST", "GEOHASH", "GEOPOS", "GEOSEARCH", "PFCOUNT",
            "XLEN", "XPENDING", "XRANGE", "XREVRANGE", "XINFO",
            "SCAN", "HSCAN", "SSCAN", "ZSCAN");

    private static final Set<String> LARGE_RESULT = Set.of(
            "COMMAND", "GET", "MGET", "GETRANGE", "DUMP",
            "HGET", "HGETALL", "HKEYS", "HMGET", "HVALS", "HRANDFIELD",
            "LINDEX", "LPOS", "LRANGE",
            "SDIFF", "SINTER", "SMEMBERS", "SRANDMEMBER", "SUNION",
            "ZDIFF", "ZINTER", "ZRANDMEMBER", "ZRANGE", "ZREVRANGE", "ZUNION",
            "GEOHASH", "GEOPOS", "GEOSEARCH",
            "XRANGE", "XREVRANGE", "XPENDING", "XINFO",
            "SCAN", "HSCAN", "SSCAN", "ZSCAN",
            "SORT", "PUBSUB");

    private static final Set<String> WRITE = Set.of(
            "APPEND", "DECR", "DECRBY", "GETDEL", "GETEX", "GETSET", "INCR", "INCRBY", "INCRBYFLOAT",
            "MSET", "MSETNX", "PSETEX", "SET", "SETBIT", "SETEX", "SETNX", "SETRANGE",
            "COPY", "DEL", "EXPIRE", "EXPIREAT", "MOVE", "PERSIST", "PEXPIRE", "PEXPIREAT", "RENAME",
            "RENAMENX", "RESTORE", "TOUCH", "UNLINK",
            "HDEL", "HINCRBY", "HINCRBYFLOAT", "HMSET", "HSET", "HSETNX",
            "LINSERT", "LMOVE", "LPOP", "LPUSH", "LPUSHX", "LREM", "LSET", "LTRIM", "RPOP", "RPOPLPUSH",
            "RPUSH", "RPUSHX",
            "SADD", "SDIFFSTORE", "SINTERSTORE", "SMOVE", "SPOP", "SREM", "SUNIONSTORE",
            "ZADD", "ZDIFFSTORE", "ZINCRBY", "ZINTERSTORE", "ZPOPMAX", "ZPOPMIN", "ZREM",
            "ZREMRANGEBYLEX", "ZREMRANGEBYRANK", "ZREMRANGEBYSCORE", "ZRANGESTORE", "ZUNIONSTORE",
            "GEOADD", "GEOSEARCHSTORE", "PFADD", "PFMERGE", "PUBLISH", "SPUBLISH",
            "XACK", "XADD", "XAUTOCLAIM", "XCLAIM", "XDEL", "XGROUP", "XSETID", "XTRIM");

    private static final Set<String> ADMIN = Set.of(
            "ACL", "BGREWRITEAOF", "BGSAVE", "CLUSTER", "CONFIG", "EVAL", "EVALSHA", "FAILOVER",
            "FCALL", "FCALL_RO", "FLUSHALL", "FLUSHDB", "FUNCTION", "LATENCY", "MEMORY", "SAVE", "SCRIPT",
            "SLOWLOG", "SWAPDB");

    private static final Set<String> BLOCKING = Set.of(
            "KEYS", "BLMOVE", "BLMPOP", "BLPOP", "BRPOP", "BRPOPLPUSH", "BZMPOP", "BZPOPMAX", "BZPOPMIN",
            "WAIT", "WAITAOF", "XREAD", "XREADGROUP");

    private static final Set<String> DENIED = Set.of(
            "ASKING", "AUTH", "CLIENT", "DEBUG", "DISCARD", "EXEC", "HELLO", "MIGRATE", "MODULE", "MONITOR", "MULTI",
            "PSUBSCRIBE", "PSYNC", "PUNSUBSCRIBE", "QUIT", "READONLY", "READWRITE", "REPLICAOF", "RESET",
            "SELECT", "SHUTDOWN", "SLAVEOF", "SSUBSCRIBE", "SUBSCRIBE", "SUNSUBSCRIBE", "SYNC", "UNSUBSCRIBE",
            "UNWATCH", "WATCH");

    private RedisCommandExecutor() {}

    public enum Risk {
        READ_ONLY(false), WRITE(true), ADMIN(true), BLOCKING(true), LARGE_RESULT(true), UNKNOWN(true), BLOCKED(true);

        private final boolean dangerous;
        Risk(boolean dangerous) { this.dangerous = dangerous; }
        public boolean dangerous() { return dangerous; }
        public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    public record ParsedCommand(String operation, List<String> arguments, Risk risk) {
        public ParsedCommand {
            arguments = List.copyOf(arguments);
        }
        public boolean dangerous() { return risk.dangerous(); }
        public boolean blocked() {
            return risk == Risk.BLOCKED
                    || risk == Risk.BLOCKING
                    || risk == Risk.LARGE_RESULT
                    || risk == Risk.UNKNOWN;
        }
    }

    public static ParsedCommand parse(String command) {
        if (command == null || command.isBlank()) throw new IllegalArgumentException("请输入 Redis 命令");
        if (command.length() > MAX_COMMAND_LENGTH) throw new IllegalArgumentException("Redis 命令超过 65536 个字符");
        List<String> tokens = tokenize(command);
        if (tokens.isEmpty()) throw new IllegalArgumentException("请输入 Redis 命令");
        if (tokens.size() - 1 > MAX_ARGUMENTS) throw new IllegalArgumentException("Redis 命令参数过多");
        String operation = tokens.get(0).toUpperCase(Locale.ROOT);
        if (!OPERATION.matcher(operation).matches()) throw new IllegalArgumentException("Redis 命令名无效");
        return new ParsedCommand(operation, tokens.subList(1, tokens.size()), classify(operation));
    }

    public static Object execute(StatefulRedisConnection<String, String> connection, ParsedCommand command) {
        if (command.blocked()) {
            throw new IllegalArgumentException("该命令不在安全允许列表中，或可能破坏会话、无限阻塞或返回无界结果，命令台不允许执行");
        }
        CommandArgs<String, String> arguments = new CommandArgs<>(StringCodec.UTF8);
        command.arguments().forEach(arguments::add);
        return connection.sync().dispatch(new LiteralKeyword(command.operation()),
                new ObjectOutput<>(StringCodec.UTF8), arguments);
    }

    private static Risk classify(String operation) {
        if (DENIED.contains(operation)) return Risk.BLOCKED;
        if (LARGE_RESULT.contains(operation)) return Risk.LARGE_RESULT;
        if (READ_ONLY.contains(operation)) return Risk.READ_ONLY;
        if (WRITE.contains(operation)) return Risk.WRITE;
        if (ADMIN.contains(operation)) return Risk.ADMIN;
        if (BLOCKING.contains(operation)) return Risk.BLOCKING;
        return Risk.UNKNOWN;
    }

    private static List<String> tokenize(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean escaping = false;
        boolean tokenStarted = false;
        for (int index = 0; index < input.length(); index++) {
            char character = input.charAt(index);
            if (escaping) {
                current.append(unescape(character));
                escaping = false;
                tokenStarted = true;
                continue;
            }
            if (character == '\\') {
                escaping = true;
                tokenStarted = true;
                continue;
            }
            if (quote != 0) {
                if (character == quote) quote = 0;
                else current.append(character);
                tokenStarted = true;
                continue;
            }
            if (character == '\'' || character == '"') {
                quote = character;
                tokenStarted = true;
            } else if (Character.isWhitespace(character)) {
                if (tokenStarted) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    tokenStarted = false;
                }
            } else {
                if (character == 0) throw new IllegalArgumentException("Redis 命令不能包含 NUL 字符");
                current.append(character);
                tokenStarted = true;
            }
        }
        if (escaping) throw new IllegalArgumentException("Redis 命令末尾的转义字符不完整");
        if (quote != 0) throw new IllegalArgumentException("Redis 命令的引号未闭合");
        if (tokenStarted) tokens.add(current.toString());
        return tokens;
    }

    private static char unescape(char value) {
        return switch (value) {
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            default -> value;
        };
    }

    private static final class LiteralKeyword implements ProtocolKeyword {
        private final String name;
        private final byte[] encoded;

        private LiteralKeyword(String name) {
            this.name = name;
            this.encoded = name.getBytes(StandardCharsets.US_ASCII);
        }

        @Override public byte[] getBytes() { return encoded; }
        @Override public String toString() { return name; }
    }
}
