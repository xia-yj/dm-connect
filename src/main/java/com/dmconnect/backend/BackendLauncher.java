package com.dmconnect.backend;

import com.dmconnect.AppContext;
import com.dmconnect.persistence.JsonStore;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class BackendLauncher {
    private BackendLauncher() {
    }

    public static void main(String[] args) {
        try (AppContext context = new AppContext()) {
            ObjectMapper mapper = new JsonStore().mapper();
            try (BackendService service = new BackendService(context, mapper);
                 JsonRpcServer server = new JsonRpcServer(service, mapper, System.in, System.out)) {
                server.run();
            }
        } catch (Exception exception) {
            exception.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
