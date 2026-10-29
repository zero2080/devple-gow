package devple.gow.engine.fx;

import devple.gow.engine.game.Tweaks;
import devple.gow.tools.tweak.TweakFile;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 사망 흐림 TimeWarpBlur 999 "DeathFade" (43 §47.2): R_PERMK 값 · 성분 키 · 구간 보간 (0x142E40). */
class TimeWarpBlurTest {

	@Test
	void fieldHashesMatchTheTweakNames() {
		assertEquals(TweakFile.hash("Zoom Start"), TimeWarpBlur.ZOOM_FIELDS[0], "0x1F5160 a1 = 0xF93F06B5");
		assertEquals(TweakFile.hash("Hold Time"), TimeWarpBlur.TIME_FIELDS[1], "0x1F5130 a1 = 0x8777A60E");
		// 성분 키 = Hash127(":" + 성분, 필드) — 덤프의 #3fadfb5a … #3b6dd442
		assertEquals(0x3FADFB5A, CameraFilter.componentKey(TimeWarpBlur.COLOR_FIELDS[0], 'R'));
		assertEquals(0x7AD2F523, CameraFilter.componentKey(TimeWarpBlur.COLOR_FIELDS[1], 'A'));
		assertEquals(0x3B6DD442, CameraFilter.componentKey(TimeWarpBlur.COLOR_FIELDS[3], 'A'));
	}

	@Test
	void deathFadeFromPermk() throws Exception {
		Path p = Path.of(System.getProperty("gow.rom", "../gow-rom")).resolve("R_PERMK.WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		Tweaks tw = new Tweaks();
		tw.load(WadFile.load(p));
		TimeWarpBlur b = TimeWarpBlur.load(tw, TimeWarpBlur.DEATH_FADE);
		assertNotNull(b);
		assertArrayEquals(new float[]{0.52f, 0.4f, 0.3f, 1f}, b.colors[0], 1e-4f);
		assertEquals(0.1f, b.colors[1][3], 1e-4f);
		assertEquals(0f, b.colors[2][3], 1e-6f);
		assertArrayEquals(new float[]{0, 0, 0, 1}, b.colors[3], 1e-6f);
		assertArrayEquals(new float[]{1f, 1.5f, 1f}, b.times, 1e-4f);
		assertArrayEquals(new int[]{128, 128, 256, 64}, b.zooms);
		assertEquals(3.5f, b.duration(), 1e-3f);
		// 구간: 0.5 초 = 키 0→1 반, 1.75 초 = 키 1→2 반 (확대 192), 3 초 = 키 2→3 반, 끝 = 키 3
		assertEquals(0.55f, b.sample(0.5f)[3], 1e-4f);
		assertEquals(0.05f, b.sample(1.75f)[3], 1e-4f);
		assertEquals(192f, b.sample(1.75f)[4], 1e-3f);
		assertEquals(0.5f, b.sample(3f)[3], 1e-4f);
		assertEquals(160f, b.sample(3f)[4], 1e-3f);
		assertArrayEquals(new float[]{0, 0, 0, 1, 64}, b.sample(4f), 1e-6f);
		b.advance(3.4f);
		assertTrue(b.active());
		b.advance(0.2f);
		assertFalse(b.active());
	}
}
