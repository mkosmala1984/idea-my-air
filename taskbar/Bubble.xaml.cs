using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;

namespace MyAir.Taskbar;

/**
 * Announces above the widget the sessions that came to need the user ([BoardWatcher.Arrived]), without taking focus.
 * A click on one opens it. It hides some seconds after the last arrival unless the pointer is on it, and drops a
 * session as soon as the board shows it no longer in the group it was announced for.
 */
public partial class Bubble : Window
{
    private readonly BoardWatcher _board;
    private readonly FrameworkElement _widget;
    private readonly DispatcherTimer _hide;
    private List<BoardSession> _sessions = [];

    public Bubble(BoardWatcher board, FrameworkElement widget)
    {
        InitializeComponent();
        Theme.Apply(Resources);
        _board = board;
        _widget = widget;
        _hide = new DispatcherTimer(TimeSpan.FromSeconds(8), DispatcherPriority.Normal, (_, _) => { if (!IsMouseOver) Dismiss(); }, Dispatcher);
        _hide.Stop();
        _board.Changed += OnBoardChanged;
        Closed += (_, _) => _board.Changed -= OnBoardChanged;
        SizeChanged += (_, _) => { if (IsVisible) Popup.PlaceOver(this, _widget); };
        Popup.HideFromAltTab(this);
    }

    public void Announce(IReadOnlyList<BoardSession> arrived)
    {
        _sessions = [.. arrived, .. _sessions.Where(s => arrived.All(a => a.Id != s.Id))];
        _hide.Stop();
        _hide.Start();
        Render();
    }

    public void Dismiss()
    {
        _hide.Stop();
        _sessions = [];
        Hide();
    }

    private void OnBoardChanged()
    {
        _sessions = _sessions.Select(s => _board.Board.FirstOrDefault(b => b.Id == s.Id && Board.GroupOf(b) == Board.GroupOf(s))).OfType<BoardSession>().ToList();
        if (IsVisible) Render();
    }

    private void Render()
    {
        if (_sessions.Count == 0)
        {
            Dismiss();
            return;
        }
        Items.ItemsSource = Popup.Items(new BoardFile(_sessions, []), [Group.NeedsInput, Group.Unread], Resources, projectInTitle: true);
        if (IsVisible) return;
        // Shown transparent and placed before the first frame, so it never flashes where it was last.
        Opacity = 0;
        Show();
        Popup.PlaceOver(this, _widget);
        Opacity = 1;
    }

    private void OnRowClick(object sender, RoutedEventArgs e)
    {
        // The click leaves a window shown without activation in the background; the IDE can take the foreground only
        // from the process that holds it. The click is the last input, so Windows lets this window take it.
        var id = (string)((Button)e.OriginalSource).Tag;
        Activate();
        _board.Focus(id);
        _sessions.RemoveAll(s => s.Id == id);
        Render();
    }
}
