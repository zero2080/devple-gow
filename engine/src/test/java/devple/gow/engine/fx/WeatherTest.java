package devple.gow.engine.fx;

import devple.gow.tools.fx.FxResources;
import devple.gow.tools.fx.PtcVu;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** SCR_Weather 원작 경로 (16 문서 §1.5): 비 방향·이동, 볼륨, PTC 속성 배치, 비 VU 식. */
class WeatherTest {

	@Test
	void rainDirectionIsFallVelocity() {
		// 0x21AFD8: normalize(−vec + (0,−10,0)); 이동 = (−vec·16 − (0,160,0))·dt → 같은 방향
		float[] vec = {12, 12, -12};
		float[] d = Weather.rainDir(vec);
		float l = (float) Math.sqrt(12 * 12 + 22 * 22 + 12 * 12);
		assertArrayEquals(new float[]{-12 / l, -22 / l, 12 / l}, d, 1e-5f);
		Weather.Sim s = new Weather.Sim(0, vec, new float[]{70, 50, 63}, new float[]{0, 0, 0}, 1f, 1000);
		float[] m = new float[3];
		s.frameMove(1 / 60f, m);
		assertArrayEquals(new float[]{-192 / 60f, -352 / 60f, 192 / 60f}, m, 1e-4f);
		float ml = (float) Math.sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]);
		for (int k = 0; k < 3; k++) {
			assertEquals(d[k], m[k] / ml, 1e-5f);
		}
		assertEquals(160, s.half, 1e-6f);          // 셀 20 ×8
		assertEquals(0.5f, s.age, 1e-6f);          // 수명 1 − 출생 0.5
		assertTrue(s.inside(0, 399, 0));           // 볼륨 = 소유 ± box×8 (50×8 = 400)
		assertFalse(s.inside(0, 401, 0));
	}

	@Test
	void dustAndPlanktonDrift() {
		Weather.Sim dust = new Weather.Sim(3, new float[]{6, 0, -12}, new float[]{250, 9, 360}, new float[3], 1f, 200);
		float[] m = new float[3];
		dust.frameMove(0.5f, m);
		assertArrayEquals(new float[]{-48, 0, 96}, m, 1e-4f);   // −vec·16·dt, 낙하 없음
		Weather.Sim pl = new Weather.Sim(2, new float[3], new float[]{165, 8.4f, 171}, new float[3], 1f, 500);
		assertEquals(14 * 8, pl.half, 1e-6f);
		assertArrayEquals(new float[3], pl.dir, 0);
	}

	private static FxResources fx(String name) throws Exception {
		Path p = Path.of(System.getProperty("gow.rom", "../gow-rom"), name + ".WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return new FxResources(WadFile.load(p));
	}

	@Test
	void rainPtcLayoutAndShape() throws Exception {
		FxResources.Shape rain = fx("ATHN01A").shapes.get("PTC_rain");
		PtcVu vu = rain.vu();
		assertEquals(8, vu.kind);
		assertEquals(1000, rain.maxCount());
		assertEquals(0x48, rain.gsBlend());                  // 가산
		assertTrue(rain.textured());
		assertEquals(0, vu.recordQword(PtcVu.ATTR_POS));      // (위치, 출생) q0
		assertEquals(3, vu.recordLane(PtcVu.ATTR_BIRTH));
		assertEquals(-1, vu.recordQword(PtcVu.ATTR_VEL));     // 속도는 CPU 스트림 0
		assertEquals(1, vu.recordQword(PtcVu.ATTR_DIR));      // 방향 q1 → 0x224
		float[] dir = Weather.rainDir(new float[]{12, 12, -12});
		float[][] o = vu.eval(new float[][]{{1, 2, 3, 0.5f}, {dir[0], dir[1], dir[2], 0}}, 5, 0.5f, 1 / 60f);
		assertEquals(380.72f * 0.5f - 761.43f * 0.125f, o[0][3], 0.05f);   // 알파 ≈ 95
		assertEquals(134.89f, o[0][0], 0.01f);
		assertArrayEquals(dir, new float[]{o[2][0], o[2][1], o[2][2]}, 1e-6f);
		// 배율 = U(3.17,4.76) + U(30.7,46.0)·t + U(−57.5,−86.3)·t²/2, t = 0.5
		assertTrue(o[1][3] > 3.17f + 15.33f - 10.79f - 1e-3f && o[1][3] < 4.76f + 23.0f - 7.19f + 1e-3f, "배율 " + o[1][3]);
		// 줄기류 (속성 6 = q2) 는 그대로
		FxResources.Shape spark = fx("ATHN01C").shapes.get("PTC_DMChdpart2");
		assertEquals(1, spark.vu().recordQword(PtcVu.ATTR_VEL));
		assertEquals(2, spark.vu().recordQword(PtcVu.ATTR_DIR));
	}

	@Test
	void permanentWeatherShapes() throws Exception {
		FxResources r = fx("R_PERMK");
		FxResources.Shape pl = r.shapes.get("PTC_plankton");
		assertEquals(1, pl.vu().kind);       // 화면 사각형
		assertEquals(500, pl.maxCount());
		assertEquals("MAT_lambert2", pl.material());
		assertEquals(4, r.shapes.get("PTC_dust").vu().kind);   // 원반
		assertEquals(1000, r.shapes.get("PTC_snow").maxCount());
	}
}
