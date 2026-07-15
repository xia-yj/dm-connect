import Editor, { type OnMount } from "@monaco-editor/react";
import type { editor, Position } from "monaco-editor";
import { useEffect, useRef, useState } from "react";
import {
  AlertTriangle, Ban, CheckCheck, ChevronDown, CircleStop, Clock3, Download,
  LoaderCircle, Play, PlayCircle, RotateCcw, Rows3, Save, TerminalSquare
} from "lucide-react";
import { asRpcError, errorMessage, rpc } from "../api";
import { sqlDialectConfig } from "../sqlDialect";
import type { DatabaseObject, DatabaseType, ExecutionResult, ObjectLoadResult, PagedResultTable, QueryStatus, StatementOutcome } from "../types";
import { DataGrid } from "./DataGrid";
import { ConfirmModal } from "./Modal";
import { ObjectView } from "./ObjectView";
import "../monaco";

interface SqlWorkspaceProps {
  sessionId: string;
  profileName: string;
  profileId: string;
  databaseType: DatabaseType;
  schemas: string[];
  tableSuggestions: DatabaseObject[];
  initialSql?: string;
}

type ExecuteMode = "SELECTION" | "CURRENT_STATEMENT" | "SCRIPT";
type ColumnSuggestion = { schema: string; table: string; name: string; typeName: string; remarks: string | null };

