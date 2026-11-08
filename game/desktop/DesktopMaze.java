package maze;

import skarph.core.Engine;
import skarph.core.Screen;
import skarph.desktop.DesktopInput;
import skarph.desktop.Game;
import skarph.desktop.GameConfig;
import skarph.desktop.InputAware;

/** Desktop start of the maze game (Windows, Linux, macOS): the same MazeScreen as on Android, with keyboard and mouse look. */
public final class DesktopMaze implements Game, InputAware {
    private DesktopInput input;

    public GameConfig config() {
        GameConfig c = new GameConfig();
        c.title = "The Maze";
        c.width = 1280;
        c.height = 720;
        c.appName = "the-maze";
        return c;
    }

    public void setInput(DesktopInput input) {
        this.input = input;
    }

    public Screen createScreen(Engine engine) {
        MazeScreen s = new MazeScreen();
        s.setPad(new DesktopPad(input));
        return s;
    }
}
