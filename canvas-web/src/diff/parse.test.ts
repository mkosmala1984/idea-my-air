import { describe, expect, it } from "vitest";
import { openLine, parseDiff, splitRows } from "./parse";

const GIT = String.raw`diff --git a/src/a.ts b/src/a.ts
index 1111111..2222222 100644
--- a/src/a.ts
+++ b/src/a.ts
@@ -10,4 +10,5 @@ function f() {
 const x = 1;
-const y = 2;
+const y = 3;
+const z = 4;
 return x;
-- a deleted line starting with dashes
diff --git a/new.md b/new.md
new file mode 100644
index 0000000..3333333
--- /dev/null
+++ b/new.md
@@ -0,0 +1,2 @@
+# Title
+text
\ No newline at end of file
diff --git a/old.txt b/old.txt
deleted file mode 100644
--- a/old.txt
+++ /dev/null
@@ -1 +0,0 @@
-gone
diff --git a/x.kt b/y.kt
similarity index 90%
rename from x.kt
rename to y.kt
diff --git a/logo.png b/logo.png
Binary files a/logo.png and b/logo.png differ
`;

describe("parseDiff", () => {
  const files = parseDiff(GIT);

  it("reads every file with its status and counts", () => {
    expect(files.map((f) => [f.path, f.status, f.additions, f.deletions, f.binary])).toEqual([
      ["src/a.ts", "modified", 2, 2, false],
      ["new.md", "added", 2, 0, false],
      ["old.txt", "deleted", 0, 1, false],
      ["y.kt", "renamed", 0, 0, false],
      ["logo.png", "modified", 0, 0, true],
    ]);
    expect(files[3].oldPath).toBe("x.kt");
  });

  it("numbers the lines of both files from the hunk header, with the section", () => {
    const [h] = files[0].hunks;
    expect(h.section).toBe("function f() {");
    expect(h.lines.map((l) => [l.kind, l.old ?? null, l.new ?? null])).toEqual([
      ["context", 10, 10], ["del", 11, null], ["add", null, 11], ["add", null, 12], ["context", 12, 13], ["del", 13, null],
    ]);
    expect(h.lines[5].text).toBe("- a deleted line starting with dashes");
    expect(files[1].hunks[0].lines.at(-1)).toEqual({ kind: "note", text: "No newline at end of file" });
  });

  it("reads a plain unified diff without git headers, CRLF and wrong counts included", () => {
    const plain = parseDiff("--- a/f.txt\r\n+++ b/f.txt\r\n@@ -1,1 +1,1 @@\r\n one\r\n-two\r\n+TWO\r\n three\r\n");
    expect(plain.map((f) => [f.path, f.additions, f.deletions])).toEqual([["f.txt", 1, 1]]);
    expect(plain[0].hunks[0].lines.at(-1)).toMatchObject({ kind: "context", old: 3, new: 3 });
  });

  it("reads a bare hunk, and nothing from text that is no diff", () => {
    expect(parseDiff("@@ -5 +5 @@\n-a\n+b").map((f) => [f.path, f.hunks[0].lines.length])).toEqual([["", 2]]);
    expect(parseDiff("just some text\nmore")).toEqual([]);
  });
});

describe("splitRows and openLine", () => {
  const [h] = parseDiff(GIT)[0].hunks;

  it("pairs a run of deletions with the additions after it", () => {
    expect(splitRows(h.lines).map((r) => [r.left?.kind ?? null, r.right?.kind ?? null])).toEqual([
      ["context", "context"], ["del", "add"], [null, "add"], ["context", "context"], ["del", null],
    ]);
  });

  it("opens a deleted line at the next line of the new file", () => {
    expect(openLine(h, h.lines[1])).toBe(11);
    expect(openLine(h, h.lines[3])).toBe(12);
    expect(openLine(h, h.lines[5])).toBe(14);
  });
});
