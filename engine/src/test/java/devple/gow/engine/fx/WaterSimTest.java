package devple.gow.engine.fx;

import devple.gow.tools.fx.EnvParams;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SCR_Water 높이장 ({@link WaterSim}, 16 문서 §1.6): 격자 원점 = 소유 GO 위치 (최소 모서리), 물방울 충격 = 쌍선형 s 를 이전 배열에 (0x21A608).
 * 예전 엔진은 위치를 격자 한가운데로 두고 충격을 12 배로 깊게 넣어 ATHN06A 팔각 마당 물에 흰 뾰족한 산 (60 초 최대 72.8) 이 솟았다 (43 §21).
 */
class WaterSimTest {

	private static WadFile wad(String name) throws Exception {
		Path p = Path.of(System.getProperty("gow.rom", "../gow-rom"), name + ".WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return WadFile.load(p);
	}

	private static WaterSim sim(String level) throws Exception {
		WadFile w = wad(level);
		EnvParams.Water water = new EnvParams(w).waters.getFirst();
		float[] corner = LevelFx.ownerPosition(new LevelData(w), water.owner());
		assertNotNull(corner);
		return new WaterSim(water, corner, 7);
	}

	/** ATHN06A gowaternode (4848, 653.5, 1184) + 20 × 12 → 가운데 (5008, 1280) = 팔각 연못 (발판 gomainplatform 4984, 1280). */
	@Test
	void gridCornerIsTheOwnerPosition() throws Exception {
		WaterSim s = sim("ATHN06A");
		assertEquals(4848, s.x0, 0.5f);
		assertEquals(1184, s.z0, 0.5f);
		assertEquals(1280, s.z0 + (s.nz - 1) * s.cellW / 2, 0.5f, "격자 가운데 = 연못 축 z 1280");
		assertEquals(4984, s.x0 + (s.nx - 1) * s.cellW / 2, 30f);
		assertEquals(1284, s.centreZ(), 0.5f, "감쇠 중심 = 위치 + nz·cell·8 (0x219E24)");
		// ATHN05D 물 방 (지도 x −600..−370, z 1530..1900) — 격자 x −609..−345, z 1540..1924
		WaterSim d = sim("ATHN05D");
		assertEquals(-608.8f, d.x0, 0.5f);
		assertEquals(1540, d.z0, 0.5f);
		assertEquals(1924, d.z0 + (d.nz - 1) * d.cellW, 1f);
	}

	/** 충격은 둘레 네 칸에 합 s (쌍선형) 으로 이전 배열에 — 다음 단계에서 −0.99·s 로 나타난다. */
	@Test
	void impulseIsABilinearKickIntoThePreviousField() throws Exception {
		WaterSim s = sim("ATHN06A");
		float px = s.x0 + 10.25f * s.cellW, pz = s.z0 + 5.5f * s.cellW;
		s.impulse(px, pz, 1f);
		s.wave(1 / 60f); // 물방울 없이 파동 한 단계
		assertEquals(-0.99f * 0.75f * 0.5f, s.height(10, 5), 1e-5f);
		assertEquals(-0.99f * 0.25f * 0.5f, s.height(11, 5), 1e-5f);
		assertEquals(-0.99f * 0.75f * 0.5f, s.height(10, 6), 1e-5f);
		assertEquals(-0.99f * 0.25f * 0.5f, s.height(11, 6), 1e-5f);
		float sum = 0;
		for (int z = 0; z < s.nz; z++) {
			for (int x = 0; x < s.nx; x++) {
				sum += s.height(x, z);
			}
		}
		assertEquals(-0.99f, sum, 1e-4f);
		// 경계 칸 (ix = 0 · nx − 2 이상) 은 무시 (0x21A680–0x21A6A0)
		WaterSim t = sim("ATHN06A");
		t.impulse(t.x0 + 0.5f * t.cellW, pz, 1f);
		t.impulse(t.x0 + (t.nx - 2 + 0.5f) * t.cellW, pz, 1f);
		t.wave(1 / 60f);
		assertEquals(0, t.maxAbs(), 0f);
	}

	/**
	 * SCR_Water 머티리얼 "REFLENVM" → "MAT_REFLENVM" 은 WAD 의 MAT_reflenvm (어두운 반사 텍스처, 알파 ≈ 0.62) — 원작 자원 조회 0x17A7B8 → 0x1759F0 은
	 * 이름을 대문자로 (0x175938) 해시해 찾는다. 예전엔 대소문자가 달라 못 찾고 흰 텍스처로 그려 물이 희뿌옜다.
	 */
	@Test
	void waterMaterialIsFoundIgnoringCase() throws Exception {
		Path p = Path.of(System.getProperty("gow.rom", "../gow-rom"), "ATHN05D.WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		devple.gow.engine.asset.WadAssets a = new devple.gow.engine.asset.WadAssets(p);
		EnvParams.Water w = new EnvParams(a.wad()).waters.getFirst();
		assertEquals("REFLENVM", w.material());
		assertEquals("TXR_visualsAthn05D_text", a.materialTexture("MAT_" + w.material()));
	}

	/** 60 초 (세기 1, 초당 30) — 원작 식이면 물결이 10 단위 안 (예전 식 72.8). */
	@Test
	void athn06aPoolStaysLow() throws Exception {
		WaterSim s = sim("ATHN06A");
		float max = 0;
		for (int i = 0; i < 3600; i++) {
			s.step(1 / 60f);
			max = Math.max(max, s.maxAbs());
		}
		assertTrue(max > 0.5f, "물방울이 파문을 만든다: " + max);
		assertTrue(max < 12f, "산처럼 솟지 않는다: " + max);
	}
}
