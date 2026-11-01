package dev.myair.editor

/**
 * What every `claude` a session tab starts appends to its system prompt (`--append-system-prompt`), on `--resume` too:
 * claude records a session's prompt on its first request and builds it again from the current launch's flags only after
 * a compaction. It names nothing of one session, so every session sends the same prompt and shares its cache; the base
 * is read from git when needed, since the context pane may change it after the start. One line without `"`, `<` or `>`,
 * as it travels on a Windows command line.
 */
fun appendedSystemPrompt(briefs: Boolean): String = listOfNotNull(
    "This session runs in a My Air tab of IntelliJ IDEA.",
    "My Air stores the base of a branch it created as `git config branch.NAME.myAirBase`, NAME being the branch: when the " +
        "current branch has one, diff, review and target pull requests against that base, not the default branch.",
    "The IDE's MCP server has My Air tools, each described in full by its own description: my_air_sessions_find finds " +
        "the user's Claude Code sessions (by date, words in the title or project, folder), for example an earlier session to " +
        "continue from; my_air_folders_get reads the folders the user files sessions in, my_air_folders_apply changes them " +
        "only after the user approved the change, and my_air_history_list with my_air_history_restore undo such changes.",
    "A project the user plans has a plan folder with milestones and tasks: at the start call my_air_plan_get with the working " +
        "directory, and when it returns a plan, tell the user which task fits the request or propose a new one" +
        (if (briefs) ", and name that task as plan_task in my_air_set_brief." else ".") +
        " Keep the task's status and next step current with my_air_plan_apply: waiting when the user has to act, done only when " +
        "the work is finished. Add tasks freely, but delete or move milestones and tasks only after the user agreed.",
    if (!briefs) null else "Call the my_air_set_brief tool once you understand the user's first request, and again whenever the " +
        "goal changes or a task starts or finishes.",
).joinToString(" ")
