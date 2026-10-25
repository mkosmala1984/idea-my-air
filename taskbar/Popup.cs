using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;

namespace MyAir.Taskbar;

public sealed record FlyoutHeader(string Label, Brush Dot);

public sealed record FlyoutRow(string Id, string Title, string Time, string Detail, string? Chip, Visibility ChipVisibility, Visibility DotVisibility);

/** What the flyout and the bubble share: top-level windows above the widget (inside the taskbar they would be clipped). */
public static class Popup
{
    /**
     * A header and rows for each of [groups] that [board] has sessions in; header dots come from [resources]. With
     * [projectInTitle] a session's project leads its title instead of its second line.
     */
    public static List<object> Items(BoardFile board, IEnumerable<Group> groups, ResourceDictionary resources, bool projectInTitle = false)
    {
        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var items = new List<object>();
        foreach (var group in groups)
        {
            var rows = group == Group.Recent
                ? board.Recent.Select(r => new FlyoutRow(r.Id, r.Title, Board.Ago(r.LastMessage, now), r.Project, null, Visibility.Collapsed, Visibility.Collapsed)).ToList()
                : board.Sessions.Where(s => Board.GroupOf(s) == group).Select(s => new FlyoutRow(
                    s.Id, Board.Title(s, projectInTitle),
                    group == Group.Unread || s.LastMessage is not { } at ? "" : Board.Ago(at, now),
                    Board.Detail(s, now, projectInTitle),
                    s.WaitingFor, s.WaitingFor is null ? Visibility.Collapsed : Visibility.Visible,
                    group == Group.Unread ? Visibility.Visible : Visibility.Collapsed)).ToList();
            if (rows.Count == 0) continue;
            items.Add(new FlyoutHeader(Board.Label(group), (Brush)resources[Theme.DotKey(group)]));
            items.AddRange(rows);
        }
        return items;
    }

    /** Centered over [widget], on the bottom of the work area; the window margin is the gap to the taskbar. */
    public static void PlaceOver(Window window, FrameworkElement widget)
    {
        var anchor = PresentationSource.FromVisual(window)!.CompositionTarget!.TransformFromDevice
            .Transform(widget.PointToScreen(new Point(widget.ActualWidth / 2, 0)));
        var area = SystemParameters.WorkArea;
        window.Left = Math.Clamp(anchor.X - window.ActualWidth / 2, area.Left, area.Right - window.ActualWidth);
        window.Top = area.Bottom - window.ActualHeight;
    }

    /** A tool window stays out of Alt+Tab. */
    public static void HideFromAltTab(Window window) => window.SourceInitialized += (_, _) =>
    {
        var hwnd = new WindowInteropHelper(window).Handle;
        SetWindowLongPtr(hwnd, GWL_EXSTYLE, GetWindowLongPtr(hwnd, GWL_EXSTYLE) | WS_EX_TOOLWINDOW);
    };

    private const int GWL_EXSTYLE = -20;
    private const long WS_EX_TOOLWINDOW = 0x80;

    [DllImport("user32.dll")]
    private static extern long GetWindowLongPtr(IntPtr hwnd, int index);

    [DllImport("user32.dll")]
    private static extern long SetWindowLongPtr(IntPtr hwnd, int index, long value);
}
