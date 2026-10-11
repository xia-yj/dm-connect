import { useEffect, useState } from "react";
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
    { token: "keyword", foreground: "8A3FD1", fontStyle: "bold" },
    { token: "string", foreground: "127A45" },
    { token: "comment", foreground: "8E8E96", fontStyle: "italic" },
    { token: "number", foreground: "A85D06" },
    { token: "predefined", foreground: "0B6E99" }
  ],
  colors: {
    "editor.background": "#FFFFFF",
    "editor.foreground": "#1D1D1F",
    "editorLineNumber.foreground": "#B4B4BB",
    "editorLineNumber.activeForeground": "#4A4A50",
    "editor.lineHighlightBackground": "#F6F6F8",
    "editor.lineHighlightBorder": "#00000000",
    "editor.selectionBackground": "#CFE0FA",
    "editorCursor.foreground": "#0A64D6",
    "editorGutter.background": "#FFFFFF"
  }
});

monaco.editor.defineTheme("dm-connect-dark", {
  base: "vs-dark",
  inherit: true,
  rules: [
    { token: "keyword", foreground: "C99BFF", fontStyle: "bold" },
    { token: "string", foreground: "7FD69C" },
    { token: "comment", foreground: "7A7A82", fontStyle: "italic" },
    { token: "number", foreground: "F2B867" },
    { token: "predefined", foreground: "6CC4E6" }
  ],
  colors: {
    "editor.background": "#1C1C1E",
    "editor.foreground": "#ECECEF",
    "editorLineNumber.foreground": "#5C5C63",
    "editorLineNumber.activeForeground": "#C4C4CA",
    "editor.lineHighlightBackground": "#242427",
    "editor.lineHighlightBorder": "#00000000",
    "editor.selectionBackground": "#2B4A78",
    "editorCursor.foreground": "#78ADFF",
    "editorGutter.background": "#1C1C1E"
  }
});

const darkScheme = "(prefers-color-scheme: dark)";

export function useMonacoTheme(): string {
  const [dark, setDark] = useState(() => window.matchMedia(darkScheme).matches);
  useEffect(() => {
    const query = window.matchMedia(darkScheme);
    const update = () => setDark(query.matches);
    query.addEventListener("change", update);
    return () => query.removeEventListener("change", update);
  }, []);
  return dark ? "dm-connect-dark" : "dm-connect-light";
}
