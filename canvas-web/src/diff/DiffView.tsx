// The Diff component's view: every file of a unified diff, unified or side by side, with line numbers that open the
// file in the IDE and a comment box on any line whose text goes to the canvas's manager with the file and line.
import { Fragment, useMemo, useState, type ReactNode } from "react";
import { useTriggerAction } from "@openuidev/react-lang";
import { openLine, parseDiff, splitRows, type DiffFile, type DiffHunk, type DiffLine } from "./parse";

export interface DiffProps {
  patch: string;
  view?: "unified" | "split";
  /** Whether files start collapsed beyond this many; default: all open. */
  collapseAfter?: number;
}

type Trigger = ReturnType<typeof useTriggerAction>;

const openFile = (trigger: Trigger, path: string, line?: number) =>
  trigger("", undefined, { steps: [{ type: "custom_action", name: "OpenFile", params: line != null ? { path, line } : { path } }] });

/** Where a comment is being written: file, hunk and line. */
type Target = { file: string; hunk: number; line: DiffLine };

/** The message and context a line comment sends (`@ToAssistant`). */
export function commentAction(path: string, line: DiffLine, at: number, comment: string) {
  const side = line.kind === "del" ? "old" : "new";
  return {
    message: `${path}:${at} ${comment}`,
    context: { file: path, line: at, side, kind: line.kind, code: line.text, comment },
  };
}

export function DiffView({ props }: { props: DiffProps }) {
  const trigger = useTriggerAction();
  // Parsed once per patch: a comment's target is a line object, compared by identity.
  const files = useMemo(() => parseDiff(props.patch ?? ""), [props.patch]);
  const [view, setView] = useState<"unified" | "split">(props.view ?? "unified");
  const [target, setTarget] = useState<Target | null>(null);
  if (files.length === 0)
    return <div className="myair-diff-empty">Diff: the patch holds no file header and no @@ hunk, so there is nothing to show.</div>;
  const additions = files.reduce((a, f) => a + f.additions, 0);
  const deletions = files.reduce((a, f) => a + f.deletions, 0);
  return (
    <div className="myair-diff">
      <div className="myair-diff-bar">
        <span>{files.length} {files.length === 1 ? "file" : "files"}</span>
        <span className="myair-diff-add">+{additions}</span>
        <span className="myair-diff-del">−{deletions}</span>
        <span className="myair-diff-spacer" />
        <div className="myair-diff-toggle" role="group" aria-label="Diff view">
          {(["unified", "split"] as const).map((v) => (
            <button key={v} type="button" aria-pressed={view === v} onClick={() => setView(v)}>{v === "unified" ? "Unified" : "Split"}</button>
          ))}
        </div>
      </div>
      {files.map((file, i) => (
        <FileView key={`${file.path}#${i}`} file={file} view={view} open={props.collapseAfter == null || i < props.collapseAfter}
          trigger={trigger} target={target?.file === `${i}` ? target : null}
          onComment={(hunk, line) => setTarget({ file: `${i}`, hunk, line })} onDone={() => setTarget(null)} />
      ))}
    </div>
  );
}

const STATUS: Record<DiffFile["status"], string> = { modified: "", added: "new", deleted: "deleted", renamed: "renamed" };

function FileView(p: {
  file: DiffFile; view: "unified" | "split"; open: boolean; trigger: Trigger;
  target: Target | null; onComment: (hunk: number, line: DiffLine) => void; onDone: () => void;
}) {
  const { file } = p;
  const first = file.hunks[0];
  return (
    <details className="myair-diff-file" open={p.open}>
      <summary>
        <button type="button" className="myair-fileref" title={`Open ${file.path}`} disabled={file.status === "deleted"}
          onClick={(e) => (e.preventDefault(), openFile(p.trigger, file.path, first ? Math.max(1, first.newStart) : undefined))}>
          {file.status === "renamed" && file.oldPath ? `${file.oldPath} → ${file.path}` : file.path}
        </button>
        {STATUS[file.status] ? <span className={`myair-diff-status myair-diff-status-${file.status}`}>{STATUS[file.status]}</span> : null}
        <span className="myair-diff-add">+{file.additions}</span>
        <span className="myair-diff-del">−{file.deletions}</span>
      </summary>
      {file.binary ? <div className="myair-diff-empty">Binary file</div> : null}
      <table className={`myair-diff-table myair-diff-${p.view}`}>
        {/* A fixed layout takes the widths from here, not from the first row (a hunk header spanning every column). */}
        <colgroup>
          {p.view === "split"
            ? [<col key="a" className="myair-diff-col-num" />, <col key="b" />, <col key="c" className="myair-diff-col-num" />, <col key="d" />]
            : [<col key="a" className="myair-diff-col-num" />, <col key="b" className="myair-diff-col-num" />, <col key="c" />]}
        </colgroup>
        <tbody>
          {file.hunks.map((hunk, h) => (
            <Fragment key={h}>
              <tr className="myair-diff-hunk">
                <td colSpan={p.view === "split" ? 4 : 3}>@@ −{hunk.oldStart} +{hunk.newStart} @@ {hunk.section}</td>
              </tr>
              {p.view === "split" ? splitView(p, hunk, h) : unifiedView(p, hunk, h)}
            </Fragment>
          ))}
        </tbody>
      </table>
    </details>
  );
}

