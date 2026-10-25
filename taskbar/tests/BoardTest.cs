using System.Text.Json;
using Xunit;

namespace MyAir.Taskbar.Tests;

public class BoardTest
{
    [Fact]
    public void Reads_the_board_the_plugin_writes()
    {
        // Verbatim from TaskbarBoardTest.kt, which checks that the plugin writes exactly this.
        const string json = """{"sessions":[{"id":"a","title":"Fix tray","project":"my-air","status":"IDLE","lastMessage":1700000000000,"unread":true},""" +
                            """{"id":"b","title":"New session","status":"NEEDS_INPUT","waitingFor":"permission","unread":false}],"recent":""" +
                            """[{"id":"c","title":"Old work","project":"tulip","lastMessage":1690000000000}]}""";
        Assert.Equal(
            [
                new BoardSession("a", "Fix tray", SessionStatus.Idle, true, "my-air", LastMessage: 1700000000000),
                new BoardSession("b", "New session", SessionStatus.NeedsInput, false, WaitingFor: "permission"),
            ],
            Board.ParseBoard(json).Sessions);
        Assert.Equal([new RecentSession("c", "Old work", "tulip", 1690000000000)], Board.ParseBoard(json).Recent);
    }

    [Theory]
    [InlineData("""{"sessions":[{"id":"a","title":"t","status":"BLOCKED","unread":false}],"recent":[]}""")] // a status this app does not know
    [InlineData("""{"sessions":[{"id":"a","status":"IDLE","unread":false}],"recent":[]}""")] // no title
    [InlineData("""{"sessions":[{"id":"a","title":"t","status":"IDLE"}],"recent":[]}""")] // no unread
    [InlineData("""{"sessions":[{"id":"a","title":"t","status":"IDLE","unread":false,"pid":1}],"recent":[]}""")] // a field this app does not know
    [InlineData("""{"sessions":[]}""")] // a plugin from before the recent list
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

    private static BoardSession Session(SessionStatus status, bool unread = false) => new("x", "t", status, unread, "my-air", LastMessage: 0);

    [Fact]
    public void Idle_sessions_show_only_while_their_reply_is_unread()
    {
        Assert.Equal(Group.NeedsInput, Board.GroupOf(Session(SessionStatus.NeedsInput)));
        Assert.Equal(Group.Working, Board.GroupOf(Session(SessionStatus.Working)));
        Assert.Equal(Group.Unread, Board.GroupOf(Session(SessionStatus.Idle, unread: true)));
        Assert.Null(Board.GroupOf(Session(SessionStatus.Idle)));
    }

    [Theory]
    [InlineData(0, "now")]
    [InlineData(59_999, "now")]
    [InlineData(180_000, "3 min")]
    [InlineData(7_200_000, "2 h")]
    [InlineData(4 * 86_400_000L, "4 d")]
    [InlineData(-5_000, "now")] // a clock a little ahead in another process
    public void Ago_rounds_down_to_the_largest_unit(long elapsedMs, string expected) => Assert.Equal(expected, Board.Ago(1_000_000_000, 1_000_000_000 + elapsedMs));

    [Fact]
    public void The_second_line_says_when_an_unread_reply_came()
    {
        Assert.Equal("my-air · finished 12 min ago", Board.Detail(Session(SessionStatus.Idle, unread: true), 720_000));
        Assert.Equal("my-air · finished just now", Board.Detail(Session(SessionStatus.Idle, unread: true), 1_000));
        Assert.Equal("my-air", Board.Detail(Session(SessionStatus.Working), 720_000));
        Assert.Equal("new session", Board.Detail(new BoardSession("n", "New session", SessionStatus.Working, false), 0));
    }

    [Fact]
    public void The_header_counts_what_needs_the_user_and_says_why_a_list_is_empty()
    {
        var sessions = new[] { Session(SessionStatus.NeedsInput), Session(SessionStatus.Idle, unread: true), Session(SessionStatus.Idle), Session(SessionStatus.Working) };
        Assert.Equal("2 need you · 1 working", Board.Summary(sessions, true));
        Assert.Equal("1 needs you · 0 working", Board.Summary([Session(SessionStatus.NeedsInput)], true));
        Assert.Equal("0 need you · 0 working", Board.Summary([Session(SessionStatus.Idle), Session(SessionStatus.Idle)], true));
        Assert.Equal("No running sessions", Board.Summary([], true));
        Assert.Equal("No IDE with My Air is running", Board.Summary(sessions, false));
    }

    [Fact]
    public void The_footer_counts_idle_sessions_that_not_even_Recent_lists()
    {
        var idle = new BoardSession("i", "t", SessionStatus.Idle, false);
        var other = new BoardSession("j", "t", SessionStatus.Idle, false);
        var board = new BoardFile([idle, other, new BoardSession("w", "t", SessionStatus.Working, false)], [new RecentSession("i", "t", "p", 0)]);
        Assert.Equal(1, Board.HiddenIdle(board));
    }
}
