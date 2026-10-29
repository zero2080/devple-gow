package devple.gow.engine.game;

import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 사망 시간 (43 §47.2): HP 사망 뒤 "Death Menu Time" (0x304094 = /Player/ +0x34) 1.0 초, 제목 → 선택지 2.5 초 (0x29F470). */
class DeathTimingTest {

	@Test
	void deathMenuTimes() throws Exception {
		assertEquals(2.5f, GameScene.DEAD_MENU_CHOICE_DELAY, 1e-6f);
		Path p = Path.of(System.getProperty("gow.rom", "../gow-rom")).resolve("R_PERMK.WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		Tweaks tw = new Tweaks();
		tw.load(WadFile.load(p));
		assertEquals(1.0f, tw.f("/Player/", "Death Menu Time", -1f), 1e-6f);
	}
}
