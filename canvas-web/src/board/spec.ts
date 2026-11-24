// A Board's parsed items (OpenUI element nodes) as the solver's specs. Pure, so a parse result is enough: the report
// can be computed without rendering.
import type { ElementNode } from "@openuidev/react-lang";
import type { Box, ContainerLayout, EdgeSpec, ElementKind, ElementSpec, Problem } from "./layout";

const KINDS: Record<string, ElementKind> = { Shape: "shape", Note: "note", Frame: "frame" };

export interface BoardSpec {
  elements: ElementSpec[];
  edges: EdgeSpec[];
  /** The element nodes by id: the view renders a frame's content and a note's text from them. */
  nodes: Map<string, ElementNode>;
  problems: Problem[];
}

const isElement = (v: unknown): v is ElementNode =>
  typeof v === "object" && v != null && (v as ElementNode).type === "element" && typeof (v as ElementNode).typeName === "string";

export function boardSpec(items: unknown[]): BoardSpec {
  const out: BoardSpec = { elements: [], edges: [], nodes: new Map(), problems: [] };
  let inline = 0;
  const idOf = (node: ElementNode) => {
    if (node.statementId) return node.statementId;
    inline += 1;
    const id = `${node.typeName.toLowerCase()}#${inline}`;
    out.problems.push({
      severity: "warning",
      code: "inline-element",
      ids: [id],
      message: `A ${node.typeName} is written inline, so nothing can anchor to it, connect to it or patch it; give it its own statement.`,
    });
    return id;
  };
  const collect = (list: unknown[], into: ElementSpec[]) => {
    for (const item of list) {
      if (!isElement(item)) continue;
      const p = item.props;
      if (item.typeName === "Edge") {
        out.edges.push({ id: idOf(item), from: String(p.from), to: String(p.to) });
        continue;
      }
      const kind = KINDS[item.typeName];
      if (!kind) {
        out.problems.push({
          severity: "error",
          code: "not-a-board-element",
          ids: [item.statementId ?? item.typeName],
          message: `${item.typeName} cannot lie on a board directly; put it in a Frame.`,
        });
        continue;
      }
      const id = idOf(item);
      out.nodes.set(id, item);
      const spec: ElementSpec = { id, kind, box: (p.box as Box | undefined) ?? {}, children: [] };
      if (kind === "shape") {
        spec.label = (p.label as string | undefined) || undefined;
        spec.layout = p.layout as ContainerLayout | undefined;
        spec.gap = p.gap as number | undefined;
        spec.columns = p.columns as number | undefined;
        collect((p.children as unknown[] | undefined) ?? [], spec.children);
      }
      into.push(spec);
    }
  };
  collect(items, out.elements);
  return out;
}
