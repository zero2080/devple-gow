package devple.gow.engine.fx;

import devple.gow.tools.fx.FxResources;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.level.LevelLayout;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 커브 이미터 (FXC 서브타입 4, 원작 갱신 0x25EC88 → 생성 0x1249C8): 이미터 +0x6C 모양 이름 → 0xD 방출 지오메트리 노드 → NCV_ 커브. 예전 엔진은
 * 서브타입 3/4 를 통째로 건너뛰어 ATHN05A "불타는 아테네" 의 불벽 (FWcurve/Fwemit 72 개) 이 하나도 없었다 — 영상 6541–6651 의 철책 너머 불·통로 바닥 불·
 * 불타는 문간 (43 문서 §19).
 */
class CurveEmitterTest {

	private static LevelData level(String name) throws Exception {
		Path p = Path.of(System.getProperty("gow.rom", "../gow-rom")).resolve(name + ".WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return new LevelData(WadFile.load(p));
	}

	/** 0xD 노드: 로컬 행렬 +0x10, NCV 이름 +0x58, 모양 이름 +0x70 — ATHN05A 의 커브 이미터 72 개가 모두 노드·커브를 찾는다. */
	@Test
	void athn05aFireWallsResolveTheirCurves() throws Exception {
		LevelData d = level("ATHN05A");
		FxResources fx = new FxResources(d.wad);
		FxResources.Geom g = fx.geoms.get("FWcurve3bmShape6");
		assertNotNull(g);
		assertEquals("FXC_FWcurve3bm6", g.name());
		assertEquals("NCV_FWcurve3bm6", g.curve());
		assertEquals(0, g.joint());
		assertNotNull(d.curves.get(g.curve()), "NCV 리소스");
		FxResources.Emitter fw = fx.emitters.get("FXC_Fwemit3bm6");
		assertEquals(4, fw.subtype());
		assertEquals("FWcurve3bmShape6", fw.geom());
		assertEquals(40f, fw.rate(), 1e-4f);
		assertEquals(1.8f, fw.speed(), 1e-4f);
		assertEquals(0f, fw.tangentSpeed(), 0f);
		assertEquals(0f, fw.normalSpeed(), 0f);
		assertArrayEquals(new float[]{0, 1, 0}, fw.dir(), 1e-6f, "위로 (P+0x00)");
		int curves = 0;
		for (LevelLayout.Placement pl : d.layout.placements()) {
			for (String ref : pl.object().refs("FXC_")) {
				FxResources.Emitter em = fx.emitters.get(ref);
				if (em == null || em.subtype() != 4) {
					continue;
				}
				float[][] c = LevelEmitters.curveSamples(fx, d, em, pl);
				assertNotNull(c, ref);
				int n = c[0].length / 3;
				assertEquals(d.curves.get(fx.geoms.get(em.geom()).curve()).knots.length * LevelEmitters.CURVE_SAMPLES_PER_SEGMENT + 1, n);
				for (int i = 0; i < c[0].length; i++) {
					assertFalse(Float.isNaN(c[0][i]) || Float.isNaN(c[1][i]), ref);
				}
				curves++;
			}
		}
		assertEquals(72, curves, "ATHN05A 커브 이미터 (FW 불벽 72)");
	}

	/** 마당 철책 너머 불 (영상 6551–6561) = 통로 바닥의 불 커브: 표본이 바닥 근처 (y −16 위) 에서 마당 서쪽 불길 자리에 놓인다. */
	@Test
	void courtyardFireCurveLiesInTheBurningAlley() throws Exception {
		LevelData d = level("ATHN05A");
		FxResources fx = new FxResources(d.wad);
		boolean low = false;
		for (LevelLayout.Placement pl : d.layout.placements()) {
			for (String ref : pl.object().refs("FXC_")) {
				FxResources.Emitter em = fx.emitters.get(ref);
				if (em == null || em.subtype() != 4) {
					continue;
				}
				float[][] c = LevelEmitters.curveSamples(fx, d, em, pl);
				for (int i = 0; i < c[0].length; i += 3) {
					float x = c[0][i], y = c[0][i + 1], z = c[0][i + 2];
					if (y > -40 && y < 120 && x > 250 && x < 750 && z > 2900 && z < 3700) {
						low = true; // 마당·덩굴 통로 바닥 쪽 불 (영상 6541 철책 너머, 6578 통로 아래)
					}
				}
			}
		}
		assertTrue(low, "마당 근처 바닥 높이의 불 커브가 있다");
	}
}
