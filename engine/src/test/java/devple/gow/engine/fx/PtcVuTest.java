package devple.gow.engine.fx;

import devple.gow.tools.fx.FxResources;
import devple.gow.tools.fx.PtcVu;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** PTC VU 블록 식 해석 (16 문서 §2.4) 과 안개 깊이 알파 (§1.2) — 헤드리스. */
class PtcVuTest {

	private static FxResources fx(String name) throws Exception {
		Path p = Path.of(System.getProperty("gow.rom", "../gow-rom"), name + ".WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return new FxResources(WadFile.load(p));
	}

	@Test
	void bloodSpriteProgram() throws Exception {
		FxResources.Shape s = fx("R_PERMK").shapes.get("PTC_SKLpart");
		assertNotNull(s);
		PtcVu vu = s.vu();
		assertEquals(3, vu.kind);
		assertArrayEquals(new int[]{7, 8}, vu.tables[0]);   // 레코드 (위치, 속도) → 슬롯 7, 8
		assertArrayEquals(new int[]{0}, vu.tables[1]);      // 출생 시각 → 슬롯 0.w
		assertArrayEquals(new int[]{3, 4, 2, 2}, vu.tables[6]);
		assertEquals(0x44, s.gsBlend());                   // flags 0x8680 → 알파 블렌드
		assertTrue(s.textured());
		assertTrue(s.textureAlpha());
		assertEquals(1024, vu.tailBits(1, 0));               // UV 64 텍셀 ×16
		float t = 0.35f;
		float[][] o = vu.eval(new float[][]{{10, 20, 30, 0}, {0, 16, 0, 0}}, 7, t, 1 / 60f);
		assertEquals(63.225f - 66.234f * t, o[0][0], 0.01f);             // R
		assertEquals(200.21f - 286.01f * t, o[0][3], 0.01f);             // A (0x80 = 1.0)
		assertEquals(20 + 16 * t - 112 * t * t / 2, o[1][1], 1e-3f);     // 위치 y (중력 −112)
		assertEquals(0.87278f + 8.4156f * t, o[1][3], 1e-3f);            // 반폭 (월드)
		assertTrue(Math.abs(o[2][3]) <= Math.PI + Math.PI * t + 1e-3);   // 회전: 난수 ±π + ±π·t
	}

	@Test
	void blendAndKinds() throws Exception {
		FxResources p = fx("PAND01A");
		FxResources.Shape fire = p.shapes.get("PTC_Tfirepart");
		assertEquals(0x48, fire.gsBlend());                // flags 0x691 &3 = 1 → 가산
		float[][] o = fire.vu().eval(new float[][]{{0, 0, 0, 0}, {0, 4, 0, 0}}, 1, 0.5f, 1 / 60f);
		assertEquals(255, o[0][3], 1e-3f);                 // 2966·t(1−t) → 0..255 포화
		assertEquals(7.127f * 0.5f, o[1][3], 1e-3f);       // 7.127 − 7.127 t
		FxResources r = fx("R_PERMK");
		assertEquals(0x44, r.shapes.get("PTC_rslicepart").gsBlend()); // 돌 조각 0x680 → 알파 (가산 아님)
		PtcVu orb = r.shapes.get("PTC_HOgpart").vu();
		assertEquals(4, orb.kind);                          // 원반: 중심색 0x222, 테두리 0x223, 반지름 0x224.w
		assertEquals(8, orb.fanSegments());
		float[][] g = orb.eval(new float[][]{{0, 0, 0, 0}, {0, 0, 0, 0}}, 1, 0.1f, 1 / 60f);
		assertEquals(8.8f - 24f * 0.1f, g[2][3], 1e-3f);
		assertEquals(57.885f, g[1][0], 1e-3f);
		PtcVu spot = r.shapes.get("PTC_BloodSpart").vu();
		assertEquals(9, spot.kind);
		assertEquals(1f, spot.tailFloat(3, 0), 0);          // 모서리 계수 (1,1) / (−1,−1)
		assertEquals(-1f, spot.tailFloat(4, 1), 0);
	}

	@Test
	void fogDepthAlpha() {
		// ATHN07 CentreZ 37136: z' = 0x7FFF 경계 ≈ C·n/2048 = 181 → 그 안쪽은 안개 없음, 멀수록 127/128 에 접근
		assertEquals(0f, FxEnvironment.depthAlpha(37136, 150, 10000), 0);
		float a400 = FxEnvironment.depthAlpha(37136, 400, 10000);
		float a2000 = FxEnvironment.depthAlpha(37136, 2000, 10000);
		assertTrue(a400 > 0.4f && a400 < 0.6f, "a400=" + a400);
		assertTrue(a2000 > a400 && a2000 < 1f);
		assertEquals(127 / 128f, FxEnvironment.depthAlpha(37136, 9999, 10000), 1e-6f);
	}
}
