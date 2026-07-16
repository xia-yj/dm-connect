package com.dmconnect.database.mysql;

import com.dmconnect.backend.RpcException;
import com.dmconnect.database.dm.DmTableDdlBuilder.Column;
import com.dmconnect.database.dm.DmTableDdlBuilder.Definition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MySqlTableDdlBuilderTest {
    private final MySqlTableDdlBuilder ddl = new MySqlTableDdlBuilder(new MySqlDatabaseAdapter());

    @Test
    void buildsQuotedCreateTableWithInlineCommentsDefaultsAndPrimaryKey() {
        Definition definition = new Definition("app", "orders", List.of(
                column(null, "id", "BIGINT", null, null, false, true, true, null, "主键"),
                column(null, "name", "VARCHAR", 100, null, false, false, false, "'new'", "订单名"),
                column(null, "amount", "DECIMAL", 18, 2, true, false, false, "0.00", null),
                column(null, "created_at", "TIMESTAMP", null, 6, false, false, false,
                        "CURRENT_TIMESTAMP(6)", null)
        ), null);

        assertThat(ddl.create(definition)).isEqualTo(
                "CREATE TABLE `app`.`orders` ("
                        + "`id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键', "
                        + "`name` VARCHAR(100) NOT NULL DEFAULT 'new' COMMENT '订单名', "
                        + "`amount` DECIMAL(18,2) DEFAULT 0.00, "
                        + "`created_at` TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), "
                        + "PRIMARY KEY (`id`))");
        assertThat(ddl.createComments(definition)).isEmpty();
    }

    @Test
    void preservesCurrentTimestampOnUpdateClauses() {
        Column updatedAt = new Column(null, "updated_at", "DATETIME", null, 6,
                false, false, false, "CURRENT_TIMESTAMP(6)", "CURRENT_TIMESTAMP(6)", null);

        assertThat(ddl.create(new Definition("app", "records", List.of(updatedAt), null)))
                .isEqualTo("CREATE TABLE `app`.`records` (`updated_at` DATETIME(6) NOT NULL "
                        + "DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6))");
    }

    @Test
    void combinesDropsChangesAndAddsIntoOneMysqlAlterTableStatement() {
        Definition original = new Definition("app", "items", List.of(
                column("id", "id", "BIGINT", null, null, false, true, true, null, null),
                column("title", "title", "VARCHAR", 20, null, true, false, false, null, null),
                column("obsolete", "obsolete", "INT", null, null, true, false, false, null, null)
        ), null);
        Definition target = new Definition("app", "items", List.of(
                column("id", "id", "BIGINT", null, null, false, true, true, null, null),
                column("title", "name", "VARCHAR", 80, null, false, false, false, "'x'", "显示名"),
                column(null, "payload", "JSON", null, null, true, false, false, null, null)
        ), null);

        assertThat(ddl.alter(original, target)).containsExactly(
                "ALTER TABLE `app`.`items` DROP COLUMN `obsolete`, "
                        + "CHANGE COLUMN `title` `name` VARCHAR(80) NOT NULL DEFAULT 'x' COMMENT '显示名', "
                        + "ADD COLUMN `payload` JSON");
    }

    @Test
    void replacesPrimaryKeyWithinTheSameAlterStatement() {
        Definition original = new Definition("app", "mapping", List.of(
                column("left_id", "left_id", "BIGINT", null, null, false, true, false, null, null),
                column("right_id", "right_id", "BIGINT", null, null, false, false, false, null, null)
        ), null);
        Definition target = new Definition("app", "mapping", List.of(
                column("left_id", "left_id", "BIGINT", null, null, false, true, false, null, null),
                column("right_id", "right_id", "BIGINT", null, null, false, true, false, null, null)
        ), null);

        assertThat(ddl.alter(original, target)).containsExactly(
                "ALTER TABLE `app`.`mapping` DROP PRIMARY KEY, "
                        + "CHANGE COLUMN `right_id` `right_id` BIGINT NOT NULL, "
                        + "ADD PRIMARY KEY (`left_id`, `right_id`)");
    }

    @Test
    void supportsRepresentativeMysqlTypeFamilies() {
        String sql = ddl.create(new Definition("app", "all_types", List.of(
                column(null, "code", "CHAR", 8, null, true, false, false, null, null),
                column(null, "medium_value", "MEDIUMINT", null, null, true, false, false, null, null),
                column(null, "flag", "BIT", 1, null, true, false, false, null, null),
                column(null, "binary_value", "VARBINARY", 255, null, true, false, false, null, null),
                column(null, "occurred_at", "DATETIME", null, 3, true, false, false, null, null),
                column(null, "body", "LONGTEXT", null, null, true, false, false, null, null),
                column(null, "content", "LONGBLOB", null, null, true, false, false, null, null),
                column(null, "attributes", "JSON", null, null, true, false, false, null, null),
                column(null, "location", "POINT", null, null, true, false, false, null, null)
        ), null));

        assertThat(sql).contains("`code` CHAR(8)", "`medium_value` MEDIUMINT", "`flag` BIT(1)",
                "`binary_value` VARBINARY(255)", "`occurred_at` DATETIME(3)", "`body` LONGTEXT",
                "`content` LONGBLOB", "`attributes` JSON", "`location` POINT");
    }

    @Test
    void safelyEscapesQuotesAndRejectsSqlModeDependentCommentEscapes() {
        String sql = ddl.create(new Definition("app", "comments", List.of(
                column(null, "value", "VARCHAR", 20, null, true, false, false,
                        null, "引号 '；仍是备注")
        ), null));

        assertThat(sql).contains("COMMENT '引号 ''；仍是备注'");
        assertThatThrownBy(() -> ddl.create(new Definition("app", "bad_comment", List.of(
                column(null, "value", "VARCHAR", 20, null, true, false, false,
                        null, "路径 C:\\tmp")
        ), null))).isInstanceOf(RpcException.class);
    }

    @Test
    void rejectsUnsafeOrStructurallyInvalidDefinitions() {
        assertThatThrownBy(() -> ddl.create(new Definition("app", "bad_default", List.of(
                column(null, "value", "VARCHAR", 20, null, true, false, false,
                        "0; DROP TABLE users", null)
        ), null))).isInstanceOf(RpcException.class);

        assertThatThrownBy(() -> ddl.create(new Definition("app", "bad_type", List.of(
                column(null, "value", "NOT_A_MYSQL_TYPE", null, null, true, false, false, null, null)
        ), null))).isInstanceOf(RpcException.class);

        assertThatThrownBy(() -> ddl.create(new Definition("app", "bad_auto", List.of(
                column(null, "code", "VARCHAR", 20, null, false, true, true, null, null)
        ), null))).isInstanceOf(RpcException.class);

        assertThatThrownBy(() -> ddl.create(new Definition("app", "two_auto", List.of(
                column(null, "id", "BIGINT", null, null, false, true, true, null, null),
                column(null, "sequence_no", "INT", null, null, false, true, true, null, null)
        ), null))).isInstanceOf(RpcException.class);
    }

    private static Column column(String original, String name, String type, Integer length, Integer scale,
                                 boolean nullable, boolean primaryKey, boolean autoIncrement,
                                 String defaultExpression, String remark) {
        return new Column(original, name, type, length, scale, nullable, primaryKey, autoIncrement,
                defaultExpression, null, remark);
    }
}
