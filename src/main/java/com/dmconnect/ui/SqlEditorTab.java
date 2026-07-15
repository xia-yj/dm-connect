package com.dmconnect.ui;

import com.dmconnect.AppContext;
import com.dmconnect.model.ExecutionMode;
import com.dmconnect.model.ExecutionResult;
import com.dmconnect.model.StatementOutcome;
import com.dmconnect.query.CsvExporter;
import com.dmconnect.query.DangerousSqlDetector;
import com.dmconnect.query.QuerySession;
import com.dmconnect.query.SqlScriptParser;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.reactfx.Subscription;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

final class SqlEditorTab extends Tab {
    private final AppContext context;
    private final ConnectedProfile workspace;
    private final QuerySession session;
    private final CodeArea editor = new CodeArea();
    private final TabPane results = new TabPane();
    private final Label status = new Label("就绪");
    private final Spinner<Integer> maxRows = new Spinner<>(100, 10_000, 1000, 100);
    private final CheckBox autoCommit = new CheckBox("自动提交");
    private final Button selectionButton = new Button("执行选中");
    private final Button currentButton = new Button("执行当前");
    private final Button scriptButton = new Button("执行全部");
    private final Button cancelButton = new Button("取消");
    private final Button commitButton = new Button("提交");
    private final Button rollbackButton = new Button("回滚");
    private final Button exportButton = new Button("导出 CSV");
    private final SqlScriptParser parser = new SqlScriptParser();
    private final DangerousSqlDetector dangerousSqlDetector = new DangerousSqlDetector();
    private final Subscription highlightingSubscription;
    private final AtomicBoolean resourcesClosed = new AtomicBoolean();
    private boolean changingAutoCommit;
    private boolean forceClosing;

    SqlEditorTab(AppContext context, ConnectedProfile workspace, QuerySession session, String initialSql) {
        this.context = context;
        this.workspace = workspace;
        this.session = session;
        setText("SQL · " + workspace.profile().name());
        setClosable(true);

        editor.setParagraphGraphicFactory(LineNumberFactory.get(editor));
        editor.getStyleClass().add("code-area");
        editor.replaceText(initialSql == null ? "" : initialSql);
        highlightingSubscription = editor.multiPlainChanges()
                .successionEnds(Duration.ofMillis(180))
                .subscribe(change -> editor.setStyleSpans(0, SyntaxHighlighter.compute(editor.getText())));
        if (!editor.getText().isEmpty()) editor.setStyleSpans(0, SyntaxHighlighter.compute(editor.getText()));

        BorderPane content = new BorderPane();
        content.getStyleClass().add("sql-workspace");
        content.setTop(buildToolbar());
        SplitPane split = new SplitPane(editor, results);
        split.getStyleClass().add("editor-split");
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.58);
        results.getStyleClass().add("result-tabs");
        Label emptyResult = new Label("执行 SQL 后，结果集、更新行数和错误信息将在这里显示");
        emptyResult.getStyleClass().add("muted-label");
        BorderPane emptyPane = new BorderPane(emptyResult);
        emptyPane.setPadding(new Insets(24));
        results.getTabs().add(new Tab("执行结果", emptyPane));
        results.getTabs().get(0).setClosable(false);
        content.setCenter(split);
        status.getStyleClass().add("status-message");
        HBox statusBar = new HBox(status);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.getStyleClass().add("status-bar");
        content.setBottom(statusBar);
        setContent(content);

