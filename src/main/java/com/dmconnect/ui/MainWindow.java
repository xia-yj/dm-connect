package com.dmconnect.ui;

import com.dmconnect.AppContext;
import com.dmconnect.model.ConnectionProfile;
import com.dmconnect.model.DatabaseObject;
import com.dmconnect.model.DatabaseObjectKind;
import com.dmconnect.model.DriverDescriptor;
import com.dmconnect.model.HistoryEntry;
import com.dmconnect.model.ResultTable;
import com.dmconnect.model.TableDetails;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class MainWindow implements AutoCloseable {
    private final Stage stage;
    private final AppContext context;
    private final BorderPane root = new BorderPane();
    private final TreeItem<BrowserNode> treeRoot = new TreeItem<>(BrowserNode.root());
    private final TreeView<BrowserNode> tree = new TreeView<>(treeRoot);
    private final TextField filter = new TextField();
    private final TabPane tabs = new TabPane();
    private final Label status = new Label("就绪");
    private final Label profileCount = new Label();
    private final Map<String, ConnectedProfile> connected = new HashMap<>();
    private final Map<TreeItem<BrowserNode>, List<TreeItem<BrowserNode>>> categoryCache = new HashMap<>();
    private boolean closed;

    public MainWindow(Stage stage, AppContext context) {
        this.stage = stage;
        this.context = context;
        buildUi();
        refreshProfiles();
    }

    public Scene scene() {
        Scene scene = new Scene(root, 1280, 820);
        var css = MainWindow.class.getResource("/com/dmconnect/ui/application.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        return scene;
    }

    private void buildUi() {
        root.getStyleClass().add("app-shell");
        root.setTop(new VBox(buildMenu(), buildHeader()));
        root.setCenter(buildMainArea());
        root.setBottom(buildStatusBar());

        tabs.getStyleClass().add("workspace-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.SELECTED_TAB);
        tabs.getTabs().add(buildWelcomeTab());
    }

    private MenuBar buildMenu() {
        MenuItem newProfile = item("新建连接", this::newProfile);
        MenuItem editProfile = item("编辑连接", this::editProfile);
        MenuItem copyProfile = item("复制连接", this::copyProfile);
        MenuItem deleteProfile = item("删除连接", this::deleteProfile);
        MenuItem importDriver = item("导入 JDBC 驱动…", this::importDriver);
        MenuItem exit = item("退出", stage::close);
        Menu file = new Menu("文件", null, newProfile, editProfile, copyProfile, deleteProfile, importDriver,
                new javafx.scene.control.SeparatorMenuItem(), exit);

        Menu connection = new Menu("连接", null,
                item("连接", this::connectSelected),
                item("断开", this::disconnectSelected),
                item("刷新对象", this::refreshSelected),
                item("新建 SQL 标签", () -> newSqlTab(null, null)));

        Menu tools = new Menu("工具", null,
                item("SQL 历史", this::showHistory),
                item("清除已保存密码和 SQL 历史", this::clearLocalData));

        Menu help = new Menu("帮助", null, item("关于数据库连接工具", () -> Dialogs.info(stage,
                "关于数据库连接工具", "数据库连接工具 2.0.0 Legacy\nJavaFX 回退界面")));
        MenuBar menuBar = new MenuBar(file, connection, tools, help);
        menuBar.setUseSystemMenuBar(true);
        return menuBar;
    }

    private HBox buildHeader() {
        Label logo = new Label("DM");
        logo.getStyleClass().add("brand-mark");
        Label title = new Label("数据库连接工具");
        title.getStyleClass().add("brand-title");
        Label subtitle = new Label("达梦数据库工作台");
        subtitle.getStyleClass().add("brand-subtitle");
        VBox brandText = new VBox(1, title, subtitle);
        HBox brand = new HBox(12, logo, brandText);
        brand.setAlignment(Pos.CENTER_LEFT);

        Button newConnection = button("新建连接", this::newProfile);
        newConnection.getStyleClass().addAll("header-button", "primary-button");
        Button editConnection = button("编辑连接", this::editProfile);
        editConnection.getStyleClass().add("header-button");
        Button connectButton = button("连接", this::connectSelected);
        connectButton.getStyleClass().add("header-button");
        Button disconnectButton = button("断开", this::disconnectSelected);
        disconnectButton.getStyleClass().add("header-button");
        Button sqlButton = button("新建 SQL", () -> newSqlTab(null, null));
        sqlButton.getStyleClass().addAll("header-button", "accent-button");
        Button refreshButton = button("刷新", this::refreshSelected);
        refreshButton.getStyleClass().add("header-button");
        Button historyButton = button("历史", this::showHistory);
        historyButton.getStyleClass().add("header-button");

        HBox actions = new HBox(8, newConnection, editConnection, connectButton, disconnectButton,
                sqlButton, refreshButton, historyButton);
        actions.getStyleClass().add("header-actions");
        actions.setAlignment(Pos.CENTER_RIGHT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(brand, spacer, actions);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("app-header");
        return header;
    }

    private SplitPane buildMainArea() {
        tree.setShowRoot(false);
        tree.setPrefWidth(300);
        tree.getStyleClass().add("object-tree");
        tree.setCellFactory(ignored -> new BrowserTreeCell());
        tree.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) handleTreeDoubleClick();
        });
        filter.setPromptText("搜索已加载的数据库对象");
        filter.getStyleClass().add("search-field");
        filter.textProperty().addListener((observable, oldValue, value) -> applyFilter());

        Label sidebarTitle = new Label("连接与对象");
        sidebarTitle.getStyleClass().add("sidebar-title");
        profileCount.getStyleClass().add("sidebar-count");
        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        HBox sidebarHeader = new HBox(sidebarTitle, titleSpacer, profileCount);
        sidebarHeader.setAlignment(Pos.CENTER_LEFT);

        Label hint = new Label("双击连接以加载模式，双击表可预览数据");
        hint.getStyleClass().add("sidebar-hint");
        Button importButton = button("导入 JDBC 驱动", this::importDriver);
        importButton.setMaxWidth(Double.MAX_VALUE);
        importButton.getStyleClass().add("sidebar-footer-button");

        VBox left = new VBox(10, sidebarHeader, hint, filter, tree, importButton);
        left.getStyleClass().add("sidebar");
        left.setMinWidth(260);
        left.setPrefWidth(300);
        left.setMaxWidth(390);
        VBox.setVgrow(tree, Priority.ALWAYS);
        SplitPane split = new SplitPane(left, tabs);
        split.getStyleClass().add("main-split");
        split.setDividerPositions(0.235);
        return split;
    }

    private HBox buildStatusBar() {
        Label indicator = new Label("●");
        indicator.getStyleClass().add("status-indicator");
        status.getStyleClass().add("status-message");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label secure = new Label("本地数据已加密  ·  Java 17");
        secure.getStyleClass().add("status-meta");
        HBox bar = new HBox(8, indicator, status, spacer, secure);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("status-bar");
        return bar;
    }

    private Tab buildWelcomeTab() {
        Label mark = new Label("DM");
        mark.getStyleClass().add("welcome-mark");
        Label eyebrow = new Label("DAMENG DATABASE CLIENT");
        eyebrow.getStyleClass().add("welcome-eyebrow");
        Label title = new Label("专注数据库，少一点干扰");
        title.getStyleClass().add("welcome-title");
        Label description = new Label("管理达梦连接、浏览数据库对象并在独立会话中安全执行 SQL。\n所有密码和 SQL 历史均在本机加密保存。");
        description.getStyleClass().add("welcome-description");
        description.setWrapText(true);

        Button create = button("新建数据库连接", this::newProfile);
        create.getStyleClass().add("primary-button");
        Button driver = button("导入 JDBC 驱动", this::importDriver);
        driver.getStyleClass().add("secondary-button");
        HBox actions = new HBox(10, create, driver);
        actions.setAlignment(Pos.CENTER);
        Label tip = new Label("连接成功后，可从左侧对象树打开表预览或创建 SQL 工作台");
        tip.getStyleClass().add("welcome-tip");

        VBox card = new VBox(14, mark, eyebrow, title, description, actions, tip);
        card.setAlignment(Pos.CENTER);
        card.setMaxWidth(650);
        card.getStyleClass().add("welcome-card");
        StackPane canvas = new StackPane(card);
        canvas.getStyleClass().add("welcome-canvas");
        Tab welcome = new Tab("工作台", canvas);
        welcome.setClosable(false);
        return welcome;
    }

    private void refreshProfiles() {
        try {
            treeRoot.getChildren().clear();
            categoryCache.clear();
            List<ConnectionProfile> profiles = context.profiles().findAll();
            for (ConnectionProfile profile : profiles) {
                TreeItem<BrowserNode> item = new TreeItem<>(BrowserNode.profile(profile, connected.containsKey(profile.id())));
                if (connected.containsKey(profile.id())) item.getChildren().add(new TreeItem<>(BrowserNode.message("双击以重新加载对象")));
                treeRoot.getChildren().add(item);
            }
            profileCount.setText(connected.size() + " / " + profiles.size() + " 已连接");
        } catch (Exception exception) {
            Dialogs.error(stage, "加载连接配置失败", exception);
        }
    }

    private void newProfile() {
        try {
            if (!ensureDriverAvailable()) return;
            saveProfileDialog(null);
        } catch (Exception exception) {
            Dialogs.error(stage, "新建连接失败", exception);
        }
    }

    private void editProfile() {
        ConnectionProfile profile = selectedProfile();
        if (profile == null) {
            status.setText("请先选择一个连接");
            return;
        }
        try {
            saveProfileDialog(profile);
        } catch (Exception exception) {
            Dialogs.error(stage, "编辑连接失败", exception);
        }
    }

    private void saveProfileDialog(ConnectionProfile original) throws Exception {
        ConnectionProfileDialog dialog = new ConnectionProfileDialog(stage, context, original);
        Optional<ConnectionProfileDialog.Result> selected = dialog.showAndWait();
        if (selected.isEmpty()) return;
        ConnectionProfileDialog.Result result = selected.get();
        char[] password = result.password();
        try {
            if (original != null) disconnect(original.id());
            context.profiles().save(result.profile());
            if (!result.profile().rememberPassword()) {
                context.vault().removeSecret(result.profile().id());
            } else if (result.passwordProvided()) {
                context.vault().putSecret(result.profile().id(), password);
            }
            refreshProfiles();
            status.setText("连接配置已保存：" + result.profile().name());
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private void copyProfile() {
        ConnectionProfile profile = selectedProfile();
        if (profile == null) return;
        try {
            ConnectionProfile copy = profile.copyWithNewId(profile.name() + " 副本");
            context.profiles().save(copy);
            Optional<char[]> secret = context.vault().getSecret(profile.id());
            if (secret.isPresent()) {
                char[] password = secret.get();
                try {
                    context.vault().putSecret(copy.id(), password);
                } finally {
                    Arrays.fill(password, '\0');
                }
            }
            refreshProfiles();
        } catch (Exception exception) {
            Dialogs.error(stage, "复制连接失败", exception);
        }
    }

    private void deleteProfile() {
        ConnectionProfile profile = selectedProfile();
        if (profile == null) return;
        if (!Dialogs.confirm(stage, "删除连接", "确认删除连接“" + profile.name() + "”吗？")) return;
        try {
            disconnect(profile.id());
            context.profiles().delete(profile.id());
            context.vault().removeSecret(profile.id());
            refreshProfiles();
            status.setText("连接已删除");
        } catch (Exception exception) {
            Dialogs.error(stage, "删除连接失败", exception);
        }
    }

    private boolean ensureDriverAvailable() throws Exception {
        if (!context.drivers().findAll().isEmpty()) return true;
        if (!Dialogs.confirm(stage, "尚未导入驱动", "创建连接前需要导入达梦 JDBC JAR，是否现在导入？")) return false;
        importDriver();
        return !context.drivers().findAll().isEmpty();
    }

    private void importDriver() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导入达梦 JDBC 驱动");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JDBC JAR", "*.jar"));
        File selected = chooser.showOpenDialog(stage);
        if (selected == null) return;
        status.setText("正在校验并导入 JDBC 驱动…");
        CompletableFuture.supplyAsync(() -> {
            try {
                return context.drivers().importDriver(selected.toPath());
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }, context.executor()).whenComplete((driver, throwable) -> Platform.runLater(() -> {
            if (throwable != null) {
                Dialogs.error(stage, "导入驱动失败", throwable);
                status.setText("驱动导入失败");
            } else {
                status.setText("已导入驱动：" + driver.displayName() + "，版本 " + driver.version());
                Dialogs.info(stage, "驱动导入成功", "已校验 " + driver.driverClass()
                        + "\n请确保驱动版本与达梦服务器版本相近。");
            }
        }));
    }

    private void connectSelected() {
        ConnectionProfile profile = selectedProfile();
        if (profile == null) {
            status.setText("请先选择一个连接");
            return;
        }
        if (connected.containsKey(profile.id())) {
            TreeItem<BrowserNode> item = findProfileItem(profile.id());
            if (item != null) loadSchemas(connected.get(profile.id()), item);
            return;
        }
        char[] password;
        try {
            Optional<char[]> stored = context.vault().getSecret(profile.id());
            if (stored.isPresent()) password = stored.get();
            else {
                Optional<char[]> entered = Dialogs.password(stage, "连接到 " + profile.name(), "请输入数据库密码");
                if (entered.isEmpty()) return;
                password = entered.get();
            }
        } catch (Exception exception) {
            Dialogs.error(stage, "读取连接密码失败", exception);
            return;
        }

        status.setText("正在连接 " + profile.name() + "…");
        char[] passwordCopy = password.clone();
        Arrays.fill(password, '\0');
        CompletableFuture.supplyAsync(() -> {
            ConnectedProfile workspace = new ConnectedProfile(context, profile, passwordCopy);
            Arrays.fill(passwordCopy, '\0');
            try (Connection connection = workspace.openConnection()) {
                List<String> schemas = context.databaseAdapter().metadataProvider().listSchemas(connection);
                return new ConnectResult(workspace, schemas);
            } catch (Exception exception) {
                workspace.close();
                throw new RuntimeException(exception);
            }
        }, context.executor()).whenComplete((result, throwable) -> Platform.runLater(() -> {
            if (throwable != null) {
                Dialogs.error(stage, "连接失败", throwable);
                status.setText("连接失败：" + profile.name());
                return;
            }
            connected.put(profile.id(), result.workspace());
            refreshProfiles();
            TreeItem<BrowserNode> item = findProfileItem(profile.id());
            if (item != null) populateSchemas(item, profile.id(), result.schemas());
            status.setText("已连接：" + profile.name());
        }));
    }

    private void loadSchemas(ConnectedProfile workspace, TreeItem<BrowserNode> profileItem) {
        profileItem.getChildren().setAll(new TreeItem<>(BrowserNode.message("正在加载模式…")));
        status.setText("正在刷新 " + workspace.profile().name() + "…");
        CompletableFuture.supplyAsync(() -> {
            try (Connection connection = workspace.openConnection()) {
                return context.databaseAdapter().metadataProvider().listSchemas(connection);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }, context.executor()).whenComplete((schemas, throwable) -> Platform.runLater(() -> {
            if (throwable != null) {
                profileItem.getChildren().setAll(new TreeItem<>(BrowserNode.message("加载失败：" + Dialogs.rootMessage(throwable))));
                status.setText("模式加载失败");
            } else {
                populateSchemas(profileItem, workspace.profile().id(), schemas);
                status.setText("对象树已刷新");
            }
        }));
    }

    private void populateSchemas(TreeItem<BrowserNode> profileItem, String profileId, List<String> schemas) {
        categoryCache.clear();
        List<TreeItem<BrowserNode>> schemaItems = new ArrayList<>();
        for (String schema : schemas) {
            TreeItem<BrowserNode> schemaItem = new TreeItem<>(BrowserNode.schema(profileId, schema));
            for (DatabaseObjectKind kind : DatabaseObjectKind.values()) {
                TreeItem<BrowserNode> category = new TreeItem<>(BrowserNode.category(profileId, schema, kind));
                category.getChildren().add(new TreeItem<>(BrowserNode.message("展开加载")));
                category.expandedProperty().addListener((observable, oldValue, expanded) -> {
                    if (expanded) loadCategory(category);
                });
                schemaItem.getChildren().add(category);
            }
            schemaItems.add(schemaItem);
        }
        profileItem.getChildren().setAll(schemaItems);
        profileItem.setExpanded(true);
    }

    private void loadCategory(TreeItem<BrowserNode> categoryItem) {
        if (categoryCache.containsKey(categoryItem)) {
            applyFilter(categoryItem);
            return;
        }
        BrowserNode node = categoryItem.getValue();
        ConnectedProfile workspace = connected.get(node.profileId());
        if (workspace == null) return;
        categoryItem.getChildren().setAll(new TreeItem<>(BrowserNode.message("正在加载…")));
        CompletableFuture.supplyAsync(() -> {
            try (Connection connection = workspace.openConnection()) {
                return context.databaseAdapter().metadataProvider()
                        .listObjects(connection, node.schema(), node.objectKind());
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }, context.executor()).whenComplete((objects, throwable) -> Platform.runLater(() -> {
            if (throwable != null) {
                categoryItem.getChildren().setAll(new TreeItem<>(BrowserNode.message("加载失败：" + Dialogs.rootMessage(throwable))));
                return;
            }
            List<TreeItem<BrowserNode>> items = objects.stream()
                    .map(object -> new TreeItem<>(BrowserNode.object(node.profileId(), object)))
                    .toList();
            categoryCache.put(categoryItem, items);
            applyFilter(categoryItem);
        }));
    }

    private void applyFilter() {
        categoryCache.keySet().forEach(this::applyFilter);
    }

    private void applyFilter(TreeItem<BrowserNode> category) {
        List<TreeItem<BrowserNode>> all = categoryCache.get(category);
        if (all == null) return;
        String needle = filter.getText() == null ? "" : filter.getText().strip().toLowerCase();
        if (needle.isEmpty()) category.getChildren().setAll(all);
        else category.getChildren().setAll(all.stream()
                .filter(item -> item.getValue().label().toLowerCase().contains(needle)).toList());
        if (category.getChildren().isEmpty()) category.getChildren().add(new TreeItem<>(BrowserNode.message("没有匹配对象")));
    }

    private void handleTreeDoubleClick() {
        TreeItem<BrowserNode> selected = tree.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        BrowserNode node = selected.getValue();
        if (node.type() == BrowserNode.Type.PROFILE) connectSelected();
        else if (node.type() == BrowserNode.Type.OBJECT) openObject(node);
    }

    private void openObject(BrowserNode node) {
        ConnectedProfile workspace = connected.get(node.profileId());
        if (workspace == null) {
            status.setText("请先连接数据库");
            return;
        }
        DatabaseObject object = node.object();
        Tab tab = new Tab(object.kind().displayName() + " · " + object.name());
        tab.setContent(new BorderPane(new ProgressIndicator()));
        tabs.getTabs().add(tab);
        tabs.getSelectionModel().select(tab);
        CompletableFuture.supplyAsync(() -> loadObject(workspace, object), context.executor())
                .whenComplete((loaded, throwable) -> Platform.runLater(() -> {
                    if (throwable != null) tab.setContent(errorPane("加载对象失败：" + Dialogs.rootMessage(throwable)));
                    else tab.setContent(new ObjectDetailsPane(loaded));
                }));
    }

    private ObjectLoadResult loadObject(ConnectedProfile workspace, DatabaseObject object) {
        TableDetails details = null;
        ResultTable preview = null;
        String ddl = null;
        String detailsError = "";
        String previewError = "";
        String ddlError = "";
        try (Connection connection = workspace.openConnection()) {
            if (object.kind() == DatabaseObjectKind.TABLE) {
                try {
                    details = context.databaseAdapter().metadataProvider().describeTable(connection, object);
                } catch (Exception exception) {
                    detailsError = "读取表结构失败：" + Dialogs.rootMessage(exception);
                }
                try {
                    preview = context.databaseAdapter().metadataProvider().previewTable(connection, object, 0, 100).table();
                } catch (Exception exception) {
                    previewError = "读取数据预览失败：" + Dialogs.rootMessage(exception);
                }
            }
            try {
                ddl = context.databaseAdapter().ddlProvider().getDdl(connection, object);
            } catch (Exception exception) {
                ddlError = "读取 DDL 失败：" + Dialogs.rootMessage(exception)
                        + "。当前用户可能没有该对象的元数据权限。";
            }
        } catch (Exception exception) {
            String error = "数据库连接失败：" + Dialogs.rootMessage(exception);
            if (detailsError.isBlank()) detailsError = error;
            if (previewError.isBlank()) previewError = error;
            if (ddlError.isBlank()) ddlError = error;
        }
        return new ObjectLoadResult(object, details, detailsError, preview, previewError, ddl, ddlError);
    }

    private void newSqlTab(String requestedProfileId, String initialSql) {
        ConnectedProfile workspace = requestedProfileId == null ? selectedWorkspace() : connected.get(requestedProfileId);
        if (workspace == null) {
            status.setText("请先连接数据库，再打开 SQL 标签");
            return;
        }
        status.setText("正在创建 SQL 会话…");
        CompletableFuture.supplyAsync(() -> {
            try {
                return workspace.openSession();
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }, context.executor()).whenComplete((session, throwable) -> Platform.runLater(() -> {
            if (throwable != null) {
                Dialogs.error(stage, "创建 SQL 会话失败", throwable);
                return;
            }
            SqlEditorTab tab = new SqlEditorTab(context, workspace, session, initialSql);
            tabs.getTabs().add(tab);
            tabs.getSelectionModel().select(tab);
            status.setText("SQL 会话已创建：" + workspace.profile().name());
        }));
    }

    private void showHistory() {
        HistoryDialog dialog = new HistoryDialog(stage, context.vault());
        dialog.showAndWait().ifPresent(entry -> {
            if (!connected.containsKey(entry.profileId())) {
                status.setText("请先连接“" + entry.profileName() + "”，再恢复这条 SQL 历史");
                return;
            }
            newSqlTab(entry.profileId(), entry.sql());
        });
    }

    private void refreshSelected() {
        TreeItem<BrowserNode> profileItem = selectedProfileItem();
        if (profileItem == null) {
            refreshProfiles();
            return;
        }
        ConnectedProfile workspace = connected.get(profileItem.getValue().profileId());
        if (workspace == null) {
            status.setText("连接尚未打开");
            return;
        }
        loadSchemas(workspace, profileItem);
    }

    private void disconnectSelected() {
        ConnectionProfile profile = selectedProfile();
        if (profile == null) return;
        disconnect(profile.id());
        refreshProfiles();
        status.setText("已断开：" + profile.name());
    }

    private void disconnect(String profileId) {
        ConnectedProfile workspace = connected.remove(profileId);
        if (workspace == null) return;
        tabs.getTabs().removeIf(tab -> tab instanceof SqlEditorTab sql && sql.profileId().equals(profileId));
        categoryCache.clear();
        CompletableFuture.runAsync(workspace::close, context.executor());
    }

    private void clearLocalData() {
        if (!Dialogs.confirm(stage, "清除本地数据", "这将删除已保存的数据库密码和 SQL 历史，但保留连接配置。确认继续吗？")) return;
        disconnectAll();
        try {
            context.vault().resetLocalData();
        } catch (Exception exception) {
            Dialogs.error(stage, "清除本地数据失败", exception);
            return;
        }
        refreshProfiles();
        status.setText("已清除保存的密码和 SQL 历史");
    }

    private ConnectionProfile selectedProfile() {
        TreeItem<BrowserNode> item = selectedProfileItem();
        if (item == null) return null;
        try {
            return context.profiles().findById(item.getValue().profileId()).orElse(null);
        } catch (Exception exception) {
            Dialogs.error(stage, "读取连接配置失败", exception);
            return null;
        }
    }

    private TreeItem<BrowserNode> selectedProfileItem() {
        TreeItem<BrowserNode> item = tree.getSelectionModel().getSelectedItem();
        while (item != null && item.getValue().type() != BrowserNode.Type.PROFILE) item = item.getParent();
        return item;
    }

    private TreeItem<BrowserNode> findProfileItem(String profileId) {
        return treeRoot.getChildren().stream()
                .filter(item -> profileId.equals(item.getValue().profileId()))
                .findFirst().orElse(null);
    }

    private ConnectedProfile selectedWorkspace() {
        TreeItem<BrowserNode> item = selectedProfileItem();
        if (item != null) return connected.get(item.getValue().profileId());
        if (connected.size() == 1) return connected.values().iterator().next();
        return null;
    }

    private BorderPane errorPane(String message) {
        Label label = new Label(message);
        label.getStyleClass().add("error-label");
        label.setWrapText(true);
        BorderPane pane = new BorderPane(label);
        pane.setPadding(new Insets(20));
        return pane;
    }

    private MenuItem item(String text, Runnable action) {
        MenuItem item = new MenuItem(text);
        item.setOnAction(event -> action.run());
        return item;
    }

    private Button button(String text, Runnable action) {
        Button button = new Button(text);
        button.setOnAction(event -> action.run());
        return button;
    }

    private final class BrowserTreeCell extends TreeCell<BrowserNode> {
        @Override
        protected void updateItem(BrowserNode node, boolean empty) {
            super.updateItem(node, empty);
            if (empty || node == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            setText(node.label());
            Label icon = new Label(iconText(node));
            icon.getStyleClass().addAll("tree-node-icon", "tree-node-" + node.type().name().toLowerCase());
            if (node.type() == BrowserNode.Type.PROFILE && connected.containsKey(node.profileId())) {
                icon.getStyleClass().add("tree-node-connected");
            }
            setGraphic(icon);
        }

        private String iconText(BrowserNode node) {
            return switch (node.type()) {
                case PROFILE -> "D";
                case SCHEMA -> "S";
                case CATEGORY, OBJECT -> objectIcon(node.objectKind());
                case MESSAGE -> "·";
                case ROOT -> "D";
            };
        }

        private String objectIcon(DatabaseObjectKind kind) {
            if (kind == null) return "·";
            return switch (kind) {
                case TABLE -> "T";
                case VIEW -> "V";
                case SEQUENCE -> "#";
                case PROCEDURE -> "P";
                case FUNCTION -> "F";
                case TRIGGER -> "!";
            };
        }
    }

    private void disconnectAll() {
        List<ConnectedProfile> workspaces = new ArrayList<>(connected.values());
        connected.clear();
        tabs.getTabs().removeIf(tab -> tab instanceof SqlEditorTab);
        categoryCache.clear();
        workspaces.forEach(workspace -> CompletableFuture.runAsync(workspace::close, context.executor()));
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        disconnectAll();
    }

    private record ConnectResult(ConnectedProfile workspace, List<String> schemas) {
    }
}
