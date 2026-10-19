package devple.gow.engine.boss;

import devple.gow.engine.combat.EnemyFighter;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static devple.gow.engine.boss.HydraFightTest.until;
import static org.junit.jupiter.api.Assertions.*;

/** ATHN08B 가족 보호 (37 문서 §9): 가족 660 리소스·고정, 포옹(865 동기·HP 이동·메시지 454), 분신 공격 휘말림, 가족 사망 → 실패 컷신. */
class EvilKratosFightTest {

	static BossRig spawned() throws Exception {
		BossRig r = new BossRig(EvilKratosFight.LEVEL);
		r.world.start();
		r.run(0.5f);
		assertNotNull(r.fight.boss(), "familyEnt-family60 SpawnAtStart: " + r.log);
		return r;
	}

	static int move(EnemyFighter f) {
		return f.combat().active() ? f.combat().move().id() : -1;
	}

	@Test
	void familyLoadsAndStaysPut() throws Exception {
		BossRig r = spawned();
		EnemyFighter fam = r.fight.boss();
		assertInstanceOf(EvilKratosFight.class, r.fight);
		assertEquals(660, fam.ai1());
		assertEquals(100f, fam.maxHp());
		assertEquals("xmc", r.fight.kindFor(660).skeleton().name, "CombatFile 660 의 XMC* 애니 = 스켈레톤 xmc");
		assertEquals(java.util.List.of("TWK_CombatFile_660", "TWK_CombatFile_661", "TWK_CombatFile_662"), r.fight.kindFor(660).combatFiles());
		Vector3f p0 = new Vector3f(fam.pos);
		// 1019(0) EvtForce (familyEnt-forceMove0, 0.1초) → M0 XMCSTANDIDLE → M1 XMCTAUNT
		assertTrue(until(r, 4, () -> move(fam) == 1), "M0 → M1 XMCTAUNT: " + r.fight.boss().combat().move());
		r.ctl.position.set(p0.x + 30, p0.y, p0.z);
		r.run(2);
		assertEquals(0, fam.pos.distance(p0), 1e-3f, "이동 애니 없음 → 제자리");
		// 영웅 공격은 가족에게 피해 없음 (661 반응 태그 0..20 전부 block)
		float hp = fam.hp;
		r.fight.contact(0, 30); // COMBO7A 첫 판정
		r.run(0.2f);
		assertEquals(hp, fam.hp, "영웅 공격 → 피해 없음");
	}

	@Test
	void hugTransfersHealth() throws Exception {
		BossRig r = spawned();
		EvilKratosFight fight = (EvilKratosFight) r.fight;
		EnemyFighter fam = fight.family();
		assertTrue(until(r, 4, () -> move(fam) == 1));
		fam.hp = 40;
		float heroHp = r.heroHp;
		r.fight.contact(EvilKratosFight.GRAB_STANCE, 0); // ○ GRAB → 가족 M1 EV_HIT_REACT tag 200 → 865, 영웅 HIT_CONFIRM tgt=660 → 865
		assertTrue(until(r, 1, () -> r.fight.sync().active() && r.fight.sync().pairedMove() == EvilKratosFight.HUG_START),
				"포옹 동기: hero " + r.heroMoves + " fam " + move(fam));
		r.run(0.3f); // 자세 섞임(페이드) 뒤
		// synchJoint 정렬: 가족 정면 48, 가족을 마주 봄
		Vector3f front = new Vector3f((float) -Math.sin(fam.yaw), 0, (float) -Math.cos(fam.yaw)).mul(48).add(fam.pos);
		assertEquals(0, new Vector3f(r.ctl.position.x, 0, r.ctl.position.z).distance(front.x, 0, front.z), 2f, "영웅 위치 " + r.ctl.position);
		assertEquals(0, Math.abs(Math.IEEEremainder(r.ctl.yaw - fam.yaw - Math.PI, Math.PI * 2)), 1e-3, "마주 봄");
		r.pad.down.add(devple.gow.engine.input.PadButton.CIRCLE);
		for (int i = 0; i < 90; i++) {
			r.pad.down.add(devple.gow.engine.input.PadButton.CIRCLE); // 866 루프: ○ 유지
			r.tick();
		}
		assertTrue(fam.hp >= 40 + 24 - 1e-3f, "가족 SELF_HP −2 ×12 회복: " + fam.hp);
		assertTrue(r.heroHp <= heroHp - 24 + 1e-3f, "영웅 SELF_HP +2 ×12: " + r.heroHp);
		assertTrue(r.log.contains("message 454"), "1035(865) → hugMsg 454: " + r.log);
		assertEquals(1, fight.hugs());
		// ○ 를 놓으면 867 → 가족은 대기 순환으로 돌아감
		assertTrue(until(r, 4, () -> !r.fight.sync().active() && move(fam) <= 1), "포옹 끝: fam " + move(fam));
	}

