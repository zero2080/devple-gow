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
		// 스크립트 Internal 33..45 (HP·MP·아이템) 대상 플레이어 — GameScene 은 영웅 PlayerState 를 넣는다
		devple.gow.engine.game.PlayerState player = new devple.gow.engine.game.PlayerState(devple.gow.engine.game.GameRules.load(r.perm));
		player.addItem(3, 1); // 포세이돈의 분노 (ATHN01 에서 이미 얻음) → MP 레벨 1
		player.mp = 5;
		r.host.players = () -> player;
		assertFalse(player.owned(devple.gow.engine.game.Weapon.MEDUSA));
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
		// 같은 EventCombat1: set_bool Internal:39 true = AddItem(4) 메두사의 시선 (0x1AFFB0 → 0x1B0088), Internal:35 ← Internal:36 = MP 가득
		assertTrue(player.owned(devple.gow.engine.game.Weapon.MEDUSA), "메두사의 시선 획득");
		assertEquals(devple.gow.engine.game.Weapon.MEDUSA, player.selectedMagic, "새 마법 선택");
		assertEquals(player.maxMp(), player.mp, 1e-3, "MP 가득");
		assertTrue(r.log.contains("message 141"), "메시지 141 메두사의 시선을 획득: " + r.log);
	}

	/**
	 * 여왕은 일반 Enemy1 이라 공용 내비게이션 (벽) 을 탄다: 방 동쪽 기둥 벽 (ATHN02C 시트 x ≈ −887, GeneralGuide · NoPlayerCollision = 적만 막음) 에
	 * 막혀 MedusaRipZone (x ≤ −864) 안에 남는다. 예전에는 보스라 세계가 없어 x −785 까지 걸어 나가, 거기서 머리를 뜯으면 EventCombat1
	 * (모드 2 = 보낸 쪽이 볼륨 안) 이 걸리지 않아 메시지 141·보상·미노타우로스가 없었다 (자동 플레이 120 초).
	 */
	@Test
	void queenStaysInsideTheRipZone() throws Exception {
		BossRig r = spawned();
		EnemyFighter b = r.fight.boss();
		assertTrue(r.fight.usesNavWorld());
		devple.gow.engine.level.LevelCollision host = new devple.gow.engine.level.LevelCollision(new devple.gow.tools.level.LevelData(
				devple.gow.tools.wad.WadFile.load(BossRig.ROM.resolve("ATHN02C.WAD"))).sheets);
		devple.gow.engine.nav.NavWorld nw = new devple.gow.engine.nav.NavWorld(r.col, host);
		devple.gow.engine.script.ScriptEntity zone = r.world.entities().stream()
				.filter(e -> e.name().equals("MedusaRipZone-pCube3661-EventCombat1")).findFirst().orElseThrow();
		r.run(1);
		b.combat().stop();
		b.setNavWorld(devple.gow.engine.combat.EnemyNav.of(nw));
		b.pos.set(-950, 960, 2030);
		b.prevPos.set(b.pos);
		for (int i = 0; i < 240; i++) {
			b.steer(new org.joml.Vector3f(1, 0, 0), Float.NaN, 1 / 60f);
		}
		assertTrue(b.pos.x < -880 && b.pos.x > -900, "벽 앞에서 멈춤: " + b.pos);
		assertFalse(zone.volume.regions(b.pos.x, b.pos.y + 20, b.pos.z).isEmpty(), "머리 뜯기 구역 안: " + b.pos);
		// 대조: 세계 없음 (예전 보스) 이면 같은 스틱으로 벽을 지나 구역 밖으로
		b.setNavWorld(null);
		for (int i = 0; i < 240; i++) {
			b.steer(new org.joml.Vector3f(1, 0, 0), Float.NaN, 1 / 60f);
		}
		assertTrue(b.pos.x > -864, "세계 없으면 벽을 지나감: " + b.pos);
		assertTrue(zone.volume.regions(b.pos.x, b.pos.y + 20, b.pos.z).isEmpty());
	}

	/**
	 * 여왕 뒤 미노타우로스 방 (메시지 595 "모든 미노타우르스를 얼린 뒤 부숴야"): 석상 파괴는 전역 이벤트 1046 (0x416, 0x265E04 → 0x1A8C78, 보낸 쪽 =
	 * 그 적) 이고 MedusaRipZone-pCube3661-EventSmash1 (모드 2) 이 센다 — LevelData1 (부순 수, 6 이면 문) += 1, LevelData5 = 참,
	 * LevelData4 (없어진 수) += 1. 제거 센서 MinoDestruction1 (조건 LevelData5 == 거짓) 은 LevelData5 가 켜진 동안 건너뛰고 0.51 초 뒤
	 * HulkSmashy 가 끈다. 둘을 0.07 초 차이로 부숴도 한 번씩만 세야 LevelData3 (스폰 수) == LevelData4 가 맞아 문이 열린다.
	 * 예전 엔진은 1046 을 보내지 않아 (일반 사망만) 부숴도 세지 않았고, 석상 제거를 0.5 초 뒤에 해 앞 석상의 HulkSmashy 가 창을 닫으면 두 번 셌다.
	 */
	@Test
	void stoneKillCountsOncePerMinotaur() throws Exception {
		assertEquals(2, smashTwo(devple.gow.engine.game.GameScene.SHATTER_DESTROY), "석상 제거 = 1046 전달 직후");
		assertEquals(3, smashTwo(0.5f), "대조: 0.5 초 뒤 제거하면 뒤 석상을 두 번 셈 (LevelData3 ≠ 4 → 문이 안 열림)");
	}

	/**
	 * 미노타우로스 방의 마법 재생 (원작 MP 경제): FirstMsgDone (1040, 메시지 141) → 10 초 뒤 MedusaRipZone-RewardPlayer 가 Internal 0x12 (10) =
	 * 0x3044A4 마법 재생 10 MP/초 (GameMgr 0x1B0B34, 영웅 갱신 0x1D6F20) — 단계 0 광선 Drain 12.5/초를 거의 메운다. 문 조건 (부순 수 ≥ 6, 스폰 ==
	 * 없어진 수) 이 서고 10 초 뒤 TurnFaceOffFinal 이 0 으로 끈다. 예전 엔진은 0x12 를 버려 봇의 MP 가 바닥나 문을 못 열었다 (미노타우로스 오브는 붉은색뿐).
	 */
	@Test
	void minotaurRoomRegeneratesMagicUntilTheDoorOpens() throws Exception {
		BossRig r = spawned();
		devple.gow.engine.game.PlayerState p = new devple.gow.engine.game.PlayerState(devple.gow.engine.game.GameRules.load(r.perm));
		p.addItem(4, 1); // 메두사의 시선 (EventCombat1 Internal:39)
		r.host.players = () -> p;
		r.host.levelData().put(0, true);
		r.world.postEvent(1040, 141, 0, 0, null); // 메시지 141 이 닫힘 → FirstMsgDone
		r.run(9.5f);
		assertEquals(0f, p.mpRegen, "RewardPlayer 지연 10 초 전");
		r.run(1f);
		assertEquals(10f, p.mpRegen, 1e-4, "RewardPlayer → Internal 0x12 (10): " + r.log);
		p.mp = 0;
		for (int i = 0; i < 60; i++) {
			p.regenMagic(1 / 60f);
		}
		assertEquals(10f, p.mp, 1e-3, "1 초에 10 MP");
		// 6 번째 석상 파괴로 문 조건이 선다 (부순 수 5 → 6, 스폰 3 == 없어진 수 2 + 1)
		float[] in = r.inside("MedusaRipZone-pCube3661-EventCombat1");
		assertNotNull(in);
		devple.gow.engine.script.ScriptActor a = r.world.addActor("MedusaRmEnemies#42", 25, in);
		r.host.levelData().put(1, 5);
		r.host.levelData().put(3, 3);
		r.host.levelData().put(4, 2);
		r.world.postEvent(1046, 0, 0, 0, a);
		r.run(0.1f);
		assertEquals(6, r.levelData(1));
		assertEquals(3, r.levelData(4));
		r.run(10.5f);
		assertEquals(0f, p.mpRegen, "TurnFaceOffFinal (문 조건 + 10 초) → Internal 0x12 (0)");
	}

	/** 미노타우로스 둘을 0.07 초 차이로 석화·파괴, 각자 destroyAfter 초 뒤 스크립트 제거 → LevelData4 (부순 수는 LevelData1 = 2 확인). */
	private static int smashTwo(float destroyAfter) throws Exception {
		BossRig r = spawned();
		r.host.levelData().put(0, true); // 메두사 처치 (EventCombat1) — EventSmash1 조건
		float[] p = r.inside("MedusaRipZone-pCube3661-EventCombat1");
		assertNotNull(p);
		devple.gow.engine.combat.EnemyFactory.Kind k = devple.gow.engine.combat.EnemyFactory.load(BossRig.ROM.resolve("R_MIN.WAD"), r.perm, -1);
		EnemyFighter[] f = new EnemyFighter[2];
		devple.gow.engine.script.ScriptActor[] a = new devple.gow.engine.script.ScriptActor[2];
		for (int i = 0; i < 2; i++) {
			f[i] = k.spawn();
			f[i].pos.set(p[0], p[1], p[2]);
			a[i] = r.world.addActor("MedusaRmEnemies#" + (42 + i), 25, p);
			r.bridge.attach(f[i], a[i]);
			assertTrue(f[i].addFreeze(30000), "석화");
		}
		float[] shatterAt = {0, 0.07f};
		boolean[] shattered = new boolean[2];
		float t = 0;
		for (int n = 0; n < 120; n++, t += BossRig.DT) {
			for (int i = 0; i < 2; i++) {
				if (!shattered[i] && t >= shatterAt[i]) {
					shattered[i] = true;
					f[i].receive(null, r.hero, 1, new org.joml.Vector3f()); // 돌 적중 → 부서짐 (GameScene.stoneKill → bridge.stoneKill)
					assertTrue(f[i].shattered());
					r.bridge.stoneKill(f[i]);
				}
				if (shattered[i] && !a[i].destroyed() && t - shatterAt[i] > destroyAfter) {
					r.world.destroy(a[i]); // GameScene.updateEnemies 의 제거 (DestructionSensor)
				}
			}
			r.world.tick(BossRig.DT, r.ctl.position.x, r.ctl.position.y, r.ctl.position.z);
		}
		assertEquals(2, r.levelData(1), "EventSmash1 (1046) 두 번: " + r.bridge.log);
		assertEquals(false, r.levelData(5), "HulkSmashy 가 LevelData5 를 끔");
		return (Integer) r.levelData(4);
	}

	/**
	 * 넘어져 착지 (105 hitLand) 의 SCR_ExplodeDeath 는 조건 6 (사망) — 살아 있으면 발동하지 않는다. 전에는 봇이 몇 번 때려 넘어뜨리자
	 * HP 9808 에서 바로 격파됐다 (자동 플레이 43 문서). 머리 뜯기 (260) 의 것은 조건 0.
	 */
	@Test
	void landingDoesNotExplodeWhileAlive() throws Exception {
		BossRig r = spawned();
		EnemyFighter b = r.fight.boss();
		b.hp = 9808;
		b.combat().forceMove(105);
		assertTrue(until(r, 4, () -> !b.combat().active() || bossMove(r) != 105));
		assertEquals(9808f, b.hp, 1f, "hitLand 뒤에도 살아 있음");
		assertFalse(r.fight.phases.contains("격파"), r.fight.phases.toString());
	}
}
