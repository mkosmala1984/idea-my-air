// The canvas's component library: OpenUI's prebuilt openuiLibrary (root Stack) plus My Air's own components and actions.
// The bundle renders with it and scripts/prompt.ts writes its system prompt, so the two cannot drift apart.
import { createElement } from "react";
import { z } from "zod/v4";
import { defineAction, defineComponent, useTriggerAction } from "@openuidev/react-lang";
import { openuiLibrary, openuiPromptOptions } from "@openuidev/react-ui/genui-lib";
import { Board, Edge, Frame, Note, Shape } from "./board/components";

/** `@OpenFile(path, line?)`: the plugin opens the file in the IDE's editor (`CanvasCommand.OpenFile`). */
export const OpenFile = defineAction({
  name: "OpenFile",
  description:
    "Opens a file in the IDE's editor, at the given 1-based line when there is one. " +
    "The path is relative to the canvas's working directory, or absolute; files outside the project are refused.",
  params: z.object({
    path: z.string(),
    line: z.number().int().positive().optional(),
  }),
});

/** `@CopyToClipboard(text)`: the plugin copies the text to the system clipboard (`CanvasCommand.Copy`). */
export const CopyToClipboard = defineAction({
  name: "CopyToClipboard",
  description: "Copies the given text to the user's clipboard, e.g. a command, a path or a snippet the user may want to paste.",
  params: z.object({ text: z.string() }),
});

const FileRefProps = z.object({
  path: z.string(),
  line: z.number().int().positive().optional(),
  label: z.string().optional(),
});

/** What a FileRef shows: its label, else `path:line`, else the path. */
export function fileRefText(props: z.infer<typeof FileRefProps>): string {
  return props.label ?? (props.line != null ? `${props.path}:${props.line}` : props.path);
}

/** `FileRef(path, line?, label?)`: a link to a file of the project; a click opens it through the OpenFile action. */
export const FileRef = defineComponent({
  name: "FileRef",
  props: FileRefProps,
  description:
    "A link to a file of the project, shown as the label or as path:line; a click opens the file in the IDE at that line " +
    "(same as @OpenFile). Use it wherever a finding points at code.",
  component: ({ props }) => {
    const triggerAction = useTriggerAction();
    const params: Record<string, unknown> = { path: props.path };
    if (props.line != null) params.line = props.line;
    return createElement(
      "button",
      {
        type: "button",
        className: "myair-fileref",
        title: props.line != null ? `${props.path}:${props.line}` : props.path,
        // An ActionPlan step: triggerAction ignores a library action passed as a bare {type, params}.
        onClick: () => triggerAction("", undefined, { steps: [{ type: "custom_action", name: "OpenFile", params }] }),
      },
      fileRefText(props),
    );
  },
});

/** The library the canvas renders with; created at module scope, as parsers memoize by library identity. */
export const canvasLibrary = openuiLibrary.extend({
  // Stack's children take any component; Card's children are a union, which FileRef joins.
  components: { add: [{ component: FileRef, slots: ["Card.children"] }, Board, Shape, Note, Frame, Edge] },
  actions: { add: [OpenFile, CopyToClipboard] },
});

/** The OpenUI system prompt of canvasLibrary, shipped as `src/main/resources/canvas/system-prompt.txt`. */
export function systemPrompt(): string {
  return canvasLibrary.prompt(openuiPromptOptions);
}
