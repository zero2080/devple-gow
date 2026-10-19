package devple.gow.engine.boss;

import devple.gow.engine.combat.EnemyFighter;
import org.junit.jupiter.api.Test;

import static devple.gow.engine.boss.HydraFightTest.bossMove;
import static devple.gow.engine.boss.HydraFightTest.contact;
import static devple.gow.engine.boss.HydraFightTest.until;
import static org.junit.jupiter.api.Assertions.*;

/** 아레스 1단계 (ATHN08A) / 3단계 (ATHN08C). */
class AresFightTest {

	static BossRig spawned(String level) throws Exception {
		BossRig r = new BossRig(level);
		r.world.start();
		r.run(1f);
		assertNotNull(r.fight.boss(), "SpawnEnemy (SpawnAtStart): " + r.log);
		EnemyFighter b = r.fight.boss();
		r.ctl.position.set(b.pos.x - (float) Math.sin(b.yaw) * 60, b.pos.y, b.pos.z - (float) Math.cos(b.yaw) * 60);
		r.ctl.prevPosition.set(r.ctl.position);
		return r;
	}

	static void place(BossRig r, float dist) {
		EnemyFighter b = r.fight.boss();
		r.ctl.position.set(b.pos.x + dist, b.pos.y, b.pos.z);
		r.ctl.prevPosition.set(r.ctl.position);
	}

	@Test
	void stage1LoadsAndPhasesFollowHp() throws Exception {
		BossRig r = spawned("ATHN08A");
		EnemyFighter b = r.fight.boss();
		assertEquals(600, b.ai1());
		assertEquals(4000f, b.maxHp());
		assertEquals(2.0f, b.template().damageMult, 1e-4, "아레스 피해 배율 2.0");
		assertEquals(java.util.List.of("TWK_CombatFile_600", "TWK_CombatFile_602", "TWK_CombatFile_604"), r.fight.kindFor(600).combatFiles());
		String[] want = {"phase 1", "phase 2", "phase 3", "phase 4"};
		float[] hp = {3900, 2900, 1900, 600};
		for (int i = 0; i < 4; i++) {
			b.hp = hp[i];
			int k = i;
			assertTrue(until(r, 30, () -> b.brain().lastLeaf() != null && want[k].equals(b.brain().lastLeaf().name())),
					"DTre_600 " + want[i] + " @HP " + hp[i] + ": " + (b.brain().lastLeaf() != null ? b.brain().lastLeaf().name() : null) + " hp " + b.hp + " " + r.bossMoves);
		}
	}

	@Test
	void stage1CircleThresholdsStagger() throws Exception {
		BossRig r = spawned("ATHN08A");
		EnemyFighter b = r.fight.boss();
		until(r, 6, () -> !b.combat().active());
		b.hp = 2050;
		r.tick();
		b.hp = 1990; // 2000 임계 통과 (Circle Health Threshold 1)
		r.tick();
		r.tick();
		assertEquals(290, bossMove(r), "EV_STUN → A290 BARHITSTAGGER: " + r.bossMoves);
		assertTrue(r.fight.phases.contains("잡기 원 2000"));
	}

	@Test
	void stage1GrabFinisherLeadsToCutsceneAndWarp() throws Exception {
		BossRig r = spawned("ATHN08A");
		EnemyFighter b = r.fight.boss();
		b.hp = 520;
		until(r, 6, () -> !b.combat().active());
		b.hp = 480; // 500 임계 → 290 (원 켬)
		assertTrue(until(r, 3, () -> bossMove(r) == 290), r.bossMoves.toString());
		place(r, 40);
		contact(r, 200, 0); // 영웅 ○ GRAB
		r.tick();
		assertEquals(605, bossMove(r), "EV_HIT_REACT 태그 200 → MAIBARGRABSTART: " + r.bossMoves);
		assertTrue(until(r, 1, () -> r.hero.active() && r.hero.move().id() == 605), "영웅 동기 605: " + r.heroMoves);
		assertTrue(until(r, 15, () -> r.bossMoves.contains(608)), "606 연타 → 608: " + r.bossMoves);
		assertTrue(until(r, 12, () -> r.fight.defeated()), "608 SELF_HP 4000: hp " + b.hp + " " + r.bossMoves);
		assertTrue(r.fight.sync().timedPassed() >= 2, "TimedPress 000 ×2: " + r.fight.sync().timedPassed() + "/" + r.fight.sync().timedFailed());
		assertTrue(until(r, 12, () -> "a07Mp01".equals(r.movie)), "608 완료 1047 → a07Mp01: " + r.log);
		r.movieDone();
		r.run(9);
		assertTrue(r.log.stream().anyMatch(s -> s.startsWith("level LOAD_FOR_WARP ATHN08B")), r.log.toString());
		assertTrue(r.log.stream().anyMatch(s -> s.startsWith("level WARP")), r.log.toString());
	}

	@Test
	void stage3ShowdownFinisher() throws Exception {
		BossRig r = spawned("ATHN08C");
		EnemyFighter b = r.fight.boss();
		assertEquals(601, b.ai1());
		assertEquals(3000f, b.maxHp(), "HitPoints 1500 / MaxHitPoints 3000");
		until(r, 8, () -> !b.combat().active());
		b.hp = 400;
		place(r, 40);
		// 115 BLOCKPARRY 적중 확인 → 620 MAIBARSHOWDOWN (칼 맞대기) — 아레스 쪽에서 시작, 영웅 동기
		b.combat().forceMove(620);
		r.tick();
		assertTrue(until(r, 1, () -> r.hero.active() && r.hero.move().id() == 620), "영웅 620: " + r.heroMoves);
		assertTrue(until(r, 12, () -> r.bossMoves.contains(622)), "621 연타(RepeatPress 601) → 622: " + r.bossMoves + " hero " + r.heroMoves + " qte " + r.fight.sync().lastResult());
		assertTrue(until(r, 10, () -> r.bossMoves.contains(395)), "HP 0 → 390 → 395: hp " + b.hp + " " + r.bossMoves);
		assertTrue(until(r, 3, () -> "Semo10".equals(r.movie)), "1035(395) → Semo10: " + r.log);
		assertTrue(r.fight.defeated());
	}
}
