package com.dmconnect.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class JsonRpcServer implements AutoCloseable {
    private static final int RPC_THREADS = Math.max(4, Runtime.getRuntime().availableProcessors());
    private static final int RPC_QUEUE_CAPACITY = 256;

    private final BackendService service;
    private final ObjectMapper mapper;
    private final ObjectWriter compactWriter;
    private final BufferedReader input;
    private final BufferedWriter output;
    private final ExecutorService requests;
    private final Object outputLock = new Object();

    public JsonRpcServer(BackendService service, ObjectMapper mapper, InputStream input, OutputStream output) {
        this.service = service;
        this.mapper = mapper;
        this.compactWriter = mapper.writer().without(SerializationFeature.INDENT_OUTPUT);
        this.input = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        this.output = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        AtomicInteger counter = new AtomicInteger();
        // Bounded pool: avoid unbounded thread growth when the UI fans out metadata/query RPCs.
        this.requests = new ThreadPoolExecutor(
                RPC_THREADS,
                RPC_THREADS,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(RPC_QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "dm-connect-rpc-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    public void run() throws Exception {
        String line;
        while ((line = input.readLine()) != null) {
            if (line.isBlank()) continue;
            String requestLine = line;
            requests.submit(() -> process(requestLine));
        }
    }

    private void process(String line) {
        String id = "";
        try {
            JsonNode request = mapper.readTree(line);
            id = request.path("id").asText("");
            String method = request.path("method").asText("");
            if (id.isBlank() || method.isBlank()) throw new RpcException("INVALID_REQUEST", "RPC 请求格式无效");
            Object result = service.handle(method, request.path("params"));
            write(Map.of("id", id, "result", result));
        } catch (Throwable throwable) {
            Throwable root = unwrap(throwable);
            Map<String, Object> error = new LinkedHashMap<>();
            if (root instanceof RpcException rpc) {
                error.put("code", rpc.code());
                error.put("message", BackendErrorFormatter.format(rpc));
                error.put("data", rpc.data());
            } else {
                error.put("code", "BACKEND_ERROR");
                error.put("message", BackendErrorFormatter.format(root));
                error.put("data", null);
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("id", id);
            response.put("error", error);
            write(response);
        }
    }

    private void write(Object value) {
        synchronized (outputLock) {
            try {
                // The Electron bridge uses newline-delimited JSON. Keep every response on
                // exactly one physical line even when the application's mapper is configured
                // for human-readable persistence files.
                output.write(compactWriter.writeValueAsString(value));
                output.newLine();
                output.flush();
            } catch (Exception exception) {
                exception.printStackTrace(System.err);
            }
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.lang.reflect.InvocationTargetException)
                && current.getCause() != null) current = current.getCause();
        return current;
    }

    @Override
    public void close() {
        requests.shutdownNow();
    }
}
