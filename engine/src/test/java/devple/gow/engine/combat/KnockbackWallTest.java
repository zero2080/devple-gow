package devple.gow.engine.combat;

import devple.gow.engine.level.LevelCollision;
import devple.gow.engine.nav.NavWorld;
import devple.gow.tools.content.Content;
import devple.gow.tools.content.Region;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 밀린·끌린 적의 벽 처리 (10 §7.2): 원작은 밀어내기 속도 +0xF0 (0x1EAEC8) · 루트 모션 D (0x1EBD78) · 동기 끌기 (0x204BF0 → combatant +0x00) 를
 * 모두 같은 프레임 내비 이동 (몸 질의 0x1E2100 · 이동 선분 0x1EAB78) 에 넣어 적이 벽 안에서 프레임을 끝내지 않는다. ATHN05D 복도 서쪽 = 연석 x −1003.1
 * (864–875.6) · 비탈 · 벽 x −1008 (893.7–992), 05C 쪽은 항아리 방 바닥 (864).
 */
class KnockbackWallTest {

	static final float DT = 1 / 60f;
	/** 복도 서쪽 연석 면 (몸이 넘으면 안 되는 선). */
	static final float CURB = -1003.1f;
	private static Path rom;
	private static NavWorld nav;

	@BeforeAll
	static void load() throws Exception {
		rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Path d = rom.resolve("ATHN05D.WAD"), c = rom.resolve("ATHN05C.WAD");
		Assumptions.assumeTrue(Files.exists(d) && Files.exists(c), "gow-rom 없음");
		nav = new NavWorld(new LevelCollision(new LevelData(WadFile.load(d)).sheets), new LevelCollision(new LevelData(WadFile.load(c)).sheets));
	}

	private static EnemyFighter skeleton(Vector3f at) throws Exception {
		Assumptions.assumeTrue(Files.exists(Content.file(rom, Region.of(rom).perm())), "R_PERMK 없음");
		EnemyFactory.Kind k = EnemyFactory.load(rom.resolve("R_SKD.WAD"), WadFile.load(rom.resolve("R_PERMK.WAD")), -1);
		EnemyFighter f = k.spawn(37); // 복도 FirstSetEnemies DiveLevel1AI
		f.pos.set(at);
		f.prevPos.set(at);
		f.holdAi = true;
		f.setNavWorld(EnemyNav.of(nav));
		for (int i = 0; i < 10; i++) {
			f.update(DT);
		}
		return f;
	}

	/**
	 * 서쪽 벽 쪽 넉백: 영웅 기술 피격 충격 최대 (지상 1500 · 공중 2000 → ÷ 질량 100 × 16 = 240 · 320 /초) 와 그 몇 배. 틱이 끝날 때 (그리는 위치) 몸이 연석을
	 * 넘지 않고 벽 + 몸 반지름 (16) 에 기대 멈춘다. 예전 (내비 단계 뒤에 밀어내기를 더함) 은 900 /초에서 −1006.4, 2000 /초에서 넉백 내내 −1023 (벽 속 15).
	 */
	@Test
	void knockbackIntoTheCorridorWallStopsAtTheWall() throws Exception {
		for (float speed : new float[] {240, 320, 900, 2000}) {
			EnemyFighter f = skeleton(new Vector3f(-990, 864, 1500));
			f.combat().impulse().set(-speed, 0, 0);
			float minX = f.pos.x;
			for (int i = 0; i < 180; i++) {
				f.update(DT);
				minX = Math.min(minX, f.pos.x);
			}
			assertTrue(minX > CURB, "속도 " + speed + ": 틱 끝 위치가 연석 안 (최소 x " + minX + ")");
			assertEquals(-1008 + f.nav().bodyRadius(), f.pos.x, 1.5f, "벽 + 몸 반지름에 기댐 " + f.pos);
			assertEquals(864, f.pos.y, 0.5f);
		}
	}

	/** 끌린 위치 (동기 끌기 0x204BF0 이 정한 자리) 가 벽 너머면 같은 틱 벽 처리 ({@link EnemyFighter#settleMotion}) 가 복도 안으로 되돌린다. */
	@Test
	void pulledPastTheWallIsHeldInside() throws Exception {
		EnemyFighter f = skeleton(new Vector3f(-990, 864, 1500));
		f.pos.x = -1030; // 끌기 한 틱 (벽 너머 22)
		f.settleMotion();
		assertTrue(f.pos.x > CURB, "끌린 자리 → 복도 안 " + f.pos);
		f.update(DT);
		assertTrue(f.pos.x > CURB, "다음 틱도 안 " + f.pos);
	}
}
