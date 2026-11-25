// Unified diff text (what `git diff` or `git show` prints, or a hand-written one) as files, hunks and numbered lines.
// Pure and lenient: an agent's diff with wrong hunk counts still reads, line numbers are recomputed from the headers.

export type LineKind = "context" | "add" | "del" | "note";

export interface DiffLine {
  kind: LineKind;
  text: string;
  /** The line's number in the old file (context and deleted lines). */
  old?: number;
  /** The line's number in the new file (context and added lines). */
  new?: number;
}

export interface DiffHunk {
  oldStart: number;
  newStart: number;
  /** What follows the second @@: usually the enclosing function. */
  section: string;
  lines: DiffLine[];
}

export type FileStatus = "modified" | "added" | "deleted" | "renamed";

export interface DiffFile {
  oldPath: string | null;
  newPath: string | null;
  /** The path to show and open: the new one, else the old one (a deleted file). */
  path: string;
  status: FileStatus;
  binary: boolean;
  hunks: DiffHunk[];
  additions: number;
  deletions: number;
}

const HUNK = /^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@ ?(.*)$/;

/** A diff path without git's a/ or b/ prefix; null for /dev/null. */
function cleanPath(raw: string): string | null {
  const path = raw.replace(/\t.*$/, "").trim().replace(/^"(.*)"$/, "$1");
  if (path === "/dev/null") return null;
  return path.replace(/^[ab]\//, "");
}

function newFile(oldPath: string | null = null, newPath: string | null = null): DiffFile {
  return { oldPath, newPath, path: "", status: "modified", binary: false, hunks: [], additions: 0, deletions: 0 };
}

function finish(file: DiffFile): DiffFile {
  file.path = file.newPath ?? file.oldPath ?? "";
  if (file.oldPath == null && file.newPath != null) file.status = "added";
  else if (file.newPath == null && file.oldPath != null) file.status = "deleted";
  else if (file.status === "modified" && file.oldPath !== file.newPath) file.status = "renamed";
  for (const h of file.hunks)
    for (const l of h.lines) {
      if (l.kind === "add") file.additions++;
      if (l.kind === "del") file.deletions++;
    }
  return file;
}

/** The files of [text], in order; text that holds no file header and no hunk gives none. */
export function parseDiff(text: string): DiffFile[] {
  const lines = text.replace(/\r\n?/g, "\n").split("\n");
  if (lines.at(-1) === "") lines.pop();
  const files: DiffFile[] = [];
  let file: DiffFile | null = null;
  let hunk: DiffHunk | null = null;
  let oldLine = 0;
  let newLine = 0;
  const start = (f: DiffFile) => {
    if (file) files.push(finish(file));
    file = f;
    hunk = null;
  };
  // "--- x" followed by "+++ y" starts a file's paths, also inside what could be a hunk.
  const isPaths = (i: number) => lines[i].startsWith("--- ") && lines[i + 1]?.startsWith("+++ ");

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (line.startsWith("diff --git ")) {
      const m = /^diff --git (?:"?a\/)?(.+?)"? (?:"?b\/)?(.+?)"?$/.exec(line);
      start(newFile(m ? m[1] : null, m ? m[2] : null));
      continue;
    }
    if (isPaths(i)) {
      const oldPath = cleanPath(line.slice(4));
      const newPath = cleanPath(lines[i + 1].slice(4));
      // A git header already opened this file; a plain unified diff opens one here.
      const current = file as DiffFile | null;
      if (current && current.hunks.length === 0 && hunk == null) {
        current.oldPath = oldPath;
        current.newPath = newPath;
      } else {
        start(newFile(oldPath, newPath));
      }
      i++;
      continue;
    }
    const h = HUNK.exec(line);
    if (h) {
      if (!file) start(newFile());
      oldLine = Number(h[1]);
      newLine = Number(h[2]);
      hunk = { oldStart: oldLine, newStart: newLine, section: h[3].trim(), lines: [] };
      file!.hunks.push(hunk);
      continue;
    }
    const current = file as DiffFile | null;
    const inHunk = hunk as DiffHunk | null;
    if (inHunk) {
      const mark = line[0];
      if (mark === "+") inHunk.lines.push({ kind: "add", text: line.slice(1), new: newLine++ });
      else if (mark === "-") inHunk.lines.push({ kind: "del", text: line.slice(1), old: oldLine++ });
      else if (mark === " " || line === "") inHunk.lines.push({ kind: "context", text: line.slice(1), old: oldLine++, new: newLine++ });
      else if (mark === "\\") inHunk.lines.push({ kind: "note", text: line.slice(1).trim() });
      else hunk = null;
      if (hunk) continue;
    }
    if (!current) continue;
    if (line.startsWith("new file mode")) current.oldPath = null;
    else if (line.startsWith("deleted file mode")) current.newPath = null;
    else if (line.startsWith("rename from ")) { current.oldPath = line.slice(12); current.status = "renamed"; }
    else if (line.startsWith("rename to ")) { current.newPath = line.slice(10); current.status = "renamed"; }
    else if (/^Binary files .* differ$/.test(line) || line === "GIT binary patch") current.binary = true;
  }
  if (file) files.push(finish(file));
  return files;
}

/** One row of the side-by-side view: the old line on the left, the new on the right, either may be missing. */
export interface SplitRow {
  left?: DiffLine;
  right?: DiffLine;
}

/** A hunk's lines side by side: context on both sides, a run of deletions paired with the run of additions after it. */
export function splitRows(lines: DiffLine[]): SplitRow[] {
  const rows: SplitRow[] = [];
  for (let i = 0; i < lines.length; ) {
    const l = lines[i];
    if (l.kind === "context" || l.kind === "note") {
      rows.push({ left: l, right: l });
      i++;
      continue;
    }
    const dels: DiffLine[] = [];
    const adds: DiffLine[] = [];
    while (lines[i]?.kind === "del") dels.push(lines[i++]);
    while (lines[i]?.kind === "add") adds.push(lines[i++]);
    for (let k = 0; k < Math.max(dels.length, adds.length); k++) rows.push({ left: dels[k], right: adds[k] });
  }
  return rows;
}

/** The line of the new file a click on [line] opens: its own, else (a deleted line) the next new line after it. */
export function openLine(hunk: DiffHunk, line: DiffLine): number {
  if (line.new != null) return line.new;
  const index = hunk.lines.indexOf(line);
  for (let i = index; i < hunk.lines.length; i++) if (hunk.lines[i].new != null) return hunk.lines[i].new!;
  for (let i = index; i >= 0; i--) if (hunk.lines[i].new != null) return hunk.lines[i].new! + 1;
  return Math.max(1, hunk.newStart);
}
