package devple.gow.engine.boss;

import devple.gow.engine.combat.EnemyFighter;
import org.junit.jupiter.api.Test;

import static devple.gow.engine.boss.HydraFightTest.bossMove;
import static devple.gow.engine.boss.HydraFightTest.contact;
import static devple.gow.engine.boss.HydraFightTest.until;
import static org.junit.jupiter.api.Assertions.*;

/** 메두사 여왕 (ATHN02D): 스폰 → 시선 석화/탈출 → 회복 → 잡기 원 → MAIMEWGRAB 스틱 회전 → 머리 뜯기 → 메시지 141·보상. */
class MedusaQueenFightTest {

	static BossRig spawned() throws Exception {
		BossRig r = new BossRig(MedusaQueenFight.LEVEL);
		r.world.start();
		r.run(0.5f);
		assertTrue(r.enter("MAmagicIcon-EntVol01-EntrySensor1"), "방 진입 볼륨");
		r.run(1f);
		assertNotNull(r.fight.boss(), "SpawnEnemy 62: " + r.log);
		return r;
	}

	/** 영웅을 메두사 정면 dist 단위에 세움. */
	static void faceOff(BossRig r, float dist) {
		EnemyFighter b = r.fight.boss();
		r.ctl.position.set(b.pos.x - (float) Math.sin(b.yaw) * dist, b.pos.y, b.pos.z - (float) Math.cos(b.yaw) * dist);
		r.ctl.prevPosition.set(r.ctl.position);
	}

	@Test
	void loadsQueen() throws Exception {
		BossRig r = spawned();
		EnemyFighter b = r.fight.boss();
		assertEquals(62, b.ai1());
		assertEquals(10000f, b.maxHp());
		assertEquals("me3", r.fight.kindFor(62).skeleton().name);
		assertEquals(java.util.List.of("TWK_CombatFile_066", "TWK_CombatFile_067", "TWK_CombatFile_068"), r.fight.kindFor(62).combatFiles());
		assertEquals(9800, b.template().circleHp, "Circle Health Threshold");
		assertFalse(b.grabbable());
	}

	@Test
	void gazePetrifiesHeroAndWiggleBreaksOut() throws Exception {
		BossRig r = spawned();
		MedusaQueenFight m = (MedusaQueenFight) r.fight;
		EnemyFighter b = m.boss();
		r.autoMinigame = false;
		until(r, 5, () -> !b.combat().active());
		faceOff(r, 150);
		b.combat().forceMove(20); // MEWBEAMON (DTre "attack!" 의 move 20)
		assertTrue(until(r, 4, m::heroPetrified), "광선 SCR_EyeBeamOn → 석화");
		assertTrue(r.heroMoves.contains(MedusaQueenFight.HERO_FREEZE));
		// 스틱 좌우 흔들기 → FROZENBREAKOUT
		for (int i = 0; i < 120 && m.heroPetrified(); i++) {
			r.pad.lx = (i / 3) % 2 == 0 ? 1 : -1;
			r.tick();
		}
		assertFalse(m.heroPetrified());
		assertTrue(r.heroMoves.contains(MedusaQueenFight.HERO_BREAKOUT));
		assertEquals(1, m.petrified);
	}

	@Test
	void healsBelowRatioThenGrabRipsHead() throws Exception {
		BossRig r = spawned();
		EnemyFighter b = r.fight.boss();
		faceOff(r, 40);
		// HP 비율 ≤ 0.975 → DTre "heal!" → 30 (SELF_HP −50)
		b.hp = 9700; // (방어 40% 라 피해 경로 대신 직접)
		assertEquals(9700f, b.hp, 1e-3);
		// 대상 정지 → 꼬리치기(2) 분기를 피하려 영웅을 조금씩 움직임 (DTre 조건 17 tgtStill)
		int[] k = {0};
		assertTrue(until(r, 12, () -> {
			r.ctl.position.x += (k[0]++ % 2 == 0) ? 1.5f : -1.5f;
			return r.bossMoves.contains(30);
		}), "회복 기술: " + r.bossMoves);
		assertTrue(until(r, 4, () -> b.hp > 9700f), "SELF_HP −50 회복 " + b.hp);
		assertTrue(b.grabbable(), "HP ≤ 9800 → 잡기 원");
		until(r, 6, () -> !b.combat().active() || bossMove(r) == 30);
		until(r, 6, () -> !b.combat().active());
		faceOff(r, 40);
		// 영웅 ○ GRAB 적중 → MAIMEWGRAB(260) 양쪽, TimedPress 060/061 스틱 회전 자동 입력
		contact(r, 200, 0);
		r.tick();
		assertTrue(r.bossMoves.contains(260), "MAIMEWGRAB: " + r.bossMoves + " hero " + r.heroMoves);
		assertTrue(until(r, 10, () -> r.fight.defeated()), "머리 뜯기: " + r.fight.phases + " timed " + r.grab.timedPassed + "/" + r.grab.timedFailed);
		assertTrue(r.grab.timedPassed >= 2, "스틱 회전 2회 성공 " + r.grab.timedPassed + "/" + r.grab.timedFailed + " " + r.bossMoves);
		// 1035(260) → MedusaRipZone-EventCombat1 (5.33초 지연) → LevelData0 = 메두사 사망, 메시지 141
		r.run(7);
		assertEquals(true, r.levelData(0), "LevelData0 (메두사 처치)");
		assertTrue(r.bridge.log.contains("1035 26 260"), r.bridge.log.toString());
	}
}
