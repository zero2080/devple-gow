package devple.gow.engine.fx;

import devple.gow.engine.game.Tweaks;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 카메라 필터 템플릿·그라데이션·선택 (45 문서 §1). */
class CameraFilterTest {

	private static Path rom() {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(rom.resolve("R_PERMK.WAD")), "gow-rom 없음");
		return rom;
	}

	private static Tweaks tweaks(String... wads) throws Exception {
		Tweaks tw = new Tweaks();
		for (String w : wads) {
			tw.load(WadFile.load(rom().resolve(w + ".WAD")));
		}
		return tw;
	}

	@Test
	void componentKeysMatchDump() {
		// 덤프의 #24266bed.. = Hash127(":R", 0x0014E3D5) 등 (0x1823F8)
		assertEquals(0x24266bed, CameraFilter.componentKey(0x0014E3D5, 'R'));
		assertEquals(0x24266bdc, CameraFilter.componentKey(0x0014E3D5, 'A'));
		assertEquals(0xa23a1ead, CameraFilter.componentKey(0xB7EB5695, 'R'));
		assertEquals(0x1fb9eab2, CameraFilter.componentKey(0x7947B8AB, 'A'));
	}

	@Test
	void permkTemplatesAndBlendEnumLowByte() throws Exception {
		Tweaks tw = tweaks("R_PERMK");
		CameraFilter none = CameraFilter.load(tw, 0);
		assertEquals("None", none.name());
		assertEquals(CameraFilter.BLEND, none.blendMode(), "1.0 = 0x3F800000 → 하위 바이트 0");
		assertFalse(none.visible());
		CameraFilter dim = CameraFilter.load(tw, 9);
		assertEquals("dimWhiteAdd", dim.name());
		assertEquals(CameraFilter.ADD, dim.blendMode(), "1.0000001 = 0x3F800001 → 1 = 가산 (이름과 일치)");
		assertArrayEquals(new float[]{1, 1, 0.82f, 0.1f}, dim.colors()[0], 1e-4f);
		assertArrayEquals(new float[]{1, 1, 0.82f, 0.15f}, dim.colors()[1], 1e-4f);
		assertTrue(dim.visible());
		CameraFilter bright = CameraFilter.load(tw, 8);
		assertEquals("brightWhiteAdd", bright.name());
		assertEquals(0.75f, bright.colors()[1][3], 1e-4f);
		assertNull(CameraFilter.load(tw, 1), "waterGel 은 레벨 WAD 에만");
		assertEquals(0x48, CameraFilter.GS_ALPHA[CameraFilter.ADD]);
	}

	@Test
	void underwaterCamerasUseWaterGel() throws Exception {
		Tweaks tw = tweaks("R_PERMK", "ATHN04A");
		CameraFilter w = CameraFilter.load(tw, 1);
		assertEquals("waterGel", w.name());
		assertEquals(CameraFilter.BLEND, w.blendMode());
		assertArrayEquals(new float[]{0.3f, 0.7f, 1f, 0.6f}, w.colors()[0], 1e-4f);
		assertArrayEquals(new float[]{0, 0.79f, 1f, 0.1f}, w.colors()[1], 1e-4f);
		assertArrayEquals(new float[]{0, 0.25f, 1f, 0.05f}, w.colors()[2], 1e-4f);
		assertArrayEquals(new float[]{0, 0.5f, 1}, w.points(), 1e-4f);
		assertFalse(w.uniform());
		LevelData d = new LevelData(WadFile.load(rom().resolve("ATHN04A.WAD")));
		assertEquals(1, d.camera("SwimCam1").def().filter(), "CAM 0xB0");
		long none = d.placedCameras.stream().filter(c -> c.def().filter() == 0).count();
		assertTrue(none > 0, "보통 카메라는 0 (None)");
		Tweaks tb = tweaks("R_PERMK", "PAND03A");
		assertEquals("bloodGel", CameraFilter.load(tb, 4).name());
	}

	@Test
	void gradientAndScreenRange() {
		float[][] c = {{1, 0, 0, 1}, {0, 1, 0, 0.5f}, {0, 0, 1, 0}};
		CameraFilter f = new CameraFilter("t", c, new float[]{0.2f, 0.5f, 0.8f}, CameraFilter.BLEND);
		assertArrayEquals(c[0], f.colorAt(0f), 1e-6f);
		assertArrayEquals(c[0], f.colorAt(0.2f), 1e-6f);
		assertArrayEquals(new float[]{0.5f, 0.5f, 0, 0.75f}, f.colorAt(0.35f), 1e-6f);
		assertArrayEquals(c[1], f.colorAt(0.5f), 1e-6f);
		assertArrayEquals(c[2], f.colorAt(0.95f), 1e-6f);
		// 수평 시선: 화면 위/아래 = 0.5 ∓ 반화각/π
		float v = (float) Math.toRadians(60);
		float[] r = CameraFilter.screenRange(0, v);
		assertEquals(0.5f - 30f / 180f, r[0], 1e-5f);
		assertEquals(0.5f + 30f / 180f, r[1], 1e-5f);
		// 45° 내려다봄: 정면 u = 0.75
		float[] d = CameraFilter.screenRange((float) -Math.sqrt(0.5), v);
		assertEquals(0.75f, (d[0] + d[1]) / 2, 1e-5f);
		assertTrue(d[0] < d[1], "화면 위가 더 위쪽 (u 작음)");
		assertEquals(5, CameraFilter.select(5, 1));
		assertEquals(1, CameraFilter.select(-1, 1));
	}

	@Test
	void thunderboltCh1HitFlashesDimWhiteThenStaysDark() throws Exception {
		Tweaks tw = tweaks("R_PERMK");
		String an = "/TweakTemplates/Arrow/003/";
		assertEquals(9, tw.i16(an, "Bright Camera Filter", -2));
		assertEquals(6, tw.i16(an, "Flash Freq", -2));
		LightningScreen s = new LightningScreen(tw);
		float dt = 1 / 60f;
		s.update(false, 0, dt);
		assertEquals(-1, s.filter(), "시작 전 = 카메라 기본");
		s.start(tw.i16(an, "Fog Template", -2), 9, tw.i16(an, "Dark Camera Filter", -2), 6,
				Float.intBitsToFloat(tw.rawHash(an, 0x8ae891cf).getAsInt()));
		int brightFrames = 0;
		for (int i = 0; i < 60; i++) {
			s.update(false, 0, dt);
			if (s.filter() == 9) {
				brightFrames++;
			}
			assertNull(s.fog(), "Fog Template −1");
		}
		assertEquals(3, brightFrames, "0.4초 동안 초당 6번, 각 1프레임");
		assertEquals(0, s.filter(), "끝나도 Dark 0 (\"None\") 이 남는다 — 원작도 되돌리지 않음");
	}
}
