using System.Text.Json;
using System.Text.Json.Serialization;

namespace MyAir.Taskbar;

public enum SessionStatus { Working, NeedsInput, Idle }

/** A running session from `~/.my-air/taskbar/board.json`, written by the plugin (TaskbarBoard.kt). */
public sealed record BoardSession(string Id, string Title, SessionStatus Status, string? Project = null, string? WaitingFor = null);

public sealed record OpenTab(IReadOnlyList<string> SessionIds, string Title, string Project);

/** An IDE running the plugin, from `~/.my-air/ides/<pid>.json` (TabStore.kt); [Started] is its start in epoch ms. */
public sealed record Ide(long Pid, long Started, IReadOnlyList<OpenTab> Tabs);

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

    private sealed record BoardFile(IReadOnlyList<BoardSession> Sessions);

    public static IReadOnlyList<BoardSession> ParseBoard(string json) => Deserialize<BoardFile>(json).Sessions;

    public static Ide ParseIde(string json) => Deserialize<Ide>(json);

    private static T Deserialize<T>(string json) => JsonSerializer.Deserialize<T>(json, Options) ?? throw new JsonException($"null {typeof(T).Name}");

    /** The IDE to ask: the one with a tab on the session, else any, which opens it where it runs; null without an IDE. */
    public static long? Target(string sessionId, IReadOnlyList<Ide> ides) =>
        (ides.FirstOrDefault(ide => ide.Tabs.Any(tab => tab.SessionIds.Contains(sessionId))) ?? ides.FirstOrDefault())?.Pid;

    public static string Line(BoardSession s)
    {
        var status = s.Status switch
        {
            SessionStatus.NeedsInput => "needs input" + (s.WaitingFor is null ? "" : $" ({s.WaitingFor})"),
            SessionStatus.Idle => "waiting for prompt",
            SessionStatus.Working => "working",
            _ => throw new ArgumentOutOfRangeException(nameof(s), s.Status, null),
        };
        return $"{s.Project ?? "?"} — {s.Title}: {status}";
    }

    public static string Tooltip(IReadOnlyList<BoardSession> sessions, bool ideRunning) =>
        !ideRunning ? "No IDE with My Air is running"
        : sessions.Count == 0 ? "No running Claude sessions"
        : string.Join("\n", sessions.Select(Line));
}
