package devple.gow.engine.boss;

import devple.gow.engine.combat.CombatInput;
import devple.gow.engine.combat.EnemyFighter;
import devple.gow.engine.combat.Hittable;
import devple.gow.engine.combat.RepeatPressQte;
import devple.gow.engine.input.PadButton;
import devple.gow.tools.combat.CombatFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 히드라 보스전 (ATHN01E) 헤드리스: 원본 레벨 스크립트가 보스를 스폰·구역·내려찍기·기절을 지시하고,
 * 영웅 ○ 잡기 → 연타 → 돛대 3단계 → 처형(TimedPress + 연타) → KILLWIN → 컷신 a01Mp01 → Goto afterHydraDeath.
 */
class HydraFightTest {

	/** 히드라가 나올 때까지 (인트로 컷신 끝 → 갑판 → 아래 갑판 스폰 볼륨). */
	static BossRig spawned() throws Exception {
		BossRig r = new BossRig(HydraFight.LEVEL);
		r.world.start();
		r.run(0.5f);
		r.movie = "a01Mp03";
		r.movieDone();
		r.run(1f);
		assertTrue(r.log.contains("level GOTO null deckBossBegin"), "인트로 컷신 끝 → Goto deckBossBegin: " + r.log);
		assertTrue(r.enter("spawnDaddyHydra-pCube4413-spawnDaddy"));
		r.run(1f);
		assertNotNull(r.fight.boss(), "SpawnEnemy 250: " + r.log);
		return r;
	}

	/** 영웅 기술 heroMove 의 첫 공격 판정이 보스에 닿은 것처럼 (Combatant.contact 와 같은 순서: 피격자 receive → 공격자 HIT_CONFIRM). */
	static Hittable.Result contact(BossRig r, int heroMove, float damage) {
		EnemyFighter b = r.fight.boss();
		r.hero.setTarget(b);
		if (!r.hero.active() || r.hero.move().id() != heroMove) {
			assertTrue(r.hero.forceMove(heroMove), "영웅 기술 " + heroMove);
			r.tick(); // 기술 이벤트(SET_1B0 공격 자세) 적용
		}
		CombatFile.Attack a = r.hero.move().attacks().getFirst();
		Hittable.Result res = b.receive(a, r.hero, damage, new Vector3f());
		if (res == Hittable.Result.HIT) {
			r.hero.fire(CombatInput.EV_HIT_CONFIRM, -1, null);
		}
		return res;
	}

	static boolean until(BossRig r, float seconds, BooleanSupplier cond) {
		for (int i = 0; i < seconds * 60; i++) {
			if (cond.getAsBoolean()) {
				return true;
			}
			r.tick();
		}
		return cond.getAsBoolean();
	}

	static int bossMove(BossRig r) {
		var c = r.fight.boss().combat();
		return c.active() ? c.move().id() : -1;
	}

	@Test
	void loadsFromLevelData() throws Exception {
		BossRig r = spawned();
		EnemyFighter b = r.fight.boss();
		assertEquals(250, b.ai1());
		assertEquals(400f, b.maxHp(), "AI1 250 HitPoints");
		assertEquals("bha", r.fight.kindFor(250).skeleton().name);
		assertEquals(java.util.List.of("TWK_CombatFile_250", "TWK_CombatFile_251", "TWK_CombatFile_252", "TWK_CombatFile_256"),
				r.fight.kindFor(250).combatFiles());
		assertNotNull(b.brain().tree, "DTre_250");
		// 구역 2 (bossZoneEntry2 → LevelData28 → 1054 → 플레이어 +0x88) → 결정 트리 "Zone 2" → 52 BITERIGHT 위주
		assertTrue(r.hydraZone(2));
		assertTrue(until(r, 6, () -> r.bossMoves.contains(52) || r.bossMoves.contains(51) || r.bossMoves.contains(62)));
		assertEquals(2, r.bridge.playerZone());
		assertEquals("Zone 2", b.brain().lastLeaf().name());
	}

