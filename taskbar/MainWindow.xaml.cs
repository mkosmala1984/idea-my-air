using Deskband11Lib.Wpf;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Threading;

namespace MyAir.Taskbar;

/** Three counts on the taskbar: sessions that need input, unread replies, working ones. Hover or a click lists them. */
public partial class MainWindow : Window
{
    private readonly BoardWatcher _board;
    private readonly Flyout _flyout;
    private readonly DispatcherTimer _open;
    private readonly DispatcherTimer _close;

    public TaskbarContentHost Host { get; }

    public MainWindow(BoardWatcher board)
    {
        InitializeComponent();
        Theme.Apply(Resources);
        Foreground = (System.Windows.Media.Brush)Resources["Text"];
        _board = board;
        _flyout = new Flyout(board);
        _open = Timer(250, () => _flyout.Peek(Root));
        // Only a peek closes by itself, and not while the pointer is back on the widget or on the flyout.
        _close = Timer(300, () => { if (!_flyout.Pinned && !_flyout.IsMouseOver && !Root.IsMouseOver) _flyout.Dismiss(); });
        _flyout.MouseEnter += (_, _) => _close.Stop();
        _flyout.MouseLeave += (_, _) => CloseSoon();
        Host = new TaskbarContentHost(this, Root, new() { PreferredWidth = Width });
        _board.Changed += Render;
        Render();
    }

    private DispatcherTimer Timer(int ms, Action tick)
    {
        var timer = new DispatcherTimer(DispatcherPriority.Normal, Dispatcher) { Interval = TimeSpan.FromMilliseconds(ms) };
        timer.Tick += (_, _) =>
        {
            timer.Stop();
            tick();
        };
        return timer;
    }

    private void Render()
    {
        NeedsInput.Text = Board.Count(_board.Board, Group.NeedsInput).ToString();
        Unread.Text = Board.Count(_board.Board, Group.Unread).ToString();
        Working.Text = Board.Count(_board.Board, Group.Working).ToString();
        Root.Opacity = _board.Ides.Count > 0 ? 1 : 0.4;
    }

    private void OnEnter(object sender, MouseEventArgs e)
    {
        _close.Stop();
        if (!_flyout.IsVisible) _open.Start();
    }

    private void OnLeave(object sender, MouseEventArgs e)
    {
        _open.Stop();
        CloseSoon();
    }

    private void CloseSoon()
    {
        if (_flyout.IsVisible && !_flyout.Pinned) _close.Start();
    }

    private void OnClick(object sender, MouseButtonEventArgs e)
    {
        _open.Stop();
        _close.Stop();
        if (_flyout.Pinned) _flyout.Dismiss();
        else if (!_flyout.JustDismissed) _flyout.Pin(Root);
        e.Handled = true;
    }

    private void OnRightClick(object sender, MouseButtonEventArgs e)
    {
        _flyout.Dismiss();
        var exit = new MenuItem { Header = "Exit" };
        exit.Click += (_, _) => Application.Current.Shutdown();
        new ContextMenu { PlacementTarget = Root, Placement = PlacementMode.Top, Items = { exit }, IsOpen = true };
        e.Handled = true;
    }

    protected override void OnClosed(EventArgs e)
    {
        _board.Changed -= Render;
        _flyout.Close();
        Host.Dispose();
        base.OnClosed(e);
    }
}
