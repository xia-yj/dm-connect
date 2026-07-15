import { loader } from "@monaco-editor/react";
import * as monaco from "monaco-editor";
import EditorWorker from "monaco-editor/esm/vs/editor/editor.worker?worker";
import "monaco-editor/esm/vs/basic-languages/sql/sql.contribution.js";

self.MonacoEnvironment = {
  getWorker: () => new EditorWorker()
};

loader.config({ monaco });

monaco.editor.defineTheme("dm-connect-light", {
  base: "vs",
  inherit: true,
  rules: [
    { token: "keyword", foreground: "6D28D9", fontStyle: "bold" },
    { token: "string", foreground: "047857" },
    { token: "comment", foreground: "8994A5", fontStyle: "italic" },
    { token: "number", foreground: "C2410C" }
  ],
  colors: {
    "editor.background": "#FFFFFF",
    "editor.foreground": "#263247",
    "editorLineNumber.foreground": "#A1ABBA",
    "editorLineNumber.activeForeground": "#4D5B70",
    "editor.lineHighlightBackground": "#F7F9FC",
    "editor.selectionBackground": "#CFE0FF",
    "editorCursor.foreground": "#2563EB",
    "editorGutter.background": "#F8FAFD"
  }
});
