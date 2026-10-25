package devple.gow.engine.fx;

import devple.gow.tools.exe.Elf;
import devple.gow.tools.exe.R5900;
import devple.gow.tools.fx.EnvParams;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SCR_Water 수면 그리기 (QA vs PS2 영상 3화 3840, PAND02D 수영장, 43 §42):
 * 카메라 반사 벡터의 구면 환경맵 UV (0x21A888), 타원 물 (+0x0C = 1, 0x21A1D0), GO 하나의 SCR_Water 여럿 = 관절 1–5 (헤더 +0x22).
 */
class PoseidonWaterFxTest {

	private static final Path ROM = Path.of(System.getProperty("gow.rom", "../gow-rom"));

	private static String insn(Elf e, int pc) {
		R5900.Insn i = R5900.decode(e.u32(pc), pc);
		return i.mnem() + " " + i.ops();
	}

	/** 위에서 내려다보면 반사 벡터가 위 → UV 가운데 (REFLENVMB 의 밝은 하늘색), 비스듬할수록 바깥 고리 (갈색), 수평 시선은 0.5 ± 0.5/√2. */
	@Test
	void sphereMapUsesViewReflection() {
		float[] uv = new float[2];
		WaterSurface.sphereUv(0, -100, 0, 0, 32, 0, uv); // 바로 위 카메라, 평평한 물
		assertEquals(0.5f, uv[0], 1e-5f);
		assertEquals(0.5f, uv[1], 1e-5f);
		WaterSurface.sphereUv(100, -1e-3f, 0, 0, 32, 0, uv); // 수면을 스치는 시선 (+x)
		assertEquals(0.5f + 0.5f / (float) Math.sqrt(2), uv[0], 1e-3f);
		assertEquals(0.5f, uv[1], 1e-3f);
		WaterSurface.sphereUv(0, -100, 100, 0, 32, 0, uv); // 45° (+z)
		assertTrue(uv[1] > 0.6f && uv[1] < 0.854f, "중간 고리 " + uv[1]);
		assertEquals(0.5f, uv[0], 1e-5f);
	}

	/** 타원 물: 내접 타원 밖 칸 (모서리) 은 그리지 않고 가운데 칸은 그린다. */
	@Test
	void ellipseWaterDropsCorners() {
		int n = 19; // PAND02D gowaterfountain 8.88 × 8.88 / 0.5
		assertFalse(WaterSurface.insideEllipse(0, 0, n, n));
		assertFalse(WaterSurface.insideEllipse(n - 2, n - 2, n, n));
		assertTrue(WaterSurface.insideEllipse(9, 9, n, n));
		assertTrue(WaterSurface.insideEllipse(0, 9, n, n));
		int kept = 0;
		for (int z = 0; z < n - 1; z++) {
			for (int x = 0; x < n - 1; x++) {
				kept += WaterSurface.insideEllipse(x, z, n, n) ? 1 : 0;
			}
		}
		assertEquals(Math.PI / 4, kept / (double) ((n - 1) * (n - 1)), 0.05, "원 넓이 비율");
	}

	/** 0x21A454: 물 정의 +0x0C == 1 이면 타원 판정, 0x21A4E8 이 ADC 0x8000. 0x21AAC0: 2048 (0x4500) = ST 가운데. */
	@Test
	void ellipseAndSphereMapCode() throws Exception {
		Path p = ROM.toAbsolutePath().normalize().resolveSibling("work").resolve("SCKA_300.02");
		Assumptions.assumeTrue(Files.exists(p), "work/SCKA_300.02 없음");
		Elf e = Elf.load(p);
		assertTrue(insn(e, 0x21A45C).contains("0xc("), insn(e, 0x21A45C));
		assertTrue(insn(e, 0x21A4E8).contains("0x8000"), insn(e, 0x21A4E8));
		assertTrue(insn(e, 0x21AABC).contains("0x4500"), insn(e, 0x21AABC));
		// 카메라 관리자 0x29F16C +8 = 지금 카메라 노드 (반사 시선의 눈)
		assertTrue(insn(e, 0x219DCC).contains("-0xe94("), insn(e, 0x219DCC));
	}

	/** PAND02D gowaterdynamic 의 SCR_Water 다섯 = 관절 1–5 (각 물 조각의 최소 모서리), 분수는 타원. 예전엔 둘째부터 소유가 " SCR_Water" 라 버려졌다. */
	@Test
	void multipleWatersOnOneObject() throws Exception {
		Path p = ROM.resolve("PAND02D.WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		WadFile wad = WadFile.load(p);
		List<EnvParams.Water> ws = new EnvParams(wad).waters;
		List<EnvParams.Water> dyn = ws.stream().filter(w -> w.owner().equals("gowaterdynamic")).toList();
		assertEquals(5, dyn.size());
		for (int i = 0; i < 5; i++) {
			assertEquals(i + 1, dyn.get(i).joint());
		}
		EnvParams.Water fountain = ws.stream().filter(w -> w.owner().equals("gowaterfountain")).findFirst().orElseThrow();
		assertEquals(1, fountain.flag());
		assertEquals(0, fountain.joint());
		LevelData d = new LevelData(wad);
		float[] c1 = LevelFx.ownerPosition(d, "gowaterdynamic", 1), c3 = LevelFx.ownerPosition(d, "gowaterdynamic", 3);
		assertEquals(1248, c1[0], 1);
		assertEquals(-3296, c1[2], 1);
		assertEquals(768, c3[0], 1);
		assertEquals(-2928, c3[2], 1);
		assertEquals(241, c1[1], 1, "수면 높이 = Water 충돌 면 240");
	}
}
