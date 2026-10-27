package com.dmconnect.mcp;

import com.dmconnect.AppContext;
import com.dmconnect.backend.BackendService;
import com.dmconnect.persistence.JsonStore;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.PrintStream;
import java.util.Arrays;

/**
 * MCP 服务入口，供 Claude Code 等客户端通过 stdio 启动。
 * 与桌面端共用连接配置和本地加密存储；传 --allow-write 或设置 DMCONNECT_MCP_ALLOW_WRITE=true 才允许写 SQL。
 */
public final class McpLauncher {
    private McpLauncher() {
    }

    public static void main(String[] args) {
        // stdout 只能输出 MCP 协议消息，驱动或第三方库打到 System.out 的内容改去 stderr。
        PrintStream protocolOutput = System.out;
        System.setOut(System.err);
        boolean allowWrite = Arrays.asList(args).contains("--allow-write")
                || "true".equalsIgnoreCase(System.getenv("DMCONNECT_MCP_ALLOW_WRITE"));
        try (AppContext context = new AppContext()) {
            ObjectMapper mapper = new JsonStore().mapper();
            try (BackendService service = new BackendService(context, mapper, false);
                 McpServer server = new McpServer(new McpTools(context, service, mapper, allowWrite), mapper,
                         System.in, protocolOutput)) {
                server.run();
            }
        } catch (Exception exception) {
            exception.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
