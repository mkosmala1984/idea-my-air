# main

## Overview

Production code of the My Air plugin: Kotlin sources and the resources packed into the plugin jar.

## Subdirectories & Boundaries

- kotlin: [./kotlin/README.md](./kotlin/README.md) - the `dev.myair` package tree.
- resources (no contract, no code): `META-INF/plugin.xml` registers the tool windows, editors, virtual file systems
  (`myair-session`, `myair-plan`), listeners, settings page and actions by class name; `META-INF/my-air-mcp.xml`
  registers `dev.myair.mcp.FoldersMcpToolset` and `BriefMcpToolset` behind the optional `com.intellij.mcpServer`
  dependency; `META-INF/my-air-branch-diff.xml` registers `dev.myair.branchdiff.SessionDiffTargetProvider` behind the
  optional `dev.kosmala.branch-diff-tree` dependency; `icons/` holds the SVG status icons.
