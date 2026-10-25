using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Windows.Threading;

namespace MyAir.Taskbar;

/**
 * Keeps the board and the live IDEs read from `~/.my-air`, and sends focus requests the way TabStore.kt does. Lives as
 * long as the app, across the windows the taskbar makes it recreate. [Changed] and [Board] belong to the UI thread.
 */
public sealed partial class BoardWatcher
{
    private readonly string _boardFile;
    private readonly string _idesDir;
    private readonly string _requestsDir;
    private readonly string _seenDir;
    private readonly Dispatcher _dispatcher;
    private readonly List<FileSystemWatcher> _watchers;
    private readonly DispatcherTimer _timer;

    public IReadOnlyList<BoardSession> Board { get; private set; } = [];
    public IReadOnlyList<RecentSession> Recent { get; private set; } = [];
    public IReadOnlyList<Ide> Ides { get; private set; } = [];
    public event Action? Changed;

    public BoardWatcher(string root, Dispatcher dispatcher)
    {
        _boardFile = Path.Combine(root, "taskbar", "board.json");
        _idesDir = Directory.CreateDirectory(Path.Combine(root, "ides")).FullName;
        _requestsDir = Path.Combine(root, "requests");
        _seenDir = Directory.CreateDirectory(Path.Combine(root, "seen")).FullName;
        _dispatcher = dispatcher;
        Directory.CreateDirectory(Path.GetDirectoryName(_boardFile)!);
        // The plugin writes a temp file and renames it into place, which shows up as Renamed, not Changed.
        _watchers = [Watch(Path.GetDirectoryName(_boardFile)!), Watch(_idesDir)];
        // An IDE that exits or crashes leaves its file behind; only a process check notices.
        _timer = new DispatcherTimer(TimeSpan.FromSeconds(5), DispatcherPriority.Background, (_, _) => Reload(), dispatcher);
        Reload();
    }

    private FileSystemWatcher Watch(string dir)
    {
        var watcher = new FileSystemWatcher(dir) { NotifyFilter = NotifyFilters.FileName | NotifyFilters.LastWrite };
        FileSystemEventHandler reload = (_, _) => _dispatcher.BeginInvoke(Reload);
        watcher.Created += reload;
        watcher.Changed += reload;
        watcher.Deleted += reload;
        watcher.Renamed += (_, _) => _dispatcher.BeginInvoke(Reload);
        watcher.EnableRaisingEvents = true;
        return watcher;
    }

    private void Reload()
    {
        var ides = Directory.GetFiles(_idesDir, "*.json").Select(TryRead).OfType<string>().Select(Taskbar.Board.ParseIde)
            .Where(ide => IsAlive(ide.Pid, ide.Started)).ToList();
        var file = TryRead(_boardFile) is { } text ? Taskbar.Board.ParseBoard(text) : BoardFile.Empty;
        // Tabs only steer where a click goes; redrawing on their changes would move the flyout's focus for nothing.
        var redraw = !file.Sessions.SequenceEqual(Board) || !file.Recent.SequenceEqual(Recent) || ides.Count != Ides.Count;
        Board = file.Sessions;
        Recent = file.Recent;
        Ides = ides;
        if (redraw) Changed?.Invoke();
    }

    /** Null when the file is gone: an IDE removes its tab list on exit, and the board does not exist before the first IDE. */
    private static string? TryRead(string path)
    {
        try
        {
            // The plugin replaces these files while they may be open here; without delete sharing its move would fail.
            using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            return new StreamReader(stream).ReadToEnd();
        }
        catch (FileNotFoundException)
        {
            return null;
        }
    }

    /** Same test as TabStore.isAlive: the pid still belongs to the process that started at [started] epoch ms. */
    private static bool IsAlive(long pid, long started)
    {
        try
        {
            using var process = Process.GetProcessById((int)pid);
            return new DateTimeOffset(process.StartTime).ToUnixTimeMilliseconds() == started;
        }
        catch (ArgumentException)
        {
            return false; // no process with that pid
        }
    }

    /** Lets the IDE take the foreground, which only works while this app has it (the click gave it), then asks it. */
    public void Focus(string sessionId)
    {
        var pid = Taskbar.Board.Target(sessionId, Ides) ?? throw new InvalidOperationException("no IDE with My Air is running");
        if (!AllowSetForegroundWindow((int)pid)) throw new InvalidOperationException($"Windows refused to let pid {pid} take the foreground");
        var inbox = Directory.CreateDirectory(Path.Combine(_requestsDir, pid.ToString())).FullName;
        // The IDE drains *.json on every create event, so the request appears under that name complete.
        var temp = Path.Combine(inbox, $"{Guid.NewGuid()}.tmp");
        File.WriteAllText(temp, $$"""{"sessionId":"{{sessionId}}"}""");
        File.Move(temp, Path.ChangeExtension(temp, ".json"));
        // The IDE marks a session seen when its tab gets focus; one running in a terminal has no tab.
        MarkSeen(sessionId);
    }

    /** Writes `seen/<id>` as Seen.kt reads it: the time in epoch ms; the temp name has a dot, which the plugin skips. */
    private void MarkSeen(string sessionId)
    {
        var target = Path.Combine(_seenDir, sessionId);
        var temp = $"{target}.{Guid.NewGuid()}.tmp";
        File.WriteAllText(temp, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds().ToString());
        File.Move(temp, target, overwrite: true);
    }

    [LibraryImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool AllowSetForegroundWindow(int processId);
}
