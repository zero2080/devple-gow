package devple.gow.engine.nav;

import devple.gow.engine.combat.BodyPush;
import devple.gow.engine.combat.EnemyFactory;
import devple.gow.engine.combat.EnemyFighter;
import devple.gow.engine.combat.EnemyNav;
import devple.gow.engine.level.LevelCollision;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ATHN01A 배 갑판 가장자리 (38 §19): 이동 선분 마지막 검사 (0x1EAB78) 가 영웅을 GeneralGuide 막 안에, 적을 갑판 난간 안에 둔다.
 * 갑판 왼쪽 (시작 카메라 화면 왼쪽) 막 = x 1416 (z 1576–1779, 높이 0–176, GeneralGuide·NoAICollision), 난간 = 같은 x 의 Ground 벽 (높이 0–14.6),
 * 고물 쪽 막 = z 1576.
 */
class DeckEdgeTest {

	static final float DT = 1 / 60f;
	static final float LEFT = 1416f;
	private static Path rom;
	private static NavWorld nav;

	@BeforeAll
	static void load() throws Exception {
		rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Path wad = rom.resolve("ATHN01A.WAD");
		Assumptions.assumeTrue(Files.exists(wad), "gow-rom 없음");
		nav = new NavWorld(new LevelCollision(new LevelData(WadFile.load(wad)).sheets));
	}

	@Test
	void heroPushedThroughCornerGuideStaysOnDeck() {
		// 재현 (GameScene 로그): 구석 (1428, 0, 1592) 에 몰린 영웅을 적 몸 밀어내기가 한 틱에 x 1410 까지 밀면
		// 몸 구 (반지름 12) 는 x 1416 막의 바깥쪽으로 빠져 1404 → 바다 (데스존) 로 떨어졌다
		Vector3f from = new Vector3f(1428, 0, 1592);
		Vector3f to = new Vector3f(1410, 0, 1591);
		Vector3f c = new Vector3f(to.x, to.y + 20, to.z);
		for (LevelCollision col : nav.statics()) {
			col.collideSphere(c, 12, NavWorld.HERO_BODY_EXCLUDE);
		}
		assertTrue(c.x < LEFT, "몸 구만으로는 막 밖으로 밀림: " + c.x);
		// 이동 선분 검사: 틱 시작 위치 → 새 위치가 막에 막힌다
		long mx = NavWorld.moveExclude(NavWorld.HERO_BODY_EXCLUDE);
		assertTrue(nav.blockMove(from, to, HeroNavigator.FOOT, mx));
		assertTrue(to.x > LEFT && to.x < LEFT + 1, "막 앞 0.05: " + to.x);
		assertEquals(1591.1f, to.z, 0.5f);
		// 막 앞에서 다시 몸 질의 → 안쪽으로 밀려난다 (GameScene.physics 와 같은 순서)
		Vector3f c2 = new Vector3f(to.x, to.y + 20, to.z);
		for (LevelCollision col : nav.statics()) {
			col.collideSphere(c2, 12, NavWorld.HERO_BODY_EXCLUDE);
		}
		assertEquals(LEFT + 12, c2.x, 0.5f);
		// 갑판 안 이동은 막지 않는다
		Vector3f inside = new Vector3f(1500, 0, 1700);
		assertFalse(nav.blockMove(new Vector3f(1480, 0, 1690), inside, HeroNavigator.FOOT, mx));
		assertEquals(1500, inside.x, 0);
	}

	@Test
	void moveExcludeMatchesOriginalMask() {
		// 0x2030002 | (+0x258 & 0x4300000): 영웅 = Water·AIBlock·ClimbGuide·NoDiving·NoPlayerCollision (+ CombatGuide: 기술 밖) — Death·GeneralGuide 는 막음
		long hero = NavWorld.moveExclude(NavWorld.HERO_BODY_EXCLUDE);
		assertEquals(devple.gow.tools.level.SurfaceAttr.fromRecord(0x02030002 | 0x00100000 | 0x08000000, 0, 0), hero);
		long ai = NavWorld.moveExclude(NavWorld.bodyExclude(false, false, false, false, 0, 0));
		assertEquals(devple.gow.tools.level.SurfaceAttr.fromRecord(0x02030002 | 0x00200000 | 0x08000000, 0, 0), ai);
	}

	private static EnemyFighter skeleton(Vector3f at) throws Exception {
		Assumptions.assumeTrue(Files.exists(Content.file(rom, Region.of(rom).perm())), "R_PERMK 없음");
		EnemyFactory.Kind sks = EnemyFactory.load(rom.resolve("R_SKS.WAD"), WadFile.load(rom.resolve("R_PERMK.WAD")), -1);
		EnemyFighter f = sks.spawn(20);
		f.pos.set(at);
		f.prevPos.set(at);
		f.holdAi = true;
		f.setNavWorld(EnemyNav.of(nav));
		f.update(DT);
		return f;
	}

	@Test
	void skeletonWalkingIntoRailStaysOnDeck() throws Exception {
		// 갑판 왼쪽 난간으로 3 초 걸어감: 몸 구 (발 + 14 위) 는 난간 (14.6) 을 거의 못 보지만 이동 선분 (발 + 6.4) 이 막는다
		EnemyFighter f = skeleton(new Vector3f(1440, 0, 1700));
		for (int t = 0; t < 180; t++) {
			f.nav().update(f.pos, new Vector3f(-1, 0, 0), null, false, DT);
		}
		assertTrue(f.pos.x > LEFT, "난간 안: " + f.pos);
		assertEquals(0, f.pos.y, 0.5f);
	}

	@Test
	void skeletonShovedByCrowdStaysOnDeck() throws Exception {
		// 영웅 몸이 적을 난간 쪽으로 밀어붙임 (구석 1430, 1600): 매 틱 영웅 몸을 −x 로 옮기며 적 이동 단계 (밀어내기 0x1E2DD8 → 벽 → 선분)
		EnemyFighter f = skeleton(new Vector3f(1432, 0, 1640));
		Vector3f heroFeet = new Vector3f(1460, 0, 1640);
		List<BodyPush.Body> crowd = new ArrayList<>();
		crowd.add(BodyPush.hero(heroFeet, () -> false, null));
		crowd.add(f);
		f.setCrowd(() -> crowd);
		for (int t = 0; t < 120; t++) {
			heroFeet.x = Math.max(1400, heroFeet.x - 2f); // 영웅이 난간까지 밀고 들어감 (영웅 자신은 막 너머)
			f.nav().update(f.pos, new Vector3f(0.01f, 0, 0), null, false, DT);
			assertTrue(f.pos.x > LEFT, "t=" + t + " 난간 안: " + f.pos);
		}
	}
}
