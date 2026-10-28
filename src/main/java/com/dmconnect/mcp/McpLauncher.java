package com.dmconnect.mcp;

import com.dmconnect.AppContext;
import com.dmconnect.backend.BackendService;
import com.dmconnect.persistence.JsonStore;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;

/**
 * MCP 服务入口，供 Claude Code 等客户端通过 stdio 启动。
 * 与桌面端共用连接配置和本地加密存储。默认只读；--allow-write（DMCONNECT_MCP_ALLOW_WRITE=true）允许写入，
 * --full-access（DMCONNECT_MCP_FULL_ACCESS=true）连危险 SQL 和 Redis 管理命令也放行。
 */
public final class McpLauncher {
    private McpLauncher() {
    }

    public static void main(String[] args) {
        // stdout 只能输出 MCP 协议消息，驱动或第三方库打到 System.out 的内容改去 stderr。
        PrintStream protocolOutput = System.out;
        System.setOut(System.err);
        List<String> flags = Arrays.asList(args);
        McpTools.Access access = flags.contains("--full-access")
                || "true".equalsIgnoreCase(System.getenv("DMCONNECT_MCP_FULL_ACCESS"))
                ? McpTools.Access.FULL
                : flags.contains("--allow-write") || "true".equalsIgnoreCase(System.getenv("DMCONNECT_MCP_ALLOW_WRITE"))
                ? McpTools.Access.WRITE
                : McpTools.Access.READ_ONLY;
        try (AppContext context = new AppContext()) {
            ObjectMapper mapper = new JsonStore().mapper();
            try (BackendService service = new BackendService(context, mapper, false);
                 McpServer server = new McpServer(new McpTools(context, service, mapper, access), mapper,
                         System.in, protocolOutput)) {
                server.run();
            }
        } catch (Exception exception) {
            exception.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
