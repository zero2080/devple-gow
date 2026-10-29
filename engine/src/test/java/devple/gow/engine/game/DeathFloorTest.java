package devple.gow.engine.game;

import devple.gow.engine.level.LevelCollision;
import devple.gow.engine.nav.NavWorld;
import devple.gow.tools.combat.CombatFile;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 하데스 낙하 (43 §56): 붉은 허공 아래 ≈ 1000–2000 에 Death 바닥이 있고, FallAnimZone (1019 504) 이 영웅에게 DEATHFALL01 기술을 건다.
 * 기술 중에도 Death 바닥에 닿으면 곧바로 죽는다 (원작 0x1E1E80 → 0x29E170 → 0x1ECFD8) — 엔진 {@link GameScene#deathFloorUnder}.
 */
class DeathFloorTest {

	static final Path ROM = Path.of(System.getProperty("gow.rom", "../gow-rom"));

	private static NavWorld nav(String level) throws Exception {
		Path p = ROM.resolve(level + ".WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return new NavWorld(new LevelCollision(new LevelData(WadFile.load(p)).sheets));
	}

	@Test
	void hadesVoidHasDeathFloor() throws Exception {
		NavWorld c = nav("HDES01C");
		// 칼날 기둥 방 옆 허공 (엔진 낙하 자리) — 바닥 −4836.7 = Death
		NavWorld.Floor f = c.floor(-1293, -4000, 895, 100000);
		assertNotNull(f);
		assertEquals(-4836.7f, f.y(), 0.5f);
		assertTrue(GameScene.deathFloorUnder(c, new Vector3f(-1293, f.y(), 895)), "Death 바닥 위");
		// 칼날 기둥 방 바닥 (체크포인트 CheckPointGroup1) 은 보통 바닥
		assertFalse(GameScene.deathFloorUnder(c, new Vector3f(-1436, -2752, 713)), "보통 바닥");
		// 공중 (바닥 위 100) 은 아님
		assertFalse(GameScene.deathFloorUnder(c, new Vector3f(-1293, f.y() + 100, 895)));
		NavWorld b = nav("HDES01B");
		NavWorld.Floor fb = b.floor(-4693, -3900, 957, 100000);
		assertEquals(-5088.6f, fb.y(), 0.5f);
		assertTrue(GameScene.deathFloorUnder(b, new Vector3f(-4693, fb.y(), 957)));
	}

	/** FallAnimZone 의 1019 504 = 영웅 M504 DEATHFALL01 (R_PERMK 영웅 전투 파일, stateMask 7 — 지상·공중 이동 경로). */
	@Test
	void fallAnimZoneMoveIsDeathFall() throws Exception {
		Path p = ROM.resolve("R_PERMK.WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		CombatFile.Move m = null;
		for (CombatFile cf : CombatFile.fromWad(WadFile.load(p))) {
			for (CombatFile.Move mv : cf.moves()) {
				if (mv.id() == 504) {
					m = mv;
				}
			}
		}
		assertNotNull(m, "M504");
		assertEquals(7, m.stateMask());
		assertNotEquals(0, m.stateMask() & 0x1C, "자유 이동 아님 → 0x1EA5F0 (Death 콜백 0x1E1E80)");
	}
}
