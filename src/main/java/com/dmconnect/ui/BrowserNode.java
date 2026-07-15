package com.dmconnect.ui;

import com.dmconnect.model.ConnectionProfile;
import com.dmconnect.model.DatabaseObject;
import com.dmconnect.model.DatabaseObjectKind;

record BrowserNode(
        Type type,
        String label,
        String profileId,
        String schema,
        DatabaseObjectKind objectKind,
        DatabaseObject object) {

    static BrowserNode root() {
        return new BrowserNode(Type.ROOT, "连接", null, null, null, null);
    }

    static BrowserNode profile(ConnectionProfile profile, boolean connected) {
        return new BrowserNode(Type.PROFILE, profile.name(),
                profile.id(), null, null, null);
    }

    static BrowserNode schema(String profileId, String schema) {
        return new BrowserNode(Type.SCHEMA, schema, profileId, schema, null, null);
    }

    static BrowserNode category(String profileId, String schema, DatabaseObjectKind kind) {
        return new BrowserNode(Type.CATEGORY, kind.displayName(), profileId, schema, kind, null);
    }

    static BrowserNode object(String profileId, DatabaseObject object) {
        return new BrowserNode(Type.OBJECT, object.name(), profileId, object.schema(), object.kind(), object);
    }

    static BrowserNode message(String label) {
        return new BrowserNode(Type.MESSAGE, label, null, null, null, null);
    }

    @Override
    public String toString() {
        return label;
    }

    enum Type {
        ROOT, PROFILE, SCHEMA, CATEGORY, OBJECT, MESSAGE
    }
}
