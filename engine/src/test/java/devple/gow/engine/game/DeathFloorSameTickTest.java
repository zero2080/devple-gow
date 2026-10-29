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
 * 43 §52.1-2: Death 재질 바닥에 내려선 틱에 죽는다 — 원작 영웅 갱신 0x1EBD78 은 이동 바닥 질의 콜백 0x1E1E80 (면 0x40 → 0x29E170) 뒤 같은 갱신의
 * 0x1ECFD8 에서 HP × 100 피해를 주므로, 프레임 끝 CheckPoint 스냅숏 0x1B3070 은 HP &lt; 1 로 미뤄진다. PAND04E 벽 아래 CheckPointGroup1 (1165, 1104, −2838)
 * 바로 옆으로 떨어지면 864 의 Death 구덩이다 — 예전 엔진은 착지 틱 프레임 끝에 기다리던 체크포인트를 그 구덩이 바닥에서 찍어 다시 시작할 때마다 죽었다.
 */
class DeathFloorSameTickTest {

	private static final Path ROM = Path.of(System.getProperty("gow.rom", "../gow-rom"));

	@Test
	void pand04eWallPitIsADeathFloorRightUnderTheCheckpoint() throws Exception {
		Path p = Content.file(ROM, "PAND04E.WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		LevelData lv = new LevelData(WadFile.load(p));
		NavWorld w = new NavWorld(new LevelCollision(lv.sheets));
		Vector3f pit = new Vector3f(1122, 864, -2721); // 예전 엔진이 체크포인트를 찍은 자리 (60 초 경로에서 떨어져 내린 곳)
		assertTrue(GameScene.deathFloorUnder(w, pit), "구덩이 바닥 = Death");
		assertFalse(GameScene.deathFloorUnder(w, new Vector3f(pit).add(0, 100, 0)), "구덩이 위 공중 (떨어지는 동안은 스냅숏이 0x8 로 미뤄짐)");
		assertFalse(GameScene.deathFloorUnder(w, new Vector3f(1140, 1088, -2880)), "벽 아래 턱 (1088) 은 보통 바닥");
		assertFalse(GameScene.deathFloorUnder(w, new Vector3f(887, 928, -3151)), "세이브 자리");
	}
}
