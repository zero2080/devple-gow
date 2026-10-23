package devple.gow.engine.boss;

import devple.gow.engine.combat.EnemyFighter;
import devple.gow.tools.combat.CombatFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ATHN01E 갑판 아기 히드라 (enc3, 템플릿 258 = 그룹 252 / 253 = 그룹 253, 37 문서 §4.4): 원본 레벨 스크립트대로
 * 피해 → 1039 (HP ≤ 90) → 1019 110/114 쓰러짐 → 고정 루프 111/115 → 영웅이 닻 볼륨 spike1/spike2 진입 → 1019 113/117 고정 (LD47/48, LD12 +1)
 * → LD12 ≥ 2 (aftrBabiesCheckpoint, 113/117 이 반복될 때마다 1035 라 하나만 고정돼도 오름).
 */
class BabyHydraTest {

	static BossRig babies() throws Exception {
		BossRig r = new BossRig(HydraFight.LEVEL);
		r.world.start();
		r.run(0.5f);
		r.movie = "a01Mp03";
		r.movieDone();
		r.run(6f);
		assertEquals(2, r.enemies.size(), "302/303 → SpawnEnemy 258, 253: " + r.log);
		return r;
	}

	static int move(EnemyFighter f) {
		return f.combat().active() ? f.combat().move().id() : -1;
	}

	/** 영웅 COMBO3A (71) 첫 판정이 맞은 것처럼, HP 가 limit 이하가 될 때까지. */
	static void beat(BossRig r, EnemyFighter b, float limit) {
		r.hero.setTarget(b);
		for (int k = 0; k < 200 && b.hp > limit; k++) {
			r.hero.forceMove(71);
			r.tick();
			CombatFile.Attack a = r.hero.move().attacks().getFirst();
			b.receive(a, r.hero, Math.max(a.damage(), 6), new Vector3f());
			r.run(0.3f);
		}
	}

	@Test
	void babiesStayInTheirDeckHolesAndArePinnedByTheAnchors() throws Exception {
		BossRig r = babies();
		EnemyFighter b1 = r.enemies.stream().filter(f -> f.template().id == 258).findFirst().orElseThrow();
		EnemyFighter b2 = r.enemies.stream().filter(f -> f.template().id == 253).findFirst().orElseThrow();
		Vector3f home1 = new Vector3f(b1.pos), home2 = new Vector3f(b2.pos);
		// 걷기 클립 없음 (goBHB Navigation ANIWalk "bwalk" 가 ANM 에 없음) → 제자리. 예전엔 대기 클립으로 영웅 발밑까지 미끄러졌다
		assertTrue(b1.stationary && b2.stationary);
		r.ctl.position.set(home1.x + 150, home1.y + 120, home1.z + 60);
		r.run(8f);
		assertTrue(b1.pos.distance(home1) < 1f && b2.pos.distance(home2) < 1f, "뿌리 고정: " + b1.pos + " / " + b2.pos);

		// 미터 슬롯 = AI1 #f3a51122 + 1: 258 → 2, 253 → 3 (§4.3)
		r.run(4f);
		assertEquals(java.util.Set.of(2, 3), r.bridge.meterTargets().keySet());

		beat(r, b1, 90);
		assertTrue(HydraFightTest.until(r, 4, () -> move(b1) == 111), "HP ≤ 90 → 1019 110 PINRIGHT → 111 고정 루프: " + move(b1) + " " + r.bridge.log);
		assertTrue(r.bridge.log.contains("1019 → 252 기술 110"), r.bridge.log.toString());
		float[] p1 = r.fight.autoObjective();
		assertNotNull(p1, "봇 목적지 = 닻 볼륨");
		java.util.List<String> cl = new java.util.ArrayList<>();
		b1.combat().onLog(cl::add);
		assertTrue(r.enter("spike1-spike1entry"));
		assertTrue(HydraFightTest.until(r, 2, () -> move(b1) == 113), "spike1 → 0.25 초 뒤 1019 113 PINRIGHTSLAM: " + move(b1));
		r.run(1f);
		assertEquals(Boolean.TRUE, r.levelData(47));
		assertTrue(((Number) r.levelData(12)).intValue() >= 1, "pinBabyL: 1035 113 마다 LD12 +1 (113 은 AUTO 로 자기 반복 — 원작 0x209488 도 시작마다 1035)");
		assertEquals(113, move(b1), "고정 유지 (AUTO → 113 @0.10) " + cl);
		r.run(3f);
		assertEquals(113, move(b1), "고정 유지 (분기 큐: 요청 0x209378 이 111 의 대기 AUTO 를 비움) " + cl);
		// SELF_HP 15 × 11 → HP 0 이지만 EV_DEATH (block) — 엔진은 HP 1 에서 멈춰 생물이 남는다 (원작은 나중에 1029 InstantDie)
		assertEquals(1f, b1.hp, 1e-3);
		assertFalse(b1.dead());
		assertTrue(r.fight.autoIgnores(b1));

		r.ctl.position.set(home2.x + 150, home2.y + 120, home2.z);
		beat(r, b2, 90);
		assertTrue(HydraFightTest.until(r, 5, () -> move(b2) == 115), "1019 114 PINLEFT → 115: " + move(b2));
		assertTrue(r.enter("spike2-spike2Entry"));
		assertTrue(HydraFightTest.until(r, 2, () -> move(b2) == 117), "spike2 → 1019 117: " + move(b2));
		r.run(1f);
		assertEquals(Boolean.TRUE, r.levelData(48));
		assertTrue(((Number) r.levelData(12)).intValue() >= 2, "두 아기 고정 → aftrBabiesCheckpoint 조건 LD12 ≥ 2");
		float[] next = r.fight.autoObjective();
		assertNotNull(next, "다음 단계: 아래 갑판 spawnDaddyHydra 볼륨");
		assertTrue(next[1] < 700, "아래 갑판 (y 606): " + java.util.Arrays.toString(next) + " b1 " + move(b1) + " b2 " + move(b2));
		assertTrue(r.enter("spawnDaddyHydra-pCube4413-spawnDaddy"));
		r.run(1f);
		assertNotNull(r.fight.boss(), "전역 301 → SpawnEnemy 250 (큰 히드라)");
		assertNull(r.fight.autoObjective());
	}

	@Test
	void stunnedBabyGetsUpAfter18SecondsWithoutTheAnchor() throws Exception {
		BossRig r = babies();
		EnemyFighter b1 = r.enemies.stream().filter(f -> f.template().id == 258).findFirst().orElseThrow();
		r.ctl.position.set(b1.pos.x + 150, b1.pos.y + 120, b1.pos.z + 60);
		beat(r, b1, 90);
		assertTrue(HydraFightTest.until(r, 4, () -> move(b1) == 111));
		r.run(10f);
		assertEquals(111, move(b1));
		// lowHealthSensorStop (1035 110) → recoverTransmit 1019 112 지연 18 초 (LD49 참 · 고정 안 됨) → 112 PINRIGHTGETUP
		assertTrue(HydraFightTest.until(r, 12, () -> r.bridge.log.contains("1019 → 252 기술 112")), r.bridge.log.toString());
		assertNotEquals(111, move(b1));
	}
}