        configureActions();
        setOnCloseRequest(event -> handleCloseRequest(event));
        setOnClosed(event -> closeResources());
    }

    private Node buildToolbar() {
        autoCommit.setSelected(true);
        maxRows.setEditable(true);
        maxRows.setPrefWidth(92);
        cancelButton.setDisable(true);
        commitButton.setDisable(true);
        rollbackButton.setDisable(true);
        exportButton.setDisable(true);
        currentButton.getStyleClass().add("run-button");
        cancelButton.getStyleClass().add("danger-button");
        exportButton.getStyleClass().add("secondary-button");

        HBox execution = new HBox(selectionButton, currentButton, scriptButton, cancelButton);
        execution.getStyleClass().add("toolbar-group");
        HBox transaction = new HBox(autoCommit, commitButton, rollbackButton);
        transaction.getStyleClass().add("toolbar-group");
        Label limitLabel = new Label("结果上限");
        limitLabel.getStyleClass().add("toolbar-label");
        HBox resultOptions = new HBox(limitLabel, maxRows, exportButton);
        resultOptions.getStyleClass().add("toolbar-group");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(execution, spacer, transaction, resultOptions);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().add("sql-toolbar");
        return toolbar;
    }

    private void configureActions() {
        selectionButton.setOnAction(event -> execute(ExecutionMode.SELECTION));
        currentButton.setOnAction(event -> execute(ExecutionMode.CURRENT_STATEMENT));
        scriptButton.setOnAction(event -> execute(ExecutionMode.SCRIPT));
        cancelButton.setOnAction(event -> cancelExecution());
        commitButton.setOnAction(event -> transaction(true));
        rollbackButton.setOnAction(event -> transaction(false));
        exportButton.setOnAction(event -> exportCurrentResult());
        autoCommit.setOnAction(event -> changeAutoCommit());
    }

    private void execute(ExecutionMode mode) {
        List<String> statements = statementsFor(mode);
        if (statements.isEmpty()) {
            status.setText(mode == ExecutionMode.SELECTION ? "请先选择要执行的 SQL" : "没有可执行的 SQL");
            return;
        }
        List<String> dangerous = dangerousSqlDetector.findDangerous(statements);
        if (!dangerous.isEmpty() && !confirmDangerous(dangerous)) return;

        setBusy(true);
        status.setText("正在执行 " + statements.size() + " 条语句…");
        int limit = maxRows.getValue();
        String historySql = String.join(";\n\n", statements);
        CompletableFuture.supplyAsync(() -> {
            ExecutionResult execution = session.execute(statements, limit);
            String historyWarning = "";
            try {
                context.vault().addHistory(workspace.profile().id(), workspace.profile().name(),
                        execution.success(), execution.durationMillis(), historySql);
            } catch (Exception exception) {
                historyWarning = Dialogs.rootMessage(exception);
            }
            return new RunResult(execution, historyWarning);
        }, context.executor()).whenComplete((run, throwable) -> Platform.runLater(() -> {
            setBusy(false);
            if (throwable != null) {
                status.setText("执行失败：" + Dialogs.rootMessage(throwable));
                return;
            }
            showResults(run.execution());
            String summary = run.execution().success()
                    ? "执行完成：" + run.execution().executedStatements() + " 条，耗时 "
                    + run.execution().durationMillis() + " ms"
                    : "执行失败：" + run.execution().errorMessage();
            if (!run.historyWarning().isBlank()) summary += "；历史未保存：" + run.historyWarning();
            status.setText(summary);
        }));
    }

    private List<String> statementsFor(ExecutionMode mode) {
        return switch (mode) {
            case SELECTION -> parser.split(editor.getSelectedText());
            case CURRENT_STATEMENT -> parser.currentStatement(editor.getText(), editor.getCaretPosition())
                    .map(statement -> List.of(statement.sql())).orElseGet(List::of);
            case SCRIPT -> parser.split(editor.getText());
        };
    }

    private boolean confirmDangerous(List<String> statements) {
        StringBuilder summary = new StringBuilder("检测到可能破坏数据库对象的语句：\n\n");
        statements.stream().limit(5).forEach(sql -> {
            String oneLine = sql.replaceAll("\\s+", " ").strip();
            summary.append("• ").append(oneLine, 0, Math.min(oneLine.length(), 180));
            if (oneLine.length() > 180) summary.append("…");
            summary.append('\n');
        });
        if (statements.size() > 5) summary.append("以及另外 ").append(statements.size() - 5).append(" 条。\n");
        summary.append("\n确认继续执行吗？");
        return Dialogs.confirm(owner(), "确认危险 SQL", summary.toString());
    }

    private void showResults(ExecutionResult execution) {
        results.getTabs().clear();
        int tableNumber = 0;
        for (StatementOutcome outcome : execution.outcomes()) {
            Tab tab;
            if (outcome.hasTable()) {
                tableNumber++;
                ResultTablePane pane = new ResultTablePane(outcome.table());
                tab = new Tab("结果 " + tableNumber, pane);
            } else {
                Label label = new Label("语句 " + outcome.statementIndex() + " 影响 " + outcome.updateCount() + " 行");
                VBox box = new VBox(label);
                box.setPadding(new Insets(20));
                tab = new Tab("更新 " + outcome.statementIndex(), box);
            }
            tab.setClosable(false);
            results.getTabs().add(tab);
        }
        if (!execution.success()) {
            TextArea error = new TextArea("错误：" + execution.errorMessage()
                    + "\nSQLState：" + execution.sqlState()
                    + "\n错误码：" + execution.vendorCode());
            error.setEditable(false);
            Tab errorTab = new Tab("错误", error);
            errorTab.setClosable(false);
            results.getTabs().add(errorTab);
        }
        if (results.getTabs().isEmpty()) {
            Tab empty = new Tab("完成", new Label("语句执行完成，数据库未返回结果集或更新计数。"));
            empty.setClosable(false);
            results.getTabs().add(empty);
        }
        results.getSelectionModel().selectFirst();
        exportButton.setDisable(results.getTabs().stream().noneMatch(tab -> tab.getContent() instanceof ResultTablePane));
    }

    private void cancelExecution() {
        cancelButton.setDisable(true);
        status.setText("正在取消…");
        CompletableFuture.runAsync(() -> {
            try {
                session.cancel();
            } catch (SQLException exception) {
                throw new RuntimeException(exception);
            }
        }, context.executor()).whenComplete((ignored, throwable) -> Platform.runLater(() -> {
            if (throwable != null) status.setText("取消失败：" + Dialogs.rootMessage(throwable));
        }));
    }

    private void changeAutoCommit() {
        if (changingAutoCommit) return;
        boolean requested = autoCommit.isSelected();
        if (requested && session.hasPendingTransaction()
                && !Dialogs.confirm(owner(), "启用自动提交", "启用自动提交会提交当前事务，确认继续吗？")) {
            changingAutoCommit = true;
            autoCommit.setSelected(false);
            changingAutoCommit = false;
            return;
        }
        autoCommit.setDisable(true);
        CompletableFuture.runAsync(() -> {
            try {
                session.setAutoCommit(requested);
            } catch (SQLException exception) {
                throw new RuntimeException(exception);
            }
        }, context.executor()).whenComplete((ignored, throwable) -> Platform.runLater(() -> {
            autoCommit.setDisable(false);
            if (throwable != null) {
                changingAutoCommit = true;
                autoCommit.setSelected(!requested);
                changingAutoCommit = false;
                status.setText("切换自动提交失败：" + Dialogs.rootMessage(throwable));
            }
            updateTransactionButtons();
        }));
    }

    private void transaction(boolean commit) {
        commitButton.setDisable(true);
        rollbackButton.setDisable(true);
        CompletableFuture.runAsync(() -> {
            try {
                if (commit) session.commit();
                else session.rollback();
            } catch (SQLException exception) {
                throw new RuntimeException(exception);
            }
        }, context.executor()).whenComplete((ignored, throwable) -> Platform.runLater(() -> {
            updateTransactionButtons();
            status.setText(throwable == null ? (commit ? "事务已提交" : "事务已回滚")
                    : "事务操作失败：" + Dialogs.rootMessage(throwable));
        }));
    }

    private void updateTransactionButtons() {
        boolean manual = !autoCommit.isSelected();
        commitButton.setDisable(!manual);
        rollbackButton.setDisable(!manual);
    }

    private void exportCurrentResult() {
        Tab selected = results.getSelectionModel().getSelectedItem();
        if (selected == null || !(selected.getContent() instanceof ResultTablePane pane)) {
            status.setText("请先选择一个查询结果标签");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导出查询结果");
        chooser.setInitialFileName("query-result.csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV 文件", "*.csv"));
        var file = chooser.showSaveDialog(owner());
        if (file == null) return;
        status.setText("正在导出 CSV…");
        CompletableFuture.runAsync(() -> {
            try {
                CsvExporter.write(pane.resultTable(), Path.of(file.toURI()));
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }, context.executor()).whenComplete((ignored, throwable) -> Platform.runLater(() -> status.setText(
                throwable == null ? "已导出：" + file.getAbsolutePath()
                        : "导出失败：" + Dialogs.rootMessage(throwable))));
    }

    private void setBusy(boolean busy) {
        selectionButton.setDisable(busy);
        currentButton.setDisable(busy);
        scriptButton.setDisable(busy);
        maxRows.setDisable(busy);
        cancelButton.setDisable(!busy);
    }

    private void handleCloseRequest(javafx.event.Event event) {
        if (forceClosing || !session.hasPendingTransaction()) return;
        event.consume();
        Dialog<ButtonType> dialog = new Dialog<>();
        Dialogs.init(dialog, owner(), "存在未提交事务");
        ButtonType commit = new ButtonType("提交并关闭");
        ButtonType rollback = new ButtonType("回滚并关闭");
        dialog.getDialogPane().getButtonTypes().addAll(commit, rollback, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(new Label("此 SQL 标签存在未提交事务，请选择处理方式。"));
        dialog.showAndWait().ifPresent(choice -> {
            if (choice == ButtonType.CANCEL) return;
            setDisable(true);
            CompletableFuture.runAsync(() -> {
                try {
                    if (choice == commit) session.commit();
                    else session.rollback();
                    session.close();
                } catch (SQLException exception) {
                    throw new RuntimeException(exception);
                }
            }, context.executor()).whenComplete((ignored, throwable) -> Platform.runLater(() -> {
                if (throwable != null) {
                    setDisable(false);
                    Dialogs.error(owner(), "关闭 SQL 标签失败", throwable);
                    return;
                }
                resourcesClosed.set(true);
                workspace.releaseSession(session);
                forceClosing = true;
                if (getTabPane() != null) getTabPane().getTabs().remove(this);
            }));
        });
    }

    private void closeResources() {
        highlightingSubscription.unsubscribe();
        if (!resourcesClosed.compareAndSet(false, true)) return;
        workspace.releaseSession(session);
        CompletableFuture.runAsync(() -> {
            try {
                session.close();
            } catch (SQLException ignored) {
                // 标签关闭时尽力释放 JDBC 会话。
            }
        }, context.executor());
    }

    private Window owner() {
        return getContent() == null || getContent().getScene() == null ? null : getContent().getScene().getWindow();
    }

    private record RunResult(ExecutionResult execution, String historyWarning) {
    }

    String profileId() {
        return workspace.profile().id();
    }
}
