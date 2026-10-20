package devple.gow.engine.game;

import devple.gow.tools.content.Content;
import devple.gow.tools.content.Region;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SaveGameTest {

	@Test
	void roundTrip(@TempDir Path tmp) throws Exception {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(Content.file(rom, Region.of(rom).perm())), "gow-rom 없음");
		GameSession s = new GameSession(rom);
		s.player = new PlayerState(GameRules.load(s.permk));
		s.difficulty = 2;
		s.player.addRedOrbs(1234);
		s.player.addItem(3, 1);
		s.player.addItem(10, 6);
		s.player.level.put(Weapon.BLADES, 2);
		s.player.invested.put(Weapon.BLADES, 300);
		s.player.deaths = 7;
		s.scriptHost.globalData().put(5, true);
		s.scriptHost.globalData().put(6, 42);
		SaveGame.capture(s, "ATHN01D", new float[]{1, 2, 3}, 0.5f).write(tmp.resolve("slot0.save"));

		GameSession t = new GameSession(rom);
		SaveGame g = SaveGame.read(tmp.resolve("slot0.save"));
		g.applyTo(t);
		assertEquals("ATHN01D", g.level);
		assertEquals(2, t.difficulty);
		assertEquals(1234, t.player.redOrbs);
		assertEquals(Weapon.POSEIDON, t.player.selectedMagic);
		assertEquals(1, t.player.hpLevel);
		assertEquals(2, t.player.level.get(Weapon.BLADES));
		assertEquals(300, t.player.invested.get(Weapon.BLADES));
		assertEquals(0, t.player.deaths, "불러오기 시 사망 수 초기화 (원작 규칙)");
		assertEquals(true, t.scriptHost.globalData().get(5));
		assertEquals(42, t.scriptHost.globalData().get(6));
	}
}
