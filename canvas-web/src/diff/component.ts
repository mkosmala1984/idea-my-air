// The Diff OpenUI component: a unified diff's text in, files with line numbers, a view toggle and line comments out.
import { createElement } from "react";
import { z } from "zod/v4";
import { defineComponent } from "@openuidev/react-lang";
import { DiffView } from "./DiffView";

export const Diff = defineComponent({
  name: "Diff",
  props: z.object({
    patch: z.string(),
    view: z.enum(["unified", "split"]).optional(),
    collapseAfter: z.number().int().min(0).optional(),
  }),
  description:
    "A code diff from unified diff text, exactly as `git diff` or `git show` prints it (several files, hunks with @@ headers; " +
    "a/ b/ prefixes, new, deleted and renamed files understood; hunk counts may be approximate). view 'unified' (default) or " +
    "'split'; the user can switch. collapseAfter: files after this many start collapsed. A click on a path or line number " +
    "opens the file in the IDE; the + beside a line lets the user send you a comment on it, as 'path:line comment' with " +
    "context {file, line, side, kind, code, comment}. Put the explanation next to it (TextContent, Steps), not into the patch.",
  component: ({ props }) => createElement(DiffView, { props }),
});
