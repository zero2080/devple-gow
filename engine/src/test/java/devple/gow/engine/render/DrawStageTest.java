package devple.gow.engine.render;

import devple.gow.engine.asset.WadAssets;
import devple.gow.tools.texture.MaterialResource;
import devple.gow.tools.texture.TextureLibrary;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 그림자 합성 앞/뒤 단계 (47 문서 §7.4.2: 0x155B50 마스크 bit 25 · 27 · 40, 층 3). */
class DrawStageTest {

	@Test
	void materialMaskMatchesSortKeySplit() {
		assertFalse(DrawStage.lateMaterial(0x44010080, 0), "GS 0x44 (A = 0, B = 1) = 불투명 단계");
		assertTrue(DrawStage.lateMaterial(0x48090080, 0), "0x48 가산 (B = 2 → bit 27)");
		assertTrue(DrawStage.lateMaterial(0x42010080, 0), "0x42 뺄셈 (A = 2 → bit 25)");
		assertTrue(DrawStage.lateMaterial(0x44010084, 0x100), "flags2 0x100 (bit 40) — 영웅·무기 R_ 머티리얼, *NoShadow 레벨 머티리얼");
		assertTrue(DrawStage.lateMaterial(0x44010080, 0x8), "층 3 (flags2 0x8 → bit 35)");
		assertFalse(DrawStage.lateMaterial(0x44010080, 0x10000000), "MAT_stencil (bit 60) 은 층 첫 키 — 단계 마스크 밖");
		assertFalse(DrawStage.lateMaterial(0x44010080, 0x80000000), "bit 63 무관");
	}

	@Test
	void skipFollowsCurrentStage() {
		int prev = DrawStage.set(DrawStage.EARLY);
		try {
			assertTrue(DrawStage.skip(true));
			assertFalse(DrawStage.skip(false));
			DrawStage.set(DrawStage.LATE);
			assertFalse(DrawStage.skip(true));
			assertTrue(DrawStage.skip(false));
			DrawStage.set(DrawStage.ALL);
			assertFalse(DrawStage.skip(true));
			assertFalse(DrawStage.skip(false));
		} finally {
			DrawStage.set(prev);
		}
	}

	@Test
	void heroDrawsAfterShadowCompositeLevelEnemiesBefore() throws Exception {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(rom.resolve("R_HERO0.WAD")) && Files.exists(rom.resolve("PAND01A.WAD")), "gow-rom 없음");
		// 원작 영웅 머티리얼은 모두 flags2 0x100 → 반투명 단계 = 자기 그림자에 어두워지지 않는다
		TextureLibrary hero = new TextureLibrary(WadFile.load(rom.resolve("R_HERO0.WAD")));
		int heroLate = 0;
		for (MaterialResource m : hero.materials()) {
			if (m.name().startsWith("MAT_mai")) {
				assertTrue(WadAssets.lateStage(m), m.name());
				heroLate++;
			}
		}
		assertTrue(heroLate >= 5, "MAT_maiF* 피부·옷");
		// 레벨 (적 포함) 은 대부분 flags2 0 의 0x44 → 불투명 단계
		TextureLibrary lvl = new TextureLibrary(WadFile.load(rom.resolve("PAND01A.WAD")));
		long early = lvl.materials().stream().filter(m -> !WadAssets.lateStage(m)).count();
		assertTrue(early > lvl.materials().size() / 2, early + " / " + lvl.materials().size());
	}
}
