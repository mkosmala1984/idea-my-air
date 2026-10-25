using Microsoft.Win32;
using System.Windows;
using System.Windows.Media;

namespace MyAir.Taskbar;

/** Windows 11 flyout colors for the taskbar's light or dark mode, as brushes that XAML takes by key. */
public static class Theme
{
    private static readonly Dictionary<string, string> Light = new()
    {
        ["Text"] = "#1A1A1A", ["Secondary"] = "#616161", ["Background"] = "#F9F9F9", ["Footer"] = "#F3F3F3",
        ["Border"] = "#14000000", ["Hover"] = "#0D000000", ["Focus"] = "#005FB8",
        ["ChipBack"] = "#FFF4E5", ["ChipText"] = "#8A4B00",
        ["NeedsInputDot"] = "#F5A623", ["UnreadDot"] = "#005FB8", ["WorkingDot"] = "#8A8A8A", ["RecentDot"] = "#C8C8C8",
    };

    private static readonly Dictionary<string, string> Dark = new()
    {
        ["Text"] = "#FFFFFF", ["Secondary"] = "#ABABAB", ["Background"] = "#2C2C2C", ["Footer"] = "#262626",
        ["Border"] = "#1AFFFFFF", ["Hover"] = "#10FFFFFF", ["Focus"] = "#60CDFF",
        ["ChipBack"] = "#4A3410", ["ChipText"] = "#FFD08A",
        ["NeedsInputDot"] = "#F5A623", ["UnreadDot"] = "#4CC2FF", ["WorkingDot"] = "#9A9A9A", ["RecentDot"] = "#5C5C5C",
    };

    public static bool TaskbarIsLight() =>
        Registry.GetValue(@"HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize", "SystemUsesLightTheme", 0) is 1;

    public static void Apply(ResourceDictionary resources)
    {
        foreach (var (key, color) in TaskbarIsLight() ? Light : Dark)
            resources[key] = new SolidColorBrush((Color)ColorConverter.ConvertFromString(color)).GetAsFrozen();
    }

    public static string DotKey(Group group) => group switch
    {
        Group.NeedsInput => "NeedsInputDot",
        Group.Unread => "UnreadDot",
        Group.Working => "WorkingDot",
        Group.Recent => "RecentDot",
        _ => throw new ArgumentOutOfRangeException(nameof(group), group, null),
    };
}
