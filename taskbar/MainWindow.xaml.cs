using Deskband11Lib.Wpf;
using Microsoft.Win32;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;

namespace MyAir.Taskbar;

/** Two counts on the taskbar: sessions waiting for the user and working ones; the tooltip and a click list them all. */
public partial class MainWindow : Window
{
    private readonly BoardWatcher _board;

    public TaskbarContentHost Host { get; }

    public MainWindow(BoardWatcher board)
    {
        InitializeComponent();
        _board = board;
        Foreground = TaskbarIsLight() ? Brushes.Black : Brushes.White;
        Host = new TaskbarContentHost(this, Root, new() { PreferredWidth = Width });
        _board.Changed += Render;
        Render();
    }

    private static bool TaskbarIsLight() =>
        Registry.GetValue(@"HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize", "SystemUsesLightTheme", 0) is 1;

    private void Render()
    {
        var running = _board.Ides.Count > 0;
        NeedsInput.Text = _board.Board.Count(s => s.Status == SessionStatus.NeedsInput).ToString();
        Working.Text = _board.Board.Count(s => s.Status == SessionStatus.Working).ToString();
        Root.Opacity = running ? 1 : 0.4;
        Root.ToolTip = Board.Tooltip(_board.Board, running);
    }

    private void OnClick(object sender, MouseButtonEventArgs e)
    {
        var menu = new ContextMenu { PlacementTarget = Root, Placement = PlacementMode.Top };
        foreach (var session in _board.Ides.Count > 0 ? _board.Board : [])
        {
            // A string header would turn "_" in a title into an access key.
            var item = new MenuItem { Header = new TextBlock { Text = Board.Line(session) } };
            item.Click += (_, _) => _board.Focus(session.Id);
            menu.Items.Add(item);
        }
        if (menu.Items.Count == 0) menu.Items.Add(new MenuItem { Header = Board.Tooltip(_board.Board, _board.Ides.Count > 0), IsEnabled = false });
        menu.Items.Add(new Separator());
        var exit = new MenuItem { Header = "Exit" };
        exit.Click += (_, _) => Application.Current.Shutdown();
        menu.Items.Add(exit);
        menu.IsOpen = true;
        e.Handled = true;
    }

    protected override void OnClosed(EventArgs e)
    {
        _board.Changed -= Render;
        Host.Dispose();
        base.OnClosed(e);
    }
}
