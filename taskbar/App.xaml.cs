using Deskband11Lib.Wpf;
using System.IO;
using System.Windows;

namespace MyAir.Taskbar;

public partial class App : Application
{
    private BoardWatcher _board = null!;
    private MainWindow? _window;
    private Mutex? _instance;

    protected override async void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        // A second copy would put a second widget on the taskbar; Windows releases the mutex when this process ends.
        _instance = new Mutex(true, @"Local\MyAir.Taskbar", out var first);
        if (!first)
        {
            Shutdown();
            return;
        }
        // A widget that vanishes silently looks like a hung taskbar; say what broke, then quit.
        DispatcherUnhandledException += (_, args) =>
        {
            MessageBox.Show(args.Exception.ToString(), "My Air taskbar");
            Shutdown(1);
        };
        _board = new BoardWatcher(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".my-air"), Dispatcher);
        await ShowWindowAsync();
    }

    private async Task ShowWindowAsync()
    {
        var window = new MainWindow(_board);
        _window = window;
        window.Host.TaskbarWindowRecreationRequired += OnRecreationRequired;
        await window.Host.AttachWhenLayoutReadyAsync();
        window.Show();
    }

    // Explorer restarts, monitor and DPI changes destroy the hosted window; attach a new one once the taskbar is back,
    // as the library's sample does.
    private async void OnRecreationRequired(object? sender, EventArgs e)
    {
        var old = _window;
        old?.Host.Dispose();
        _window = null;
        await Task.Delay(1000);
        await ShowWindowAsync();
        if (old.IsWindowAlive()) old!.Close();
    }
}
