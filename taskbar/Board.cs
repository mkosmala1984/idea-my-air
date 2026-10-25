using System.Text.Json;
using System.Text.Json.Serialization;

namespace MyAir.Taskbar;

public enum SessionStatus { Working, NeedsInput, Idle }

/**
 * A running session from `~/.my-air/taskbar/board.json`, written by the plugin (TaskbarBoard.kt). [LastMessage] is epoch
 * ms; it and [Project] are missing while the transcript is not scanned yet. [Activity] is the task in progress of the
 * session's brief, else its goal; missing without a brief.
 */
public sealed record BoardSession(string Id, string Title, SessionStatus Status, bool Unread, string? Project = null, string? WaitingFor = null, long? LastMessage = null, string? Activity = null);

public sealed record OpenTab(IReadOnlyList<string> SessionIds, string Title, string Project);

/** An IDE running the plugin, from `~/.my-air/ides/<pid>.json` (TabStore.kt); [Started] is its start in epoch ms. */
public sealed record Ide(long Pid, long Started, IReadOnlyList<OpenTab> Tabs);

/** The flyout's groups, from urgent to calm. */
public enum Group { NeedsInput, Unread, Working, Recent }

/** A recent session no other group lists, with or without a process (TaskbarBoard.kt recentSessions). */
public sealed record RecentSession(string Id, string Title, string Project, long LastMessage);

public sealed record BoardFile(IReadOnlyList<BoardSession> Sessions, IReadOnlyList<RecentSession> Recent)
{
    public static readonly BoardFile Empty = new([], []);
}

public static class Board
{
    // Strict, like the plugin's readers: a changed format fails loudly instead of showing half the data.
    private static readonly JsonSerializerOptions Options = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        RespectNullableAnnotations = true,
        RespectRequiredConstructorParameters = true,
        UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow,
        Converters = { new JsonStringEnumConverter<SessionStatus>(JsonNamingPolicy.SnakeCaseUpper, allowIntegerValues: false) },
    };

    public static BoardFile ParseBoard(string json) => Deserialize<BoardFile>(json);

    public static Ide ParseIde(string json) => Deserialize<Ide>(json);

    private static T Deserialize<T>(string json) => JsonSerializer.Deserialize<T>(json, Options) ?? throw new JsonException($"null {typeof(T).Name}");

    /** The IDE to ask: the one with a tab on the session, else any, which opens it where it runs; null without an IDE. */
    public static long? Target(string sessionId, IReadOnlyList<Ide> ides) =>
        (ides.FirstOrDefault(ide => ide.Tabs.Any(tab => tab.SessionIds.Contains(sessionId))) ?? ides.FirstOrDefault())?.Pid;

    /** Null for an idle session the user has read: the flyout only counts those. */
    public static Group? GroupOf(BoardSession s) => s.Status switch
    {
        SessionStatus.NeedsInput => Group.NeedsInput,
        SessionStatus.Working => Group.Working,
        SessionStatus.Idle => s.Unread ? Group.Unread : null,
        _ => throw new ArgumentOutOfRangeException(nameof(s), s.Status, null),
    };

    public static int Count(IReadOnlyList<BoardSession> sessions, Group group) => sessions.Count(s => GroupOf(s) == group);

    /** Running sessions, idle and read, that not even "Recent" lists: the footer counts them. */
    public static int HiddenIdle(BoardFile board) =>
        board.Sessions.Count(s => GroupOf(s) is null && board.Recent.All(r => r.Id != s.Id));

    /** "now", "3 min", "2 h", "4 d": how long ago [epochMs] was at [nowMs]. */
    public static string Ago(long epochMs, long nowMs)
    {
        var minutes = Math.Max(0, (nowMs - epochMs) / 60_000);
        return minutes < 1 ? "now" : minutes < 60 ? $"{minutes} min" : minutes < 24 * 60 ? $"{minutes / 60} h" : $"{minutes / (24 * 60)} d";
    }

    public static string Label(Group group) => group switch
    {
        Group.NeedsInput => "Needs input",
        Group.Unread => "Unread",
        Group.Working => "Working",
        Group.Recent => "Recent",
        _ => throw new ArgumentOutOfRangeException(nameof(group), group, null),
    };

    /** The second line of a row: the project, for an unread reply when it came, and what the session is doing. */
    public static string Detail(BoardSession s, long nowMs)
    {
        var parts = new List<string> { s.Project ?? "new session" };
        if (s.Unread && s.LastMessage is { } at)
        {
            var ago = Ago(at, nowMs);
            parts.Add($"finished {(ago == "now" ? "just now" : ago + " ago")}");
        }
        if (s.Activity is { } activity) parts.Add(activity);
        return string.Join(" · ", parts);
    }

    /**
     * The flyout header: "2 need you · 1 working", or why the list is empty. Idle sessions count in neither part: a
     * running process alone is not work, and the footer counts them.
     */
    public static string Summary(IReadOnlyList<BoardSession> sessions, bool ideRunning)
    {
        if (!ideRunning) return "No IDE with My Air is running";
        if (sessions.Count == 0) return "No running sessions";
        var waiting = Count(sessions, Group.NeedsInput) + Count(sessions, Group.Unread);
        return $"{waiting} need{(waiting == 1 ? "s" : "")} you · {Count(sessions, Group.Working)} working";
    }
}