export function SqlWorkspace({ sessionId, profileName, profileId, databaseType, schemas, tableSuggestions, initialSql = "" }: SqlWorkspaceProps) {
  const dialect = sqlDialectConfig(databaseType);
  const workspaceRef = useRef<HTMLElement | null>(null);
  const editorRef = useRef<editor.IStandaloneCodeEditor | null>(null);
  const completionRef = useRef<{ dispose: () => void } | null>(null);
  const suggestionsRef = useRef({ schemas, tables: tableSuggestions, columns: [] as ColumnSuggestion[] });
  const executeCurrentRef = useRef<() => void>(() => {});
  const requestedSchemasRef = useRef(new Set<string>());
  const requestedTableDetailsRef = useRef(new Set<string>());
  const suppressInitialSuggestRef = useRef(initialSql.trim().length > 0);
  const [contextTables, setContextTables] = useState<DatabaseObject[]>([]);
  const [sql, setSql] = useState(initialSql);
  const [execution, setExecution] = useState<ExecutionResult | null>(null);
  const [activeOutcome, setActiveOutcome] = useState(0);
  const [busy, setBusy] = useState(false);
  const [autoCommit, setAutoCommitState] = useState(true);
  const [pendingTransaction, setPendingTransaction] = useState(false);
  const maxRows = 100;
  const [tablePreview, setTablePreview] = useState<ObjectLoadResult | null>(null);
  const [editorHeight, setEditorHeight] = useState(() => Number(localStorage.getItem("dm-connect.sql-editor-height")) || 390);
  const [resizingResults, setResizingResults] = useState(false);
  const [message, setMessage] = useState("SQL 会话已就绪");
  const [dangerous, setDangerous] = useState<{ statements: string[]; mode: ExecuteMode } | null>(null);
  const [confirmAutoCommit, setConfirmAutoCommit] = useState(false);

  useEffect(() => {
    const tables = [...tableSuggestions, ...contextTables].filter((table, index, items) =>
      items.findIndex(item => item.schema === table.schema && item.name === table.name) === index);
    suggestionsRef.current = { schemas, tables, columns: suggestionsRef.current.columns };
  }, [schemas, tableSuggestions, contextTables]);
  useEffect(() => {
    const match = sql.match(/([A-Za-z_][A-Za-z0-9_$]*)\.[A-Za-z0-9_$]*$/);
    if (!match) return;
    const schema = schemas.find(item => item.toLowerCase() === match[1].toLowerCase());
    if (!schema || requestedSchemasRef.current.has(schema.toLowerCase())) return;
    requestedSchemasRef.current.add(schema.toLowerCase());
    void rpc<DatabaseObject[]>("objects.list", { profileId, schema, kind: "TABLE" }).then(tables => {
      suggestionsRef.current = {
        schemas,
        tables: [...suggestionsRef.current.tables, ...tables].filter((table, index, items) =>
          items.findIndex(item => item.schema === table.schema && item.name === table.name) === index),
        columns: suggestionsRef.current.columns
      };
      setContextTables(current => [...current, ...tables]);
      // 历史 SQL 的初次加载只补齐候选缓存，不能主动弹出联想框；
      // 后续用户编辑或输入模式名时仍会照常触发联想。
      if (!suppressInitialSuggestRef.current) {
        editorRef.current?.trigger("sql-autocomplete", "editor.action.triggerSuggest", undefined);
      }
    }).catch(() => requestedSchemasRef.current.delete(schema.toLowerCase()));
  }, [sql, schemas, profileId]);
  useEffect(() => {
    const match = sql.match(/\bFROM\s+([A-Za-z_][A-Za-z0-9_$]*)\.([A-Za-z_][A-Za-z0-9_$]*)\b/i);
    if (!match) return;
    const schema = schemas.find(item => item.toLowerCase() === match[1].toLowerCase()) ?? match[1];
    const table = match[2];
    const key = `${schema.toLowerCase()}.${table.toLowerCase()}`;
    if (requestedTableDetailsRef.current.has(key)) return;
    requestedTableDetailsRef.current.add(key);
    void rpc<ObjectLoadResult>("object.load", { profileId, schema, name: table, kind: "TABLE", remarks: "" }).then(result => {
      if (!result.details) return;
      const current = suggestionsRef.current;
      suggestionsRef.current = {
        ...current,
        columns: [...current.columns.filter(column => `${column.schema.toLowerCase()}.${column.table.toLowerCase()}` !== key),
          ...result.details.columns.map(column => ({ schema, table, name: column.name, typeName: column.typeName, remarks: column.remarks }))]
      };
      if (!suppressInitialSuggestRef.current) editorRef.current?.trigger("sql-column-autocomplete", "editor.action.triggerSuggest", undefined);
    }).catch(() => requestedTableDetailsRef.current.delete(key));
  }, [sql, schemas, profileId]);
  useEffect(() => () => completionRef.current?.dispose(), []);
  useEffect(() => {
    localStorage.setItem("dm-connect.sql-editor-height", String(editorHeight));
  }, [editorHeight]);
  useEffect(() => {
    if (!resizingResults) return;
    const resize = (event: PointerEvent) => {
      const workspace = workspaceRef.current;
      if (!workspace) return;
      const top = workspace.getBoundingClientRect().top;
      // 允许结果面板收起编辑器到 0px；结果区随即占满工作台（保留 SQL 工具栏）。
      const available = workspace.clientHeight - 54 - 33 - 7;
      setEditorHeight(Math.max(0, Math.min(available, event.clientY - top - 54)));
    };
    const stop = () => setResizingResults(false);
    window.addEventListener("pointermove", resize);
    window.addEventListener("pointerup", stop);
    return () => { window.removeEventListener("pointermove", resize); window.removeEventListener("pointerup", stop); };
  }, [resizingResults]);

  const onMount: OnMount = (instance, monaco) => {
    editorRef.current = instance;
    completionRef.current?.dispose();
    completionRef.current = monaco.languages.registerCompletionItemProvider("sql", {
      triggerCharacters: [".", " "],
      provideCompletionItems: (model: editor.ITextModel, position: Position) => {
        if (model !== editorRef.current?.getModel()) return { suggestions: [] };
        const word = model.getWordUntilPosition(position);
        const range = { startLineNumber: position.lineNumber, endLineNumber: position.lineNumber, startColumn: word.startColumn, endColumn: word.endColumn };
        const dynamic = suggestionsRef.current;
        const linePrefix = model.getValueInRange({ startLineNumber: position.lineNumber, startColumn: 1, endLineNumber: position.lineNumber, endColumn: position.column });
        const schemaMatch = linePrefix.match(/([A-Za-z_][A-Za-z0-9_$]*)\.[A-Za-z0-9_$]*$/);
        const scopedTables = schemaMatch
          ? dynamic.tables.filter(table => table.schema.toLowerCase() === schemaMatch[1].toLowerCase())
          : dynamic.tables;
        const sourceMatch = model.getValue().match(/\bFROM\s+([A-Za-z_][A-Za-z0-9_$]*)\.([A-Za-z_][A-Za-z0-9_$]*)\b/i);
        const cachedColumns = sourceMatch
          ? dynamic.columns.filter(column => column.schema.toLowerCase() === sourceMatch[1].toLowerCase() && column.table.toLowerCase() === sourceMatch[2].toLowerCase())
          : dynamic.columns;
        const columnItems = cachedColumns.map(column => ({
          label: column.name,
          detail: `${column.schema}.${column.table} · ${column.typeName}${column.remarks ? ` · ${column.remarks}` : ""}`,
          kind: monaco.languages.CompletionItemKind.Field,
          insertText: column.name,
          sortText: `0_column_${column.name}`,
          range
        }));
        const suggestions = schemaMatch
          ? scopedTables.map(table => ({ label: table.name, detail: `${table.schema} · 表`, kind: monaco.languages.CompletionItemKind.Class, insertText: table.name, sortText: `0_${table.name}`, range }))
          : [
              ...dialect.keywords.map(label => ({ label, kind: monaco.languages.CompletionItemKind.Keyword, insertText: label, range })),
              ...dialect.functions.map(label => ({ label, kind: monaco.languages.CompletionItemKind.Function, insertText: `${label}($0)`, insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet, range })),
              ...dialect.snippets.map(snippet => ({ label: snippet.label, detail: snippet.detail, documentation: snippet.detail, kind: monaco.languages.CompletionItemKind.Snippet, insertText: snippet.insertText, insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet, sortText: `0_${snippet.label}`, range })),
              ...columnItems,
              ...dynamic.schemas.map(schema => ({ label: schema, detail: dialect.namespaceLabel, kind: monaco.languages.CompletionItemKind.Module, insertText: schema, range })),
              ...scopedTables.flatMap(table => [
                { label: table.name, detail: `${table.schema} · 表`, kind: monaco.languages.CompletionItemKind.Class, insertText: table.name, range },
                { label: `${table.schema}.${table.name}`, detail: "完整表名", kind: monaco.languages.CompletionItemKind.Class, insertText: `${table.schema}.${table.name}`, range }
              ])
            ];
        return { suggestions };
      }
    });
    instance.onDidChangeModelContent(event => {
      suppressInitialSuggestRef.current = false;
      if (event.changes.length !== 1 || event.changes[0].text !== "。") return;
      const position = instance.getPosition();
      const model = instance.getModel();
      if (!position || !model || position.column < 2) return;
      const prefix = model.getValueInRange({ startLineNumber: position.lineNumber, startColumn: 1, endLineNumber: position.lineNumber, endColumn: position.column });
      // 输入法误输“。”时，仅在对象限定符位置替换成英文点号并继续触发联想。
      if (!/[A-Za-z_][A-Za-z0-9_$]*。$/.test(prefix)) return;
      instance.executeEdits("dm-connect-normalize-schema-dot", [{
        range: { startLineNumber: position.lineNumber, startColumn: position.column - 1, endLineNumber: position.lineNumber, endColumn: position.column },
        text: "."
      }]);
      instance.trigger("dm-connect-schema-autocomplete", "editor.action.triggerSuggest", undefined);
    });
    instance.addAction({
      id: "dm-connect.execute-current",
      label: "执行当前 SQL",
      keybindings: [monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter],
      run: () => executeCurrentRef.current()
    });
    instance.addAction({
      id: "dm-connect.undo-sql",
      label: "撤销 SQL 编辑",
      keybindings: [monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyZ],
      run: () => instance.trigger("keyboard", "undo", null)
    });
    instance.addAction({
      id: "dm-connect.redo-sql",
      label: "重做 SQL 编辑",
      keybindings: [monaco.KeyMod.CtrlCmd | monaco.KeyMod.Shift | monaco.KeyCode.KeyZ],
      run: () => instance.trigger("keyboard", "redo", null)
    });
    if (suppressInitialSuggestRef.current) {
      // 从历史记录恢复时不自动聚焦编辑器；Monaco 会把首次 focus 视为输入上下文，
      // 并在异步阶段弹出基于末尾单词的建议。用户点击编辑器后，仍使用正常自动联想。
      window.setTimeout(() => {
        instance.trigger("dm-connect-history-initial-focus", "hideSuggestWidget", undefined);
      }, 0);
    } else {
      instance.focus();
    }
  };

  function executionPayload(mode: ExecuteMode, allowDangerous: boolean) {
    const instance = editorRef.current;
    const model = instance?.getModel();
    const selection = instance?.getSelection();
    return {
      sessionId,
      sql,
      mode,
      selection: model && selection ? model.getValueInRange(selection) : "",
      caretOffset: model && instance?.getPosition() ? model.getOffsetAt(instance.getPosition()!) : 0,
      maxRows,
      allowDangerous
    };
  }

  function simpleTable(sqlText: string): DatabaseObject | null {
    const match = sqlText.trim().match(/^SELECT\s+\*\s+FROM\s+([A-Za-z_][A-Za-z0-9_$]*)\.([A-Za-z_][A-Za-z0-9_$]*)\s*;?$/i);
    return match ? { schema: match[1], name: match[2], kind: "TABLE", remarks: "" } : null;
  }

  async function execute(mode: ExecuteMode, allowDangerous = false) {
    const source = simpleTable(sql);
    setTablePreview(null);
    setBusy(true);
    setMessage("正在执行 SQL…");
    try {
      const result = await rpc<ExecutionResult>("query.execute", executionPayload(mode, allowDangerous));
      setExecution(result);
      setActiveOutcome(0);
      setAutoCommitState(result.autoCommit);
      setPendingTransaction(result.pendingTransaction);
      setMessage(result.success
        ? `执行完成 · ${result.executedStatements} 条语句 · ${result.durationMillis} ms${result.historyWarning ? ` · 历史未保存：${result.historyWarning}` : ""}`
        : `执行失败 · ${result.errorMessage}`);
      if (result.success && source) {
        void rpc<ObjectLoadResult>("object.load", { profileId, ...source }).then(result => {
          setTablePreview(result);
          if (result.details) {
            const current = suggestionsRef.current;
            const key = `${source.schema.toLowerCase()}.${source.name.toLowerCase()}`;
            suggestionsRef.current = {
              ...current,
              columns: [...current.columns.filter(column => `${column.schema.toLowerCase()}.${column.table.toLowerCase()}` !== key),
                ...result.details.columns.map(column => ({ schema: source.schema, table: source.name, name: column.name, typeName: column.typeName, remarks: column.remarks }))]
            };
          }
        })
          .catch(cause => setMessage(`读取表预览失败：${errorMessage(cause)}`));
      }
    } catch (cause) {
      const error = asRpcError(cause);
      if (error.code === "DANGEROUS_SQL") {
        setDangerous({ statements: Array.isArray(error.data) ? error.data as string[] : [], mode });
        setMessage("已暂停危险 SQL，等待确认");
      } else {
        setMessage(errorMessage(cause));
      }
    } finally {
      setBusy(false);
    }
  }

  executeCurrentRef.current = () => void execute("CURRENT_STATEMENT");

  async function cancel() {
    try {
      setMessage("正在取消查询…");
      await rpc("query.cancel", { sessionId });
    } catch (cause) {
      setMessage(`取消失败：${errorMessage(cause)}`);
    }
  }

  async function applyAutoCommit(value: boolean) {
    try {
      const status = await rpc<QueryStatus>("query.autoCommit", { sessionId, autoCommit: value });
      setAutoCommitState(status.autoCommit);
      setPendingTransaction(status.pendingTransaction);
      setMessage(status.autoCommit ? "已启用自动提交" : "已切换到手动事务");
    } catch (cause) {
      setMessage(`切换自动提交失败：${errorMessage(cause)}`);
    }
  }

  function toggleAutoCommit(value: boolean) {
    if (value && pendingTransaction) setConfirmAutoCommit(true);
    else void applyAutoCommit(value);
  }

  async function transaction(commit: boolean) {
    try {
      const status = await rpc<QueryStatus>(commit ? "query.commit" : "query.rollback", { sessionId });
      setPendingTransaction(status.pendingTransaction);
      setMessage(commit ? "事务已提交" : "事务已回滚");
    } catch (cause) {
      setMessage(`事务操作失败：${errorMessage(cause)}`);
    }
  }

  async function exportResult(outcome: StatementOutcome) {
    if (!outcome.resultId) return;
    const path = await window.dmConnect.saveCsv("query-result.csv");
    if (!path) return;
    try {
      await rpc("csv.export", { sessionId, resultId: outcome.resultId, path });
      setMessage(`已导出 CSV：${path}`);
    } catch (cause) {
      setMessage(`导出失败：${errorMessage(cause)}`);
    }
  }

  async function loadTablePreview(object: DatabaseObject, page: number, filter: { column: string; operator: "=" | "LIKE"; value: string }[] | null): Promise<PagedResultTable> {
    return rpc<PagedResultTable>("object.preview", { profileId, ...object, page, pageSize: 100, ...(filter ? { filters: filter } : {}) });
  }

  async function saveTableChanges(object: DatabaseObject, changes: { column: string; value: string | null; keyValues: Record<string, unknown> }[]) {
    await rpc("object.updateCells", { profileId, ...object, changes });
    setMessage(`已保存 ${changes.length} 项修改`);
  }

  async function exportTableInsert(object: DatabaseObject, scope: "CURRENT_PAGE" | "ALL", page: number, filter: { column: string; operator: "=" | "LIKE"; value: string }[] | null) {
    const path = await window.dmConnect.saveSql();
    if (!path) return;
    const result = await rpc<{ rows: number }>("table.exportInsert", { profileId, ...object, path, scope, page, pageSize: 100, ...(filter ? { filters: filter } : {}) });
    setMessage(`已导出 ${result.rows} 条 INSERT 语句`);
  }
  async function exportTableCsv(object: DatabaseObject, scope: "CURRENT_PAGE" | "ALL", page: number, filter: { column: string; operator: "=" | "LIKE"; value: string }[] | null) {
    const path = await window.dmConnect.saveCsv(`${object.name}.csv`);
    if (!path) return;
    const result = await rpc<{ rows: number }>("table.exportCsv", { profileId, ...object, path, scope, page, pageSize: 100, ...(filter ? { filters: filter } : {}) });
    setMessage(`已导出 ${result.rows} 行 CSV`);
  }

  const selected = execution?.outcomes[activeOutcome] ?? null;

  return (
    <section ref={workspaceRef} className={`sql-workspace${resizingResults ? " sql-resizing" : ""}`} style={{ "--sql-editor-height": `${editorHeight}px` } as React.CSSProperties}>
      <header className="sql-toolbar">
        <div className="toolbar-cluster run-actions">
          <button className="button run" onClick={() => execute("CURRENT_STATEMENT")} disabled={busy} title="执行光标所在语句"><Play size={15} fill="currentColor" />执行当前</button>
          <button className="button toolbar-button" onClick={() => execute("SELECTION")} disabled={busy}><PlayCircle size={15} />执行选中</button>
          <button className="button toolbar-button" onClick={() => execute("SCRIPT")} disabled={busy}><TerminalSquare size={15} />执行脚本</button>
          {busy && <button className="button stop" onClick={cancel}><CircleStop size={15} />取消</button>}
        </div>
        <div className="toolbar-spacer" />
        <div className="toolbar-cluster transaction-actions">
          <label className="switch-label"><input type="checkbox" checked={autoCommit} onChange={event => toggleAutoCommit(event.target.checked)} /><span className="switch" /><em>自动提交</em></label>
          <button className="button toolbar-button" disabled={autoCommit || !pendingTransaction} onClick={() => transaction(true)}><CheckCheck size={14} />提交</button>
          <button className="button toolbar-button" disabled={autoCommit || !pendingTransaction} onClick={() => transaction(false)}><RotateCcw size={14} />回滚</button>
        </div>
        <span className="row-limit fixed"><span>结果上限</span><strong>100 行</strong></span>
      </header>

      <div className="sql-editor-pane">
        <div className="editor-context"><span className="connection-badge"><span />{profileName}</span><span className="editor-language">{dialect.label}</span></div>
        <Editor
          height="100%"
          language="sql"
          theme="dm-connect-light"
          value={sql}
          onChange={value => setSql(value ?? "")}
          onMount={onMount}
          options={{
            automaticLayout: true,
            minimap: { enabled: false },
            fontFamily: "SFMono-Regular, Menlo, Monaco, Consolas, monospace",
            fontSize: 15,
            lineHeight: 24,
            padding: { top: 18, bottom: 18 },
            scrollBeyondLastLine: false,
            smoothScrolling: true,
            cursorSmoothCaretAnimation: "on",
            renderLineHighlight: "all",
            overviewRulerBorder: false,
            overviewRulerLanes: 0,
            folding: true,
            wordWrap: "off",
            tabSize: 4,
            insertSpaces: true,
            suggest: { showKeywords: true, showSnippets: true }
          }}
        />
      </div>

      <div className="sql-results-resizer" role="separator" aria-orientation="horizontal" aria-label="调整 SQL 编辑和结果区域高度" onPointerDown={event => { event.preventDefault(); setResizingResults(true); }} />

      <div className="sql-results-pane">
        <div className="result-tabbar">
          <div className="result-tabs-list">
            {!execution && <button className="active"><Rows3 size={14} />执行结果</button>}
            {execution?.outcomes.map((outcome, index) => <button key={`${outcome.statementIndex}-${outcome.resultIndex}`} className={activeOutcome === index ? "active" : ""} onClick={() => setActiveOutcome(index)}>
              {outcome.table ? <Rows3 size={14} /> : <Save size={14} />}{outcome.table ? `结果 ${index + 1}` : `更新 ${outcome.statementIndex}`}
            </button>)}
            {execution && !execution.success && <button className={!selected ? "active error" : "error"} onClick={() => setActiveOutcome(-1)}><AlertTriangle size={14} />错误</button>}
          </div>
        </div>
        <div className="result-content">
          {!execution && <div className="result-placeholder"><span><TerminalSquare size={28} /></span><strong>准备执行 SQL</strong><p>使用“执行当前”运行光标所在语句，或选择一段 SQL 后执行。</p></div>}
          {execution && activeOutcome === -1 && <div className="query-error"><AlertTriangle size={25} /><div><strong>{execution.errorMessage || "SQL 执行失败"}</strong><p>SQLState：{execution.sqlState || "—"}　错误码：{execution.vendorCode}</p></div></div>}
          {selected?.table && (tablePreview ? <ObjectView compact result={tablePreview} onLoadPreview={(page, filter) => loadTablePreview(tablePreview.object, page, filter)} onSaveChanges={changes => saveTableChanges(tablePreview.object, changes)} onExportInsert={(scope, page, filter) => exportTableInsert(tablePreview.object, scope, page, filter)} onExportCsv={(scope, page, filter) => exportTableCsv(tablePreview.object, scope, page, filter)} /> : <DataGrid table={selected.table} />)}
          {selected && !selected.table && <div className="update-result"><CheckCheck size={28} /><strong>语句执行成功</strong><span>影响 {selected.updateCount ?? 0} 行</span></div>}
          {execution && execution.outcomes.length === 0 && execution.success && <div className="update-result"><CheckCheck size={28} /><strong>执行完成</strong><span>数据库未返回结果集或更新计数</span></div>}
        </div>
      </div>

      <footer className="sql-statusbar"><span className={busy ? "busy" : execution?.success === false ? "error" : "ok"}>{busy ? <LoaderCircle className="spin" size={12} /> : execution?.success === false ? <Ban size={12} /> : <CheckCheck size={12} />}</span><span>{message}</span><span className="status-spacer" /><Clock3 size={12} /><span>{autoCommit ? "自动提交" : pendingTransaction ? "存在未提交事务" : "手动事务"}</span></footer>

      {dangerous && <ConfirmModal
        title="确认执行危险 SQL"
        danger
        confirmText="仍然执行"
        onCancel={() => setDangerous(null)}
        onConfirm={() => { const current = dangerous; setDangerous(null); void execute(current.mode, true); }}
        message={<><p>检测到 DROP 或 TRUNCATE 语句，执行后可能无法恢复：</p><div className="dangerous-list">{dangerous.statements.slice(0, 5).map((statement, index) => <code key={index}>{statement.replace(/\s+/g, " ").slice(0, 180)}</code>)}</div></>}
      />}
      {confirmAutoCommit && <ConfirmModal title="启用自动提交" message="启用自动提交会提交当前事务。确认继续吗？" confirmText="提交并启用" onCancel={() => setConfirmAutoCommit(false)} onConfirm={() => { setConfirmAutoCommit(false); void applyAutoCommit(true); }} />}
    </section>
  );
}
