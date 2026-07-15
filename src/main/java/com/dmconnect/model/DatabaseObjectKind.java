package com.dmconnect.model;

public enum DatabaseObjectKind {
    TABLE("表", "TABLE"),
    VIEW("视图", "VIEW"),
    SEQUENCE("序列", "SEQUENCE"),
    PROCEDURE("存储过程", "PROCEDURE"),
    FUNCTION("函数", "FUNCTION"),
    TRIGGER("触发器", "TRIGGER");

    private final String displayName;
    private final String ddlType;

    DatabaseObjectKind(String displayName, String ddlType) {
        this.displayName = displayName;
        this.ddlType = ddlType;
    }

    public String displayName() {
        return displayName;
    }

    public String ddlType() {
        return ddlType;
    }
}
