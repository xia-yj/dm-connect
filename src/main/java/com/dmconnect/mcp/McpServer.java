package com.dmconnect.mcp;

import com.dmconnect.backend.BackendService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.NullNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP stdio 传输：每行一条 JSON-RPC 2.0 消息，stdout 只能写协议消息。
 */
public final class McpServer implements AutoCloseable {
    static final String LATEST_PROTOCOL = "2025-06-18";
    private static final List<String> PROTOCOLS = List.of("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25");
    private static final String INSTRUCTIONS = """
            读取“数据库连接工具”桌面端里已保存的数据库连接。先用 list_connections 查看连接，\
            connection 参数可以填连接名称或 ID。连接需要在桌面端勾选“记住密码”。\
            关系型数据库用 list_schemas → list_objects → describe_object 了解结构，再用 execute_sql 查询；\
            MongoDB 用 mongo_* 工具，Redis 用 redis_* 工具。""";

    private final McpTools tools;
    private final ObjectMapper mapper;
    private final ObjectWriter compactWriter;
    private final BufferedReader input;
    private final BufferedWriter output;
    private final ExecutorService requests;
    private final Object outputLock = new Object();

    public McpServer(McpTools tools, ObjectMapper mapper, InputStream input, OutputStream output) {
        this.tools = tools;
        this.mapper = mapper;
        this.compactWriter = mapper.writer().without(SerializationFeature.INDENT_OUTPUT);
        this.input = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        this.output = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        AtomicInteger counter = new AtomicInteger();
        // 客户端可能并发调用多个工具，慢查询不应挡住 ping 和其他请求。
        this.requests = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "dm-connect-mcp-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    public void run() throws Exception {
        String line;
        while ((line = input.readLine()) != null) {
            if (line.isBlank()) continue;
            String requestLine = line;
            requests.submit(() -> process(requestLine));
        }
    }

    void process(String line) {
        JsonNode request;
        try {
            request = mapper.readTree(line);
        } catch (Exception exception) {
            writeError(NullNode.getInstance(), -32700, "Parse error");
            return;
        }
        JsonNode id = request.get("id");
        String method = request.path("method").asText("");
        // 通知（notifications/initialized、notifications/cancelled）和客户端回给我们的响应都不需要回复。
        if (id == null || id.isNull() || method.isBlank()) return;
        try {
            Object result = switch (method) {
                case "initialize" -> initialize(request.path("params"));
                case "ping" -> Map.of();
                case "tools/list" -> Map.of("tools", tools.definitions());
                case "tools/call" -> tools.call(request.path("params"));
                default -> throw new McpError(-32601, "Method not found: " + method);
            };
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("jsonrpc", "2.0");
            response.put("id", id);
            response.put("result", result);
            write(response);
        } catch (McpError error) {
            writeError(id, error.code, error.getMessage());
        } catch (Throwable throwable) {
            throwable.printStackTrace(System.err);
            writeError(id, -32603, "Internal error: " + throwable.getMessage());
        }
    }

    private Map<String, Object> initialize(JsonNode params) {
        String requested = params.path("protocolVersion").asText("");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", PROTOCOLS.contains(requested) ? requested : LATEST_PROTOCOL);
        result.put("capabilities", Map.of("tools", Map.of()));
        result.put("serverInfo", Map.of("name", "dm-connect", "title", "数据库连接工具", "version", BackendService.VERSION));
        result.put("instructions", INSTRUCTIONS);
        return result;
    }

    private void writeError(JsonNode id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", code, "message", message));
        write(response);
    }

    private void write(Object value) {
        synchronized (outputLock) {
            try {
                output.write(compactWriter.writeValueAsString(value));
                output.newLine();
                output.flush();
            } catch (Exception exception) {
                exception.printStackTrace(System.err);
            }
        }
    }

    @Override
    public void close() {
        requests.shutdownNow();
    }

    static final class McpError extends RuntimeException {
        final int code;

        McpError(int code, String message) {
            super(message);
            this.code = code;
        }
    }
}
