# src

## Overview

Sources of the My Air IntelliJ plugin. Tests in `test/kotlin` are plain JUnit 5 (no IntelliJ test framework) and sit in
the package of what they test.

## Subdirectories & Boundaries

- main: [./main/README.md](./main/README.md) - production Kotlin sources and plugin resources.
- test (no contract, test code only): JUnit 5 tests for `actions`, `claude`, `editor`, `folders`, `mcp`, `planview`,
  `prompt`, `service`, `store`, `toolwindow`; run with `./gradlew test`.
