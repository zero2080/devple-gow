package devple.gow.engine.game;

import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GameFlowTest {

	private static GameRules rules;

	@BeforeAll
	static void load() throws Exception {
		Path perm = Path.of(System.getProperty("gow.rom", "../gow-rom"), "R_PERMK.WAD");
		Assumptions.assumeTrue(Files.exists(perm), "gow-rom 없음");
		rules = GameRules.load(WadFile.load(perm));
	}

	@Test
	void newGameToWarp() {
		PlayerState p = new PlayerState(rules);
		GameFlow f = new GameFlow(p);
		f.tick();
		f.tick();
		assertEquals(GameState.SHELL_MENU, f.state());
		assertEquals(List.of("R_PERMK", "R_SHELLK"), f.requiredWads());

		f.shellMenu(GameFlow.MenuChoice.NEW_GAME, 1);
		f.tick();
		assertEquals(GameState.LEVEL_LOADING, f.state());
		assertEquals("ATHN01A", f.level());
		f.levelLoaded();
		f.tick();
		assertEquals(GameState.IN_GAME, f.state());
		assertEquals(List.of("R_PERMK", "R_HERO0", "R_SAVEPT", "R_WPN0_0", "ATHN01A"), f.requiredWads());

		p.addItem(3, 1);
		f.msgLoadWad("Athn01B");
		assertTrue(f.requiredWads().containsAll(List.of("R_LGHTN0", "ATHN01B")));
		f.msgWarp();
		assertEquals("ATHN01B", f.level());
		assertEquals(GameState.LEVEL_LOADING, f.state());
	}

	@Test
	void deathAndRestart() {
		PlayerState p = new PlayerState(rules);
		GameFlow f = new GameFlow(p);
		f.tick();
		f.tick();
		f.shellMenu(GameFlow.MenuChoice.NEW_GAME, 0);
		f.tick();
		f.levelLoaded();
		f.tick();
		f.playerDied();
		assertEquals(GameState.DEAD, f.state());
		assertEquals(1, p.deaths);
		f.menuClosed(GameFlow.MenuChoice.RESTART_CHECKPOINT);
		assertEquals(GameState.LEVEL_LOADING, f.state());
	}
}