type FileViewProps = Parameters<typeof FileView>[0];

/** The gutter button that opens the line in the IDE, and the + that starts a comment on it. */
function gutter(p: FileViewProps, hunk: DiffHunk, h: number, line: DiffLine | undefined, number: number | undefined, side: string) {
  if (!line || line.kind === "note") return <td className="myair-diff-num" />;
  const deleted = p.file.status === "deleted";
  return (
    <td className={`myair-diff-num myair-diff-num-${side}`}>
      <button type="button" className="myair-diff-comment-btn" title="Comment on this line" aria-label="Comment on this line"
        onClick={() => p.onComment(h, line)}>+</button>
      <button type="button" className="myair-diff-num-btn" disabled={deleted} title={deleted ? undefined : "Open in the IDE"}
        onClick={() => openFile(p.trigger, p.file.path, openLine(hunk, line))}>{number ?? ""}</button>
    </td>
  );
}

const MARK: Record<DiffLine["kind"], string> = { add: "+", del: "−", context: " ", note: "\\" };

function code(line: DiffLine | undefined) {
  if (!line) return <td className="myair-diff-code myair-diff-blank" />;
  return <td className={`myair-diff-code myair-diff-${line.kind}`}><span className="myair-diff-mark">{MARK[line.kind]}</span>{line.text || " "}</td>;
}

function unifiedView(p: FileViewProps, hunk: DiffHunk, h: number): ReactNode[] {
  return hunk.lines.flatMap((line, i) => {
    const row = (
      <tr key={i} className={`myair-diff-row-${line.kind}`}>
        {gutter(p, hunk, h, line, line.old, "old")}
        {gutter(p, hunk, h, line, line.new, "new")}
        {code(line)}
      </tr>
    );
    return isTarget(p, h, line) ? [row, commentRow(p, hunk, line, `c${i}`, 3)] : [row];
  });
}

function splitView(p: FileViewProps, hunk: DiffHunk, h: number): ReactNode[] {
  return splitRows(hunk.lines).flatMap((row, i) => {
    const out = [
      <tr key={i}>
        {gutter(p, hunk, h, row.left, row.left?.old, "old")}
        {code(row.left)}
        {gutter(p, hunk, h, row.right, row.right?.new, "new")}
        {code(row.right)}
      </tr>,
    ];
    const hit = [row.left, row.right].find((l) => l && isTarget(p, h, l));
    if (hit) out.push(commentRow(p, hunk, hit, `c${i}`, 4));
    return out;
  });
}

const isTarget = (p: FileViewProps, h: number, line: DiffLine) => p.target != null && p.target.hunk === h && p.target.line === line;

function commentRow(p: FileViewProps, hunk: DiffHunk, line: DiffLine, key: string, span: number) {
  return (
    <tr key={key} className="myair-diff-comment-row">
      <td colSpan={span}>
        <CommentBox at={line.old != null && line.kind === "del" ? line.old : openLine(hunk, line)} line={line} path={p.file.path}
          trigger={p.trigger} onDone={p.onDone} />
      </td>
    </tr>
  );
}

function CommentBox(p: { path: string; line: DiffLine; at: number; trigger: Trigger; onDone: () => void }) {
  const [text, setText] = useState("");
  const send = () => {
    if (!text.trim()) return;
    const { message, context } = commentAction(p.path, p.line, p.at, text.trim());
    p.trigger(message, undefined, { steps: [{ type: "continue_conversation", message, context }] });
    p.onDone();
  };
  return (
    <div className="myair-diff-comment">
      <div className="myair-diff-comment-where">{p.path}:{p.at}{p.line.kind === "del" ? " (old)" : ""}</div>
      <textarea autoFocus rows={3} value={text} placeholder="Comment for the agent; Ctrl+Enter sends"
        onChange={(e) => setText(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) send();
          if (e.key === "Escape") p.onDone();
        }} />
      <div className="myair-diff-comment-buttons">
        <button type="button" className="myair-diff-send" disabled={!text.trim()} onClick={send}>Send to agent</button>
        <button type="button" onClick={p.onDone}>Cancel</button>
      </div>
    </div>
  );
}
