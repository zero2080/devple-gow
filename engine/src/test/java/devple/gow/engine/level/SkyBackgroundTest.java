package devple.gow.engine.level;

import devple.gow.engine.fx.FxEnvironment;
import devple.gow.tools.fx.EnvParams;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 시각 QA (43 문서): 하늘 = SCR_Sky 대상 오브젝트, 지오메트리 없는 배경의 안개 계수. */
class SkyBackgroundTest {

	private static WadFile wad(String name) throws Exception {
		Path p = Path.of(System.getProperty("gow.rom", "../gow-rom"), name + ".WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return WadFile.load(p);
	}

	private static List<String> skyInstances(String level) throws Exception {
		WadFile w = wad(level);
		Set<String> names = LevelScene.skyNames(new EnvParams(w));
		LevelData d = new LevelData(w);
		return d.layout.placements().stream().filter(p -> p.model() != null && LevelScene.isScrSky(p, names))
				.map(p -> p.instance().name().toLowerCase(Locale.ROOT)).sorted().toList();
	}

	@Test
	void skyIsTheScrSkyObject() throws Exception {
		// 이름에 sky 가 없거나 (group1224, group3508) 원점 중심 휴리스틱에 걸리지 않던 하늘 (이전: 지오메트리로 그려 화면 구석이 비었다)
		assertEquals(List.of("goskylr"), skyInstances("ATHN05A"));
		assertEquals(List.of("goskybattlefx", "goskysea"), skyInstances("ATHN02B"));
		assertEquals(List.of("gogroup3508", "goshitboxl"), skyInstances("PAND04E"));
		assertEquals(List.of("gogroup1224"), skyInstances("PAND01BB"));
		assertEquals(List.of("gonightsky"), skyInstances("ATHN01A"));
		// 템플릿 MDL_group2 에 붙은 SCR_Sky (이름에 sky 없음) + 본문 없는 인스턴스 SCR_Sky 의 소유 GO (도시 위 거대한 아레스, 43 §16):
		// 원작 0x218848 은 스크립트가 붙은 GO 를 옮길 뿐 본문을 읽지 않는다 — MDL_AresAnimGroup1 템플릿에는 SCR_Sky 가 없다
		assertEquals(List.of("goaresanimgroup1", "gogroup2"), skyInstances("ATHN03A"));
		assertEquals(List.of("goaresanimgroup1"), skyInstances("ATHN03AA").stream().filter(n -> n.contains("ares")).toList());
	}

	/** 하늘 GO 의 SCR_Sky 소유자 목록 ({@link EnvParams#skyOwners}): ATHN03A 는 group2 (템플릿) 와 aresanimgroup1 (인스턴스 SCR_Sky). */
	@Test
	void instanceScrSkyOwners() throws Exception {
		EnvParams a3 = new EnvParams(wad("ATHN03A"));
		assertTrue(a3.skyOwners.stream().anyMatch(o -> o.equalsIgnoreCase("goaresanimgroup1")), "SCR_Sky 소유 GO: " + a3.skyOwners);
	}

	@Test
	void backgroundFogFactor() throws Exception {
		// DEST01 모래 폭풍: Fog_A 1, 블렌드 0 → 0x1275E0 경로, 배경 = 안개색 전부
		EnvParams dest = new EnvParams(wad("DEST01"));
		assertEquals(1f, FxEnvironment.backgroundFactor(dest.fogs.getFirst(), null), 1e-6f);
		// ATHN07: Fog_A 0.6, BackPlane 100 → 배경 알파 100/128
		EnvParams a7 = new EnvParams(wad("ATHN07"));
		assertEquals(100f / 128, FxEnvironment.backgroundFactor(a7.fogs.getFirst(), null), 1e-6f);
		// ATHN01A 층 안개 BackPlane 95
		EnvParams a1 = new EnvParams(wad("ATHN01A"));
		assertEquals(95f / 128, FxEnvironment.backgroundFactor(null, a1.layeredFogs.getFirst()), 1e-6f);
		// PAND01A goholefog1: Fog_A 0.8 · BackPlane −1 → 배경 안 칠함
		EnvParams p1 = new EnvParams(wad("PAND01A"));
		assertTrue(FxEnvironment.backgroundFactor(p1.fogs.getFirst(), null) < 0);
		assertTrue(FxEnvironment.backgroundFactor(null, null) < 0);
	}

	@Test
	void athn01dDeckPlanksAreTranslucentOverCloudReflection() throws Exception {
		// 갑판 판자 (p28 0.967 → 정렬 플래그 0x8000 = 반투명) 가 0.4 아래 구름 반사 평면 (불투명 + 알파 테스트) 위에 있다:
		// 반투명 패스가 모든 불투명 뒤여야 판자가 보인다 (이전: 모델마다 두 패스 → 뒤 인스턴스 구름이 판자를 덮어 갑판이 베이지 단색)
		Path p = Path.of(System.getProperty("gow.rom", "../gow-rom"), "ATHN01D.WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		try (devple.gow.engine.asset.WadAssets a = new devple.gow.engine.asset.WadAssets(p)) {
			assertEquals(devple.gow.engine.asset.RenderModel.Blend.ALPHA, a.classify(a.material("MAT_2woodPlanks06aTrans"), true));
			assertEquals(devple.gow.engine.asset.RenderModel.Blend.CUTOUT, a.classify(a.material("MAT_1sfxCloudReflection"), true));
		}
		LevelData d = new LevelData(WadFile.load(p));
		float planks = Float.NaN, cloud = Float.NaN;
		for (var pl : d.layout.placements()) {
			if (pl.instance().name().equals("gostartdeckhull")) {
				planks = pl.instance().pos()[1];
			} else if (pl.instance().name().equals("gostartdeckhull1")) {
				cloud = pl.instance().pos()[1];
			}
		}
		assertEquals(0f, planks, 1e-3f);
		assertTrue(cloud < -0.3f, "구름 반사 인스턴스가 판자보다 아래");
	}
}
