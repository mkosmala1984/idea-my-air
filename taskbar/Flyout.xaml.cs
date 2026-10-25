using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;

namespace MyAir.Taskbar;

/**
 * The session list above the widget. Hover shows it without focus (a peek); a click pins it, with focus, until the
 * user clicks outside, presses Esc or picks a session.
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
        Popup.HideFromAltTab(this);
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
        var running = _board.Ides.Count > 0;
        // Without an IDE nothing can open a session, and the board is as old as the last IDE left it.
        var board = running ? new BoardFile(_board.Board, _board.Recent) : BoardFile.Empty;
        Summary.Text = Board.Summary(_board.Board, running);
        Items.ItemsSource = Popup.Items(board, Enum.GetValues<Group>(), Resources);
        var idle = Board.HiddenIdle(board);
        FooterText.Text = Pinned ? "↑↓ to move · Enter to open · Esc to close"
            : idle == 0 ? "No idle sessions"
            : $"{idle} idle session{(idle == 1 ? " is" : "s are")} not shown";
    }

    private void Place()
    {
        if (_widget is not null && IsVisible) Popup.PlaceOver(this, _widget);
    }

    private void OnRowClick(object sender, RoutedEventArgs e)
    {
        // Focus first: it hands the foreground to the IDE, which only works while this window still holds it.
        _board.Focus((string)((Button)e.OriginalSource).Tag);
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
}
