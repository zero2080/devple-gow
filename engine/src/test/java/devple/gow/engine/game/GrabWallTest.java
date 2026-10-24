package devple.gow.engine.game;

import devple.gow.engine.level.LevelCollision;
import devple.gow.engine.nav.NavWorld;
import devple.gow.tools.content.Content;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 결합 (잡기) 중 영웅 변위의 벽 처리 (43 문서 §21.2-7): 원작 생물 갱신 0x1EBD78 은 루트 모션을 변위로 내비게이션 이동 (벽 0x1E2100 · 이동 선분 0x1EAB78) 에
 * 넘긴다. 예전 엔진은 결합 중 물리를 건너뛰어, ATHN05D 엘리베이터 방 서쪽 벽 감실 (x −1148..−1132, z 1216..1245) 에서 해골병을 잡아 끄는 동안
 * 영웅이 감실 북쪽 벽을 지나 바닥 없는 바깥 (−1146, 1258) 으로 나가 떨어졌다 (e5 35.78 초).
 */
class GrabWallTest {

	private static final Path ROM = Path.of(System.getProperty("gow.rom", "../gow-rom"));

	@Test
	void dragAlongTheNicheStaysInTheRoom() throws Exception {
		Path f = Content.file(ROM, "ATHN05D.WAD");
		Assumptions.assumeTrue(Files.exists(f), "gow-rom 없음");
		LevelCollision col = new LevelCollision(new LevelData(WadFile.load(f)).sheets);
		NavWorld nw = new NavWorld(col);
		long ex = NavWorld.bodyExclude(true, false, false, true, 0x60, 0); // 동기 잡기 기술 (지상)
		// e5 기록의 끌기: 틱마다 (−0.48, +0.53) 로 1 초
		Vector3f free = new Vector3f(-1125.2f, 864, 1231.9f), walled = new Vector3f(free);
		for (int i = 0; i < 60; i++) {
			free.add(-0.48f, 0, 0.53f);
			Vector3f prev = new Vector3f(walled);
			walled.add(-0.48f, 0, 0.53f);
			GameScene.grabWalls(walled, prev, ex, col, null, null, nw);
		}
		assertTrue(Float.isNaN(col.groundHeight(free.x, 900, free.z, 200)), "벽 처리 없이 = 바닥 없는 바깥 (예전 결함)");
		assertEquals(864f, col.groundHeight(walled.x, 900, walled.z, 200), 0.5f, "벽 처리 = 방 안 바닥");
		assertTrue(walled.x > -1137f || walled.z < 1246f, "감실 북쪽 벽 (z 1239–1245) · 서쪽 벽 (x −1132) 안: " + walled);
	}
}
