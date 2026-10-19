package devple.gow.engine.fx;

import devple.gow.tools.fx.EnvParams;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** SCR_LensFlare (0x20E728 / 0x20E990, 46 문서). */
class LensFlareTest {

	private static Path rom() {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(rom.resolve("OLYMP01.WAD")), "gow-rom 없음");
		return rom;
	}

	@Test
	void levelData() throws Exception {
		Path rom = rom();
		List<LensFlare.Def> o = LensFlare.defs(new EnvParams(WadFile.load(rom.resolve("OLYMP01.WAD"))));
		assertEquals(1, o.size());
		assertEquals("gosunflare", o.getFirst().owner());
		assertEquals(256f, o.getFirst().size());
		assertEquals(8f, o.getFirst().occlusion());
		assertEquals("MAT_starFlare", o.getFirst().material());
		List<LensFlare.Def> g = LensFlare.defs(new EnvParams(WadFile.load(rom.resolve("PAND04G.WAD"))));
		assertEquals(2, g.size());
		assertEquals("MAT_FLARE", g.getFirst().material());
		assertEquals(64f, g.getFirst().occlusion());
		assertEquals(700f, g.get(1).size());
		assertEquals(1, LensFlare.defs(new EnvParams(WadFile.load(rom.resolve("PAND04F.WAD")))).size());
	}

	@Test
	void clipAndRatio() {
		float[] b = LensFlare.clip(320, 224, 32, 640, 448);
		assertArrayEquals(new float[]{288, 192, 352, 256}, b);
		assertEquals(1f, LensFlare.clipRatio(b, 32));
		// 왼쪽 가장자리: 짧은 변 = 32 → 비율 ½
		float[] e = LensFlare.clip(0, 224, 32, 640, 448);
		assertEquals(0.5f, LensFlare.clipRatio(e, 32));
		assertTrue(LensFlare.onScreen(-100, 10, 128, 640, 448));
		assertFalse(LensFlare.onScreen(-200, 10, 128, 640, 448));
		assertFalse(LensFlare.onScreen(320, 448 + 129, 128, 640, 448));
	}

	@Test
	void occlusionIntensity() {
		float[] d = {1f, 1f, 0.5f, 0.2f};
		assertEquals(0.5f, LensFlare.visibleFraction(d, 4, 0.9f));
		assertEquals(1f, LensFlare.visibleFraction(d, 4, 0.1f));
		assertEquals(0f, LensFlare.visibleFraction(d, 0, 0.1f));
		// 알파 0xC0 = 1.5 배, 자름 비율 곱
		assertEquals(1.5f, LensFlare.intensity(1, 1), 1e-6f);
		assertEquals(0.375f, LensFlare.intensity(0.5f, 0.5f), 1e-6f);
		assertEquals(0f, LensFlare.intensity(0, 1));
	}
}
