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

	/**
	 * 잡기 정렬 = 원작 0x204BF0 (37 문서 §11): 605/606 은 아레스 기술의 이벤트 19 창 [0–1] 로 (0x205B28 의 0x24 검사), 608 MAIBARGRABWIN 은
	 * 이벤트 22 창 [0–0.52] 동안 아레스가 영웅을 synchJoint 관계로 끈다 — 영웅 = 아레스 정면 (z −28 → −3), 마주 봄, 들어 올림 (y +30).
	 * 그 관계에서 영웅 손목이 아레스 손가락 관절에 붙는다 (평균 &lt; 6). 예전 BOSS_TO_HERO "같은 원점·같은 방향" 은 폐기.
	 */
	@Test
	void grabAlignmentTargetSynch() throws Exception {
		BossRig r = spawned("ATHN08A");
		EnemyFighter b = r.fight.boss();
		b.hp = 520;
		until(r, 6, () -> !b.combat().active());
		b.hp = 480;
		assertTrue(until(r, 3, () -> bossMove(r) == 290), r.bossMoves.toString());
		// 정면 40 에서 아레스를 마주 보고 잡기
		r.ctl.position.set(b.pos.x - (float) Math.sin(b.yaw) * 40, b.pos.y, b.pos.z - (float) Math.cos(b.yaw) * 40);
		r.ctl.prevPosition.set(r.ctl.position);
		r.ctl.yaw = b.yaw + (float) Math.PI;
		contact(r, 200, 0);
		assertTrue(until(r, 3, () -> r.fight.sync().active() && r.fight.sync().pairedMove() == 606), r.bossMoves.toString());
		// 606: 아레스 604 의 이벤트 19 (FLAG_4 [0–1]) → 0x205B28 의 0x24 검사에 걸려 아레스가 영웅을 끈다 (0x204BF0, 37 문서 §11.6).
		// 영웅 002 의 605–609 에는 이벤트 19/22 가 없다. 아레스는 끄는 쪽 = 자기 루트 모션 그대로 (MAIBARAutoStruggle zero 고정 → 제자리)
		org.joml.Vector3f bp = new org.joml.Vector3f(b.pos);
		for (int k = 0; k < 30; k++) {
			r.tick();
			assertEquals(1, r.fight.sync().pulling(), "606 이벤트 19 → 아레스가 영웅을 끈다");
		}
		assertEquals(0, b.pos.distance(bp), 1e-3f, "아레스 제자리");
		var t606 = devple.gow.engine.combat.SyncPull.target(b.pos, b.yaw, b.animator(), b.scale, r.ctl.animator(), 1f);
		assertEquals(0, t606.getTranslation(new org.joml.Vector3f()).distance(r.ctl.position), 1f, "606 synchJoint 관계로 수렴: " + r.ctl.position);
		var rel606 = new org.joml.Matrix4f().translation(b.pos).rotateY(b.yaw).invert().transformPosition(new org.joml.Vector3f(r.ctl.position));
		assertTrue(Math.abs(rel606.x) < 5 && rel606.z < -20 && rel606.z > -45, "영웅 = 아레스 정면: " + rel606);
		assertEquals(0, Math.abs(Math.IEEEremainder(r.ctl.yaw - b.yaw - Math.PI, Math.PI * 2)), 0.05, "마주 봄");
		assertTrue(until(r, 15, () -> bossMove(r) == 608), "608: " + r.bossMoves);
		float gap = 0;
		int n = 0;
		boolean pulled = false;
		for (int k = 0; k < 200 && bossMove(r) == 608; k++, r.tick()) {
			float p = b.combat().progress();
			if (r.fight.sync().pulling() == 1) {
				pulled = true;
				assertTrue(p <= 0.53f, "이벤트 22 창 [0–0.52] 안에서만 끌기: " + p);
			}
			if (p > 0.15f && p < 0.5f && r.fight.sync().active()) {
				gap += BossSyncContact.handGap(r.ctl.animator(), r.ctl.world(new org.joml.Matrix4f(), 1), b.animator(), b.world(new org.joml.Matrix4f(), 1));
				n++;
				var rel = new org.joml.Matrix4f().translation(b.pos).rotateY(b.yaw).invert().transformPosition(new org.joml.Vector3f(r.ctl.position));
				assertTrue(Math.abs(rel.x) < 5 && rel.z < 5 && rel.z > -32, "영웅 = 아레스 정면 (z −28 → −3, 608 synchJoint): " + rel);
				assertTrue(rel.y > 25, "들어 올림: " + rel);
				assertEquals(0, Math.abs(Math.IEEEremainder(r.ctl.yaw - b.yaw - Math.PI, Math.PI * 2)), 0.05, "마주 봄");
			}
		}
		assertTrue(pulled, "608 이벤트 22 → 아레스가 영웅을 끈다");
		assertTrue(n > 5);
		assertTrue(gap / n < 6, "손목 ↔ 아레스 관절 평균 " + gap / n);
	}

	/** 600 BARMAIBLADESHITTHROW (아레스 공격 적중 → 영웅 A600): 동기 쌍, 아레스의 이벤트 22 [0–0.35] 동안만 영웅을 끈다. */
	@Test
	void bladesThrowPullsHeroInWindow() throws Exception {
		BossRig r = spawned("ATHN08A");
		EnemyFighter b = r.fight.boss();
		until(r, 6, () -> !b.combat().active());
		b.combat().forceMove(600);
		r.tick();
		assertTrue(until(r, 1, () -> r.hero.active() && r.hero.move().id() == 600), "영웅 600 동기: " + r.heroMoves);
		boolean pulled = false;
		for (int k = 0; k < 120 && bossMove(r) == 600; k++, r.tick()) {
			if (r.fight.sync().pulling() == 1) {
				pulled = true;
				assertTrue(b.combat().progress() <= 0.36f, "창 [0–0.35]: " + b.combat().progress());
			}
		}
		assertTrue(pulled, "600 이벤트 22 → 영웅을 끈다");
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
