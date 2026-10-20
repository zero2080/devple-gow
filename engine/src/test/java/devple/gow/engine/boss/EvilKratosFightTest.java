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
		Vector3f at = new Vector3f(r.ctl.position);
		float yaw0 = r.ctl.yaw;
		r.run(0.3f); // 자세 섞임(페이드) 뒤
		// 원작: 865–867 은 이벤트 19 만 (끌기 없음) → 잡은 자리 + 루트 모션. MAIEVIHug* 는 두 쪽 zeroJoint 가 고정 → 영웅은 잡은 자리 그대로
		// (예전 엔진 대체 SYNCH 는 영웅을 가족 16 뒤·같은 방향으로 옮겼다, 37 문서 §11.4)
		assertEquals(0, new Vector3f(r.ctl.position.x, 0, r.ctl.position.z).distance(at.x, 0, at.z), 1f, "영웅 위치 " + r.ctl.position + " ← " + at);
		assertEquals(0, Math.abs(Math.IEEEremainder(r.ctl.yaw - yaw0, Math.PI * 2)), 1e-3, "방향 그대로");
		r.pad.down.add(devple.gow.engine.input.PadButton.CIRCLE);
		for (int i = 0; i < 90; i++) {
			r.pad.down.add(devple.gow.engine.input.PadButton.CIRCLE); // 866 루프: ○ 유지
			r.tick();
		}
		assertTrue(fam.hp >= 40 + 24 - 1e-3f, "가족 SELF_HP −2 ×12 회복: " + fam.hp);
		assertTrue(r.heroHp <= heroHp - 24 + 1e-3f, "영웅 SELF_HP +2 ×12: " + r.heroHp);
		assertTrue(r.log.contains("message 454"), "1035(865) → hugMsg 454: " + r.log);
		assertEquals(1, fight.hugs());
		assertTrue(r.hero.godMeter >= 10, "가족 865/866 GOD_METER_ADD → 크레토스 게이지: " + r.hero.godMeter);
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

	/** 분신 666 을 영웅 정면 30 에 두고 EVIGRAB(0) — 판정 [0.47–0.53] 이 영웅에 닿을 때까지. 성공하면 분신. */
	static EnemyFighter cloneGrabs(BossRig r) {
		EnemyFighter fam = r.fight.boss();
		assertTrue(until(r, 8, () -> r.enemies.stream().anyMatch(e -> e.ai1() == 666)), r.log.toString());
		EnemyFighter clone = r.enemies.stream().filter(e -> e.ai1() == 666).findFirst().orElseThrow();
		// 가족에서 떨어진 곳 (분신 공격 휘말림 없이)
		r.ctl.position.set(fam.pos.x - (float) Math.sin(fam.yaw) * 150, fam.pos.y, fam.pos.z - (float) Math.cos(fam.yaw) * 150);
		r.ctl.prevPosition.set(r.ctl.position);
		for (int k = 0; k < 10 && !r.heroMoves.contains(EvilKratosFight.CLONE_GRAB_START); k++) {
			clone.pos.set(r.ctl.position.x + 30, r.ctl.position.y, r.ctl.position.z);
			clone.prevPos.set(clone.pos);
			clone.yaw = (float) Math.atan2(30, 0); // 영웅 쪽 (-x) 을 봄: 정면 = (-sin, -cos)
			clone.combat().forceMove(0);
			until(r, 1.2f, () -> r.heroMoves.contains(EvilKratosFight.CLONE_GRAB_START));
		}
		assertTrue(r.heroMoves.contains(EvilKratosFight.CLONE_GRAB_START), "EVIGRAB 적중 → 영웅 A860: " + r.heroMoves);
		return clone;
	}

	@Test
	void cloneGrabWiggleEscapes() throws Exception {
		BossRig r = spawned();
		r.autoMinigame = false;
		EvilKratosFight fight = (EvilKratosFight) r.fight;
		EnemyFighter clone = cloneGrabs(r);
		assertTrue(until(r, 3, () -> fight.cloneSync().active() && fight.cloneSync().pairedMove() == EvilKratosFight.CLONE_GRAB_LOOP),
				"860 → 861 쌍: hero " + r.heroMoves + " clone " + move(clone));
		assertSame(clone, fight.grabber());
		// 원작: 860–863 은 이벤트 19 만 → 분신을 영웅 쪽으로 옮기지 않는다 (예전 BOSS_TO_HERO 대체 규칙 제거). 루프 861 은 두 쪽 zeroJoint 고정
		Vector3f c0 = new Vector3f(clone.pos), h0 = new Vector3f(r.ctl.position);
		r.run(0.5f);
		assertEquals(0, clone.pos.distance(c0), 1f, "분신 제자리 (861 루프)");
		assertEquals(0, r.ctl.position.distance(h0), 1f, "영웅 제자리 (861 루프)");
		System.out.printf("분신 잡기 861: 영웅–분신 거리 %.1f%n", new Vector3f(clone.pos).sub(r.ctl.position).setComponent(1, 0).length());
		assertTrue(fight.heroLocked());
		// 스틱 흔들기 → 게이지 > 2 → "AUTO fall>2" → 862 탈출, 분신도 862 (EVIGRABHEROWIN, 분신 사망 연출)
		for (int i = 0; i < 120 && !r.heroMoves.contains(EvilKratosFight.CLONE_GRAB_WIN); i++) {
			r.pad.lx = i % 2 == 0 ? 1 : -1;
			r.tick();
		}
		r.pad.lx = 0;
		assertTrue(r.heroMoves.contains(EvilKratosFight.CLONE_GRAB_WIN), "흔들기 → 862: " + r.heroMoves + " wiggle " + r.hero.wiggle());
		assertEquals(1, fight.grabEscapes);
		assertTrue(until(r, 1, () -> move(clone) == EvilKratosFight.CLONE_GRAB_WIN), "분신 862: " + move(clone));
	}

	@Test
	void cloneGrabWithoutWiggleHurtsHero() throws Exception {
		BossRig r = spawned();
		r.autoMinigame = false;
		EvilKratosFight fight = (EvilKratosFight) r.fight;
		EnemyFighter clone = cloneGrabs(r);
		float hp = r.heroHp;
		assertTrue(until(r, 8, () -> r.heroMoves.contains(EvilKratosFight.CLONE_GRAB_LOSE)), "창 끝 → 863: " + r.heroMoves);
		assertEquals(1, fight.grabLosses);
		assertTrue(until(r, 3, () -> r.heroHp <= hp - 10 + 1e-3f), "863 SELF_HP 10 → 영웅 피해: " + r.heroHp);
		assertTrue(until(r, 4, () -> fight.grabber() == null), "잡기 끝");
	}
}