	@Test
	void clonesHitFamilyAndDeathFails() throws Exception {
		BossRig r = spawned();
		EnemyFighter fam = r.fight.boss();
		// 웨이브 1 분신 666 (giveAthenaSword CreationSensor → 2..5초 지연 SpawnEnemy)
		assertTrue(until(r, 8, () -> r.enemies.stream().anyMatch(e -> e.ai1() == 666)), r.log.toString());
		EnemyFighter clone = r.enemies.stream().filter(e -> e.ai1() == 666).findFirst().orElseThrow();
		// 분신을 가족 옆에, 영웅은 멀리: 분신 칼날 공격(1)이 가족에게도 판정
		assertTrue(until(r, 4, () -> move(fam) == 1));
		r.ctl.position.set(fam.pos.x - (float) Math.sin(fam.yaw) * 70, fam.pos.y, fam.pos.z - (float) Math.cos(fam.yaw) * 70);
		clone.pos.set(fam.pos.x - (float) Math.sin(fam.yaw) * 30, fam.pos.y, fam.pos.z - (float) Math.cos(fam.yaw) * 30);
		clone.yaw = fam.yaw + (float) Math.PI;
		float hp = fam.hp;
		boolean hit = false;
		for (int k = 0; k < 20 && !hit; k++) {
			clone.pos.set(fam.pos.x - (float) Math.sin(fam.yaw) * 30, fam.pos.y, fam.pos.z - (float) Math.cos(fam.yaw) * 30);
			clone.yaw = fam.yaw + (float) Math.PI;
			clone.combat().forceMove(1 + k % 3);
			hit = until(r, 1.5f, () -> fam.hp < hp);
		}
		assertTrue(hit, "분신 공격 휘말림: fam hp " + fam.hp);
		// 가족 사망 → 150 XMCDEATH2 → 1035(150) → familyEnt-move150 → camWifeDeatho + A07MP04 → 실패 화면
		fam.hp = 1;
		for (int k = 0; k < 20 && fam.hp > 0; k++) {
			clone.pos.set(fam.pos.x - (float) Math.sin(fam.yaw) * 30, fam.pos.y, fam.pos.z - (float) Math.cos(fam.yaw) * 30);
			clone.yaw = fam.yaw + (float) Math.PI;
			clone.combat().forceMove(1 + k % 3);
			until(r, 1.5f, () -> fam.hp <= 0);
		}
		assertTrue(until(r, 2, () -> move(fam) == EvilKratosFight.FAMILY_DEATH), "XMCDEATH2: " + move(fam));
		assertTrue(until(r, 5, () -> "A07MP04".equals(r.movie)), "실패 컷신: " + r.log);
		assertTrue(r.log.stream().anyMatch(l -> l.startsWith("cam camWifeDeatho")), r.log.toString());
		r.movieDone();
		r.run(0.5f);
		assertTrue(r.log.stream().anyMatch(l -> l.startsWith("internal 0x13")), "FailedScreen: " + r.log);
		assertTrue(r.fight.phases.contains("가족 사망"));
	}
}