	@Test
	void damageScriptSlamGrabAndFinisher() throws Exception {
		BossRig r = spawned();
		EnemyFighter b = r.fight.boss();
		r.hydraZone(1);
		// 영웅 공격 피해 = 데이터 (COMBO3A 첫 타격 피해 × 배율 1) → 1039 (피해, 남은 HP)
		r.run(0.1f);
		var atk = r.cd.move(71).attacks().getFirst();
		assertEquals(Hittable.Result.HIT, contact(r, 71, atk.damage()));
		assertEquals(400 - atk.damage(), b.hp, 1e-3);
		r.run(0.1f);
		assertTrue(r.bridge.log.contains("1039 250 " + atk.damage() + " " + (400 - atk.damage())), r.bridge.log.toString());
		// HP 250 아래 → bossEnt-bossStunSensor → EvtForce 53/54 내려찍기 (짝/홀)
		contact(r, 71, b.hp - 240);
		assertTrue(until(r, 3, () -> bossMove(r) == 53 || bossMove(r) == 54), "내려찍기 요청: " + r.bridge.log);
		int slam = bossMove(r);
		assertEquals(slam == 53 ? "1019 → 250 기술 53" : "1019 → 250 기술 54",
				r.bridge.log.stream().filter(s -> s.startsWith("1019 → 250")).findFirst().orElse(null));
		for (int round = 1; round <= 3; round++) {
			int s0 = bossMove(r);
			if (round > 1) {
				// 다음 내려찍기: 피해 → 1039 → 스크립트 (짝/홀 HP)
				contact(r, 71, 1);
				assertTrue(until(r, 8, () -> bossMove(r) == 53 || bossMove(r) == 54), "라운드 " + round + " 내려찍기");
				s0 = bossMove(r);
			}
			int s1 = s0;
			assertTrue(until(r, 4, () -> r.fight.boss().combat().progress() >= 0.4f), "잡기 창 0.33–");
			assertEquals(806, r.fight.boss().combat().stanceTag(), "내려찍기 0.35– 자세 806 (좌/우 모두, 데이터)");
			r.run(0.05f);
			contact(r, 200, 0); // 영웅 ○ GRAB(자세 200) 적중
			r.tick();
			int grab = bossMove(r);
			assertEquals(s1 == 53 ? 806 : 802, grab, "보스 EV_HIT_REACT 태그 200 → BHABOSSGRAB*: s1=" + s1 + " boss " + r.bossMoves + " hero " + r.heroMoves);
			r.tick();
			assertEquals(grab, r.hero.move().id(), "영웅 같은 ID 동기: " + r.heroMoves);
			int win = grab == 806 ? 809 : 805;
			assertTrue(until(r, 12, () -> r.bossMoves.contains(win)), "연타 성공 → " + win + " " + r.bossMoves);
			assertEquals(RepeatPressQte.State.PASSED, r.fight.sync().lastResult());
			int stage = round;
			assertTrue(until(r, 8, () -> Integer.valueOf(stage).equals(r.levelData(39))), "돛대 단계 " + r.levelData(39));
			until(r, 10, () -> !r.fight.boss().combat().active() || bossMove(r) < 800 && bossMove(r) != 55);
		}
		// 돛대 3단계 + HP < 150 → BHAbossStun(123) → 120 STUNLOOP (자세 810)
		contact(r, 71, b.hp - 100);
		assertTrue(until(r, 10, () -> bossMove(r) == 120), "기절 루프: " + r.bossMoves);
		assertTrue(r.fight.phases.contains("기절 (○ 처형)"));
		// 영웅 ○ (A800 CIRCLE, 대상 250 자세 810) → COMBO5A 적중 → 810 BHABOSSKILL 동기
		r.hero.setTarget(b);
		r.tick();
		assertEquals(810, b.combat().stanceTag(), "STUNLOOP 자세 810");
		// 영웅이 IDLECOMBAT(80) 이면 ANY_INPUT 분기와 GRAB 분기가 같은 우선순위라 무작위 → 800 이 나올 때까지 다시 누름
		for (int k = 0; k < 20 && !(r.hero.active() && r.hero.move().id() == 800); k++) {
			until(r, 3, () -> !r.hero.active() || r.hero.move().id() == 80);
			r.pad.down.add(PadButton.CIRCLE);
			r.tick();
		}
		assertEquals(800, r.hero.move().id(), "COMBO5A (A800 ○, 대상 250 자세 810)");
		contact(r, 800, 0);
		r.tick();
		assertEquals(810, bossMove(r));
		assertTrue(until(r, 20, () -> r.bossMoves.contains(812)), "TimedPress → 811 연타 → 812: " + r.bossMoves);
		assertTrue(r.fight.sync().timedPassed() >= 1, "TimedPress 251");
		assertTrue(until(r, 3, () -> r.fight.defeated()));
		assertEquals(0f, b.hp, "KILLWIN SELF_HP 1500");
		// 812 끝 → 1047 → trigHydras-EventHydraDie → 컷신 a01Mp01 → 501 → Goto afterHydraDeath
		assertTrue(until(r, 5, () -> "a01Mp01".equals(r.movie)), r.log.toString());
		assertEquals(true, r.host.globalData().get(2), "GlobalData2 = 히드라 처치");
		r.movieDone();
		r.run(1);
		assertTrue(r.log.contains("level GOTO null afterHydraDeath"), r.log.toString());
	}

	@Test
	void grabFailHurtsHero() throws Exception {
		BossRig r = spawned();
		EnemyFighter b = r.fight.boss();
		r.hydraZone(1);
		contact(r, 71, b.hp - 240);
		assertTrue(until(r, 3, () -> bossMove(r) == 53 || bossMove(r) == 54));
		int fail = bossMove(r) == 53 ? 808 : 804;
		until(r, 4, () -> r.fight.boss().combat().progress() >= 0.4f);
		contact(r, 200, 0);
		float hp0 = r.heroHp;
		r.autoMinigame = false;
		for (int i = 0; i < 12 * 60 && !r.bossMoves.contains(fail); i++) {
			r.tick(); // 연타 안 함
		}
		assertTrue(r.bossMoves.contains(fail), "연타 실패 → " + fail + ": " + r.bossMoves);
		assertEquals(RepeatPressQte.State.FAILED, r.fight.sync().lastResult());
		r.run(3);
		assertTrue(r.heroHp <= hp0 - 10f, "영웅 실패 기술 SELF_HP 10: " + hp0 + " → " + r.heroHp);
	}

	@Test
	void hitsNeverKillBoss() throws Exception {
		BossRig r = spawned();
		EnemyFighter b = r.fight.boss();
		contact(r, 71, 1000);
		assertEquals(1f, b.hp, "HP 바닥 1 (사망은 KILLWIN SELF_HP 로만)");
		r.run(2);
		assertFalse(b.dead());
		assertFalse(r.fight.defeated());
	}
}
