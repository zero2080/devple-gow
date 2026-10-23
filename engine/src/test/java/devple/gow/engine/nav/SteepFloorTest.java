package devple.gow.engine.nav;

import devple.gow.engine.level.LevelCollision;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 원작 바닥 질의 0x1E3298 의 면 조건 = 법선 · 위 &gt; 0.1 (0x1E3568) — 엔진 일반 바닥 (법선 Y ≥ 0.7) 보다 가파른 비탈도 발밑이면 바닥.
 * ATHN02B 사이클롭스 바닥: 945 단 → 937 단 경사 띠 (x 32–48, z 2434–2466, 법선 Y 0.696) 가 0.7 기준에선 폭 8 의 구멍이라
 * 사이클롭스 공 휘두르기 (CY2SHOOTOUT) 에 밀려난 영웅이 돌아오다 이 띠를 밟고 데스존으로 떨어졌다 (자동 플레이 ATHN02B, bc258e3 이후).
 */
class SteepFloorTest {

	@Test
	void cyclopsFloorRampIsFloorForTheOriginalQuery() throws Exception {
		Path wad = Path.of(System.getProperty("gow.rom", "../gow-rom")).resolve("ATHN02B.WAD");
		Assumptions.assumeTrue(Files.exists(wad), "gow-rom 없음");
		LevelCollision col = new LevelCollision(new LevelData(WadFile.load(wad)).sheets);
		NavWorld nw = new NavWorld(col);
		Vector3f p = new Vector3f(37.7f, 944.8f, 2456.2f); // 자동 플레이에서 영웅이 떨어진 자리
		float d = 3.2f, top = p.y + Math.max(d - 6.4f, 0f), drop = top - (p.y - 6.4f - d);
		// 엔진 일반 바닥: 발밑 (0x1E3298 선분 범위) 에 없음 — 그 아래 데스존까지 비어 있다
		assertTrue(Float.isNaN(nw.groundHeight(p.x, top, p.z, drop)), "법선 Y 0.7 기준 바닥 없음");
		LevelCollision.Floor f = col.floorAt(p.x, top, p.z, drop, LevelCollision.QUERY_NY, t -> true);
		assertNotNull(f, "원작 기준 (법선 · 위 > 0.1) 바닥");
		assertTrue(f.tri().n().y() > 0.6f && f.tri().n().y() < LevelCollision.FLOOR_NY, "경사 띠 법선 Y " + f.tri().n().y());
		float s = nw.steepGround(p.x, top, p.z, drop);
		assertEquals(f.y(), s, 1e-3f);
		assertEquals(941f, s, 4f, "945 → 937 사이 높이");
		// 띠 전체: 원작 질의는 구멍이 없다
		for (float z = 2436; z <= 2456; z += 2) {
			for (float x = 34; x <= 46; x += 1) {
				float h = nw.groundHeight(x, 960, z, 40);
				if (Float.isNaN(h)) {
					assertFalse(Float.isNaN(nw.steepGround(x, 948, z, 16)), "경사 띠 " + x + "," + z);
				}
			}
		}
	}
}
