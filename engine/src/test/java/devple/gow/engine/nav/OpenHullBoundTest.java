package devple.gow.engine.nav;

import devple.gow.engine.level.LevelCollision;
import devple.gow.tools.content.Content;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 43 §54.1-1: 아래로 열린 CDV hull (압력판 — 윗면 + 비스듬한 옆면뿐) 은 원작 충돌 질의 0x139218 의 경계구 거르기로만 끝이 있다.
 * PAND04G 톱날 구덩이 (영상 5 2996–2998) 에 떨어진 영웅이 시작 압력판 gopressplate1 의 끝없는 원뿔 옆면 (y 2147) 에 서지 않고
 * KillMe 바닥 (y 1976, Death) 까지 떨어져야 한다.
 */
class OpenHullBoundTest {

	private static final Path ROM = Path.of(System.getProperty("gow.rom", "../gow-rom"));

	@Test
	void pressPlateConeDoesNotCatchTheHeroInTheSawPit() throws Exception {
		Path p = Content.file(ROM, "PAND04G.WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		LevelData lv = new LevelData(WadFile.load(p));
		assertTrue(Platforms.Body.openBelow(lv.hulls.get("CDV_PressPlate1").hulls.getFirst().planes()), "압력판 hull 은 아래로 열림");
		assertFalse(Platforms.Body.openBelow(List.of(new float[]{0, 1, 0, -1}, new float[]{0, -1, 0, -1}, new float[]{1, 0, 0, -1},
				new float[]{-1, 0, 0, -1})), "아래 평면이 있으면 닫힘");

		Platforms ps = Platforms.of(lv, Set.of(), Set.of());
		// 판 위: 윗면 (관절 2 + 4.9 ≈ 2724)
		Platforms.Support on = ps.floor(-96f, 2740f, -2779f, 40f);
		assertNotNull(on);
		assertEquals("gopressplate1", on.body().owner);
		assertEquals(2724f, on.y(), 2f);
		// 구덩이 위 630 유닛 떨어진 곳: 원뿔 옆면 (y ≈ 2147) 은 경계구 (r 20.4) 밖 — 바닥이 아님
		Platforms.Support pit = ps.floor(-112f, 2700f, -3409f, 4000f);
		assertTrue(pit == null || !pit.body().owner.startsWith("gopressplate"), "구덩이에 압력판 원뿔: " + (pit == null ? null : pit.body().owner + " " + pit.y()));
		// 몸 밀어내기 · 선분도 같음
		Vector3f c = new Vector3f(-112f, 2150f, -3409f);
		Vector3f c0 = new Vector3f(c);
		assertFalse(ps.collideSphere(c, 12f, Set.of()));
		assertEquals(c0, c, "원뿔 안 먼 점을 밀지 않음");

		NavWorld w = new NavWorld(new LevelCollision(lv.sheets));
		w.setPlatforms(ps);
		NavWorld.Floor f = w.floor(-112f, 2700f, -3409f, 4000f);
		assertNotNull(f);
		assertEquals(1975.8f, f.y(), 1f, "KillMe 바닥");
		assertNull(f.platform());
	}
}
