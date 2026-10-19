package devple.gow.engine.game;

import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 원본 데이터가 있을 때만 실행 (gow-rom 은 저장소에 없음). */
class TweaksTest {

	private static final Path PERM = Path.of(System.getProperty("gow.rom", "../gow-rom"), "R_PERMK.WAD");

	@Test
	void playerMaxHitPoints() throws Exception {
		Assumptions.assumeTrue(Files.exists(PERM), "gow-rom 없음");
		Tweaks t = new Tweaks();
		t.load(WadFile.load(PERM));
		assertEquals(100f, t.f("/Player/", "Max Hit Points Level 0", -1));
		assertEquals(200f, t.f("/Player/", "Max Hit Points Level 4", -1));
		assertEquals(200f, t.f("/Player/", "Max Magic Level 5", -1));
		assertEquals("SND_TRANSFORM_LP", t.s("/Player/", "God Mode Loop Name", null));
	}
}
