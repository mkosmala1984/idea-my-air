using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;

namespace MyAir.Taskbar;

public sealed record FlyoutHeader(string Label, Brush Dot);

public sealed record FlyoutRow(string Id, string Title, string Time, string Detail, string? Chip, Visibility ChipVisibility, Visibility DotVisibility);

/**
 * The session list above the widget. Hover shows it without focus (a peek); a click pins it, with focus, until the
 * user clicks outside, presses Esc or picks a session. It is a top-level window: inside the taskbar it would be clipped.
 */
public partial class Flyout : Window
{
    private readonly BoardWatcher _board;
    private FrameworkElement? _widget;
    private long _dismissedAt;

    public bool Pinned { get; private set; }

    /** A click on the widget deactivates a pinned flyout first; that click must not pin it again. */
    public bool JustDismissed => Environment.TickCount64 - _dismissedAt < 300;

    public Flyout(BoardWatcher board)
    {
        InitializeComponent();
        Theme.Apply(Resources);
        _board = board;
        _board.Changed += OnBoardChanged;
        Closed += (_, _) => _board.Changed -= OnBoardChanged;
        SizeChanged += (_, _) => Place();
        Deactivated += (_, _) => { if (Pinned) Dismiss(); };
        KeyDown += (_, e) => { if (e.Key == Key.Escape) Dismiss(); };
        // A tool window stays out of Alt+Tab.
        SourceInitialized += (_, _) =>
        {
            var hwnd = new WindowInteropHelper(this).Handle;
            SetWindowLongPtr(hwnd, GWL_EXSTYLE, GetWindowLongPtr(hwnd, GWL_EXSTYLE) | WS_EX_TOOLWINDOW);
        };
    }

    private void OnBoardChanged()
    {
        if (IsVisible) Render();
    }

    public void Peek(FrameworkElement widget)
    {
        if (IsVisible) return;
        Pinned = false;
        Open(widget);
    }

    public void Pin(FrameworkElement widget)
    {
        Pinned = true;
        Open(widget);
        Activate();
        Items.UpdateLayout();
        FindRow(Items)?.Focus();
    }

    public void Dismiss()
    {
        Pinned = false;
        _dismissedAt = Environment.TickCount64;
        Hide();
    }

    private void Open(FrameworkElement widget)
    {
        _widget = widget;
        Render();
        // Shown transparent and placed before the first frame, so it never flashes where it was last.
        Opacity = 0;
        Show();
        Place();
        Opacity = 1;
    }

    private void Render()
    {
        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var running = _board.Ides.Count > 0;
        // Without an IDE nothing can open a session, and the board is as old as the last IDE left it.
        var board = running ? new BoardFile(_board.Board, _board.Recent) : BoardFile.Empty;
        Summary.Text = Board.Summary(_board.Board, running);
        var items = new List<object>();
        foreach (var group in Enum.GetValues<Group>())
        {
            var rows = group == Group.Recent
                ? board.Recent.Select(r => new FlyoutRow(r.Id, r.Title, Board.Ago(r.LastMessage, now), r.Project, null, Visibility.Collapsed, Visibility.Collapsed)).ToList()
                : board.Sessions.Where(s => Board.GroupOf(s) == group).Select(s => new FlyoutRow(
                    s.Id, s.Title,
                    group == Group.Unread || s.LastMessage is not { } at ? "" : Board.Ago(at, now),
                    Board.Detail(s, now),
                    s.WaitingFor, s.WaitingFor is null ? Visibility.Collapsed : Visibility.Visible,
                    group == Group.Unread ? Visibility.Visible : Visibility.Collapsed)).ToList();
            if (rows.Count == 0) continue;
            items.Add(new FlyoutHeader(Board.Label(group), (Brush)Resources[Theme.DotKey(group)]));
            items.AddRange(rows);
        }
        Items.ItemsSource = items;
        var idle = Board.HiddenIdle(board);
        FooterText.Text = Pinned ? "↑↓ to move · Enter to open · Esc to close"
            : idle == 0 ? "No idle sessions"
            : $"{idle} idle session{(idle == 1 ? " is" : "s are")} not shown";
    }

    /** Centered over the widget, on the bottom of the work area; the window margin is the gap to the taskbar. */
    private void Place()
    {
        if (_widget is null || !IsVisible) return;
        var anchor = PresentationSource.FromVisual(this)!.CompositionTarget!.TransformFromDevice
            .Transform(_widget.PointToScreen(new Point(_widget.ActualWidth / 2, 0)));
        var area = SystemParameters.WorkArea;
        Left = Math.Clamp(anchor.X - ActualWidth / 2, area.Left, area.Right - ActualWidth);
        Top = area.Bottom - ActualHeight;
    }

    private void OnRowClick(object sender, RoutedEventArgs e)
    {
        // Focus first: it hands the foreground to the IDE, which only works while this window still holds it.
        _board.Focus((string)((Button)sender).Tag);
        Dismiss();
    }

    private static Button? FindRow(DependencyObject parent)
    {
        for (var i = 0; i < VisualTreeHelper.GetChildrenCount(parent); i++)
        {
            var child = VisualTreeHelper.GetChild(parent, i);
            if (child is Button button) return button;
            if (FindRow(child) is { } found) return found;
        }
        return null;
    }

    private const int GWL_EXSTYLE = -20;
    private const long WS_EX_TOOLWINDOW = 0x80;

    [DllImport("user32.dll")]
    private static extern long GetWindowLongPtr(IntPtr hwnd, int index);

    [DllImport("user32.dll")]
    private static extern long SetWindowLongPtr(IntPtr hwnd, int index, long value);
}
