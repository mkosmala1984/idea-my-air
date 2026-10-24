using System.Text.Json;
using Xunit;

namespace MyAir.Taskbar.Tests;

public class BoardTest
{
    [Fact]
    public void Reads_the_board_the_plugin_writes()
    {
        // Verbatim from TaskbarBoardTest.kt, which checks that the plugin writes exactly this.
        const string json = """{"sessions":[{"id":"a","title":"Fix tray","project":"my-air","status":"NEEDS_INPUT","waitingFor":"permission"},""" +
                            """{"id":"b","title":"New session","status":"WORKING"}]}""";
        Assert.Equal(
            [new BoardSession("a", "Fix tray", SessionStatus.NeedsInput, "my-air", "permission"), new BoardSession("b", "New session", SessionStatus.Working)],
            Board.ParseBoard(json));
    }

    [Theory]
    [InlineData("""{"sessions":[{"id":"a","title":"t","status":"BLOCKED"}]}""")] // a status this app does not know
    [InlineData("""{"sessions":[{"id":"a","status":"IDLE"}]}""")] // no title
    [InlineData("""{"sessions":[{"id":"a","title":"t","status":"IDLE","pid":1}]}""")] // a field this app does not know
    public void A_changed_format_throws(string json) => Assert.ThrowsAny<JsonException>(() => Board.ParseBoard(json));

    [Fact]
    public void Reads_an_ide_tab_list_as_TabStore_writes_it()
    {
        var ide = Board.ParseIde("""{"pid":42,"started":1700000000000,"tabs":[{"sessionIds":["x","y"],"title":"T","project":"my-air"}]}""");
        Assert.Equal((42L, 1700000000000L, "x,y"), (ide.Pid, ide.Started, string.Join(",", ide.Tabs.Single().SessionIds)));
    }

    private static Ide IdeWith(long pid, params string[] sessionIds) => new(pid, 0, [new OpenTab(sessionIds, "t", "p")]);

    [Fact]
    public void A_click_goes_to_the_ide_showing_the_session_else_to_any_ide()
    {
        var ides = new[] { IdeWith(1, "a"), IdeWith(2, "b") };
        Assert.Equal(2, Board.Target("b", ides));
        Assert.Equal(1, Board.Target("in-a-terminal", ides));
        Assert.Null(Board.Target("b", []));
    }

    [Fact]
    public void The_tooltip_lists_one_session_per_line_and_says_when_nothing_runs()
    {
        var sessions = new[] { new BoardSession("a", "Fix tray", SessionStatus.NeedsInput, "my-air", "permission"), new BoardSession("b", "New session", SessionStatus.Idle) };
        Assert.Equal("my-air — Fix tray: needs input (permission)\n? — New session: waiting for prompt", Board.Tooltip(sessions, true));
        Assert.Equal("No running Claude sessions", Board.Tooltip([], true));
        Assert.Equal("No IDE with My Air is running", Board.Tooltip(sessions, false));
    }
}
