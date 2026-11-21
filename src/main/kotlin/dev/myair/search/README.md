# search

## Overview

A full-text index of what the human and the agent said in every session, so the sessions search finds a session by its
conversation and not only by title, directory and branch. One SQLite FTS5 table in `~/.my-air/search.db`, shared by every
IDE running the plugin; SQLite is `org.xerial:sqlite-jdbc` bundled with the plugin (FTS5 included), since the IDE's own
copy is a library module no plugin can depend on.

## Interface

- `SessionIndex(file)` (`SessionIndex.kt`):
  - `update(files): Boolean`: reads the complete lines after the stored offset of each transcript, forgets the files not
    listed, returns whether anything changed;
  - `search(query): Map<Path, String>`: the transcripts with one message holding every word of the query, each with the
    text around the match, best first; an empty query throws;
  - internal `texts(line)`: the messages one transcript line holds, as indexed.
- `ClaudeSessionsService.searchContent(query)` (off the EDT) and `indexGeneration` (counts index changes); the service
  indexes after every refresh on its own thread (`indexConversations`, the latest list of transcripts only).
- Used by the Claude Sessions search field (`SessionsPanel.searchContent`: a session stays in the tree when it matches by
  title, directory or branch, or by a message; the toggle Search in Conversations beside the field, kept in
  `dev.myair.searchConversations`, default on, turns the message search off) and by `my_air_sessions_find`'s `query` (`findSessions(content = ...)`, which
  adds `match`, the text around the hit, to each session found by a message).

## Invariants

- One row per message: what the human typed (or the skill they ran, as `ClaudeSessionScanner.promptText` reads it) and the
  `text` blocks of the agent's replies, outside sidechains and `isMeta` lines, not `claude`'s own error replies (model
  `<synthetic>`). Tool calls, tool results and thinking are not indexed.
- Every word of a query must occur in the same message. The trigram tokenizer matches any fragment, ignoring case and
  accents (`remove_diacritics 1`); it does not fold `ł`, and a word shorter than three characters matches nothing (the whole
  search is then empty; the title match stays).
- Transcripts are append-only: each file's offset is stored in `progress`; a file shorter than it was rewritten and is read
  again; a file not among those listed is forgotten (the copy of a session the scanner does not pick, a session `/bg`
  continued elsewhere). Each file is one write transaction taking the lock at its start; the offset is read again inside it,
  since another IDE may have indexed the file meanwhile.
- Connections come from `JDBC.createConnection` (not `DriverManager`, whose system class loader does not see the plugin's
  jar), in WAL journal mode with IMMEDIATE transactions and a 60 s busy timeout.
- `PRAGMA user_version` holds `FORMAT`; another value drops both tables and starts over, as the scanner's cache does for a
  rebuilt reader. Bump it whenever `texts` or the schema changes.
- Anything else wrong (a corrupt file, a line that does not parse) throws.
