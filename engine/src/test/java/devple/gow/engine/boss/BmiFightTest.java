package devple.gow.engine.boss;

import devple.gow.engine.combat.EnemyFighter;
import org.junit.jupiter.api.Test;

import static devple.gow.engine.boss.HydraFightTest.contact;
import static devple.gow.engine.boss.HydraFightTest.until;
import static org.junit.jupiter.api.Assertions.*;

/**
 * PAND03G 판도라 수호자 BMI (37 문서 §10): 전용 클래스 Think/TakeDamage — 단계표 기술 선택, 배출구 5 → 거북 기절 → ○ 올라타기(TimedPress)
 * → 통나무 기절 → 레버 → 통나무 (단계 +1), 피하기(999), 끝까지 (245 → p03MP07 → Pand03H).
 */
class BmiFightTest {

	static BossRig spawned() throws Exception {
		BossRig r = new BossRig(BmiFight.LEVEL);
		r.world.start();
		r.run(0.3f);
		// 문 앞 볼륨 → 컷신 P03MP06 → 501 → Goto BossSpawnPoint, 0.1초 뒤 231 → minoBoss-SenseMpegDoneMsg → SpawnEnemy 200
		assertTrue(r.enter("playCutScene-CloseToDoorVol-CloseToDoorEntry"), "CloseToDoor 볼륨");
		assertTrue(until(r, 1, () -> "P03MP06".equals(r.movie)), r.log.toString());
		r.movieDone();
		assertTrue(until(r, 2, () -> r.fight.boss() != null), "minoBoss SpawnEnemy 200: " + r.log);
		assertTrue(r.log.stream().anyMatch(s -> s.startsWith("level GOTO")), "Goto BossSpawnPoint: " + r.log);
		return r;
	}

	static int move(EnemyFighter f) {
		return f.combat().active() ? f.combat().move().id() : -1;
	}

	/** 영웅을 BMI 정면 dist 에 (BMI 는 앵커 선 위에서 돌지 않는다). */
	static void front(BossRig r, float dist) {
		EnemyFighter b = r.fight.boss();
		r.ctl.position.set(b.pos.x - (float) Math.sin(b.yaw) * dist, b.pos.y, b.pos.z - (float) Math.cos(b.yaw) * dist);
		r.ctl.prevPosition.set(r.ctl.position);
	}

	/** 대기(기술 없음 또는 대기·이동 기술)까지 기다린 뒤 영웅 공격 피해 (일반 피격 경로 → TakeDamage 재정의). */
	static void hit(BossRig r, float damage) {
		EnemyFighter b = r.fight.boss();
		assertTrue(until(r, 12, () -> !b.invulnerable()), "무적 끝: " + move(b));
		contact(r, 71, damage); // 영웅 공격 (판정 하나 있는 기술)
		r.tick();
	}

	@Test
	void loadsAndChoosesFromStageTable() throws Exception {
		BossRig r = spawned();
		BmiFight fight = (BmiFight) r.fight;
		EnemyFighter b = fight.boss();
		assertEquals(200, b.ai1());
		assertEquals(1500f, b.hp);
		assertEquals(java.util.List.of("TWK_CombatFile_200", "TWK_CombatFile_201", "TWK_CombatFile_202", "TWK_CombatFile_203",
				"TWK_CombatFile_204", "TWK_CombatFile_205"), fight.kindFor(200).combatFiles());
		// /GlobGame/BMI (R_PERMK): 짧은 필드 = 기술 번호, 거리 ×16
		assertEquals(250, fight.staggerFirst);
		assertEquals(232, fight.armorStunned);
		assertEquals(12 * 16f, fight.closeMid);
		assertArrayEquals(new int[]{78, 150, 79, 1, 151, 1, 1, 152, 1, 105, 100, 103, 103}, fight.table[0]);
		assertArrayEquals(new int[]{78, 180, 79, 1, 181, 1, 72, 182, 72, 105, 100, 103, 103}, fight.table[3]);
		assertEquals(0.25f, fight.idle[2], 1e-4);
		// 멀리 → Walk Forward 1 (걸어서 다가옴, zeroJoint 이동)
		front(r, 900);
		var p0 = new org.joml.Vector3f(b.pos);
		assertTrue(until(r, 8, () -> r.bossMoves.contains(1)), "Far Walk Dist 밖 → 1: " + r.bossMoves);
		r.run(1);
		assertTrue(b.pos.distance(p0) > 30, "걷기 이동 " + b.pos.distance(p0));
		// 정면 가까이 → S0 Close Center 150 (대기 → AUTO 무작위 50/53/42/74)
		r.bossMoves.clear();
		front(r, 100);
		assertTrue(until(r, 10, () -> r.bossMoves.contains(150)), "S0 Close Center 150: " + r.bossMoves);
		// 앵커 선 위 [0, Platform Dist] (HP > 99)
		var fwd = new org.joml.Vector3f((float) -Math.sin(b.yaw), 0, (float) -Math.cos(b.yaw));
		float bA = fwd.dot(new org.joml.Vector3f(b.pos).sub(fight.anchor()));
		assertTrue(bA >= -1 && bA <= fight.platDist + 1, "앵커 선 " + bA);
	}

	@Test
	void ventsStunRideAndLog() throws Exception {
		BossRig r = spawned();
		BmiFight fight = (BmiFight) r.fight;
		EnemyFighter b = fight.boss();
		front(r, 100);
		// 배출구 단계: 피해 100 마다 하나 — 첫 번째 250 STEAMVENT (VentCam), 2–4 = 200, 5 번째 = 201 → 202 → 203 거북 기절
		int[] want = {250, 200, 200, 200, 201};
		for (int i = 0; i < 5; i++) {
			front(r, 100);
			hit(r, 100);
			int w = want[i];
			assertEquals(w, move(b), "배출구 " + (i + 1) + ": " + r.bossMoves);
			assertEquals(i + 1, fight.vents());
		}
		assertEquals(1500f, b.hp, "단계 0 에서는 HP 가 줄지 않는다");
		assertTrue(r.cams.contains("VentCam"), "1035(250) → VentCam: " + r.cams);
		assertTrue(until(r, 5, () -> move(b) == 203), "202 → 203 TURTLESTUNIDLE: " + r.bossMoves);
		// 잠금 중 피해 무시
		contact(r, 71, 300);
		assertEquals(5, fight.vents());
		// ○ GRAB (자세 200) → 203 EV_HIT_REACT tag 200 → 450, 영웅 HIT_CONFIRM tgt=200 tag=203 → 450 → 451..453 (TimedPress 자동) → 455
		front(r, 60);
		r.fight.contact(200, 0);
		assertTrue(until(r, 1, () -> fight.sync().active() && fight.sync().pairedMove() == BmiFight.RIDE_MOUNT), "450: " + r.bossMoves + " hero " + r.heroMoves);
		assertTrue(r.log.contains("message 589"), "1035(450) → SwirlyMessage 589: " + r.log);
		assertTrue(until(r, 25, () -> r.bossMoves.contains(BmiFight.RIDE_WIN)), "올라타기 → 455: " + r.bossMoves + " hero " + r.heroMoves);
		assertTrue(r.heroMoves.containsAll(java.util.List.of(451, 452, 453, 455)), "영웅 451–453 → 455: " + r.heroMoves);
		assertTrue(fight.sync().timedPassed() >= 7, "TimedPress 206/207 ×7: " + fight.sync().timedPassed() + "/" + fight.sync().timedFailed());
		assertTrue(until(r, 6, () -> move(b) == BmiFight.LOG_STUN), "455 → 208 LOGSTUNIDLE: " + r.bossMoves);
		assertTrue(r.cams.containsAll(java.util.List.of("mountcam", "cam451", "cam452", "cam453", "WinCam")), r.cams.toString());
		assertEquals(Boolean.TRUE, r.levelData(4), "1035(208) → LevelData4 (Boss is stunned)");
		// 레버 과제 351 = 99 % → 통나무 (1020 인자 1000) → 적중: 단계 +1, A210 LOGHIT (HP 1500 > 1000)
		fight.pullLever();
		assertTrue(until(r, 6, () -> fight.logHits == 1), "통나무 적중: hits " + fight.logHits + " misses " + fight.logMisses + " " + r.log);
		assertEquals(1, fight.stage());
		assertEquals(0, fight.vents());
		assertTrue(until(r, 1, () -> move(b) == 210), "A210 BMILOGHIT: " + r.bossMoves);
	}

	@Test
	void dodgesLogWhenNotStunned() throws Exception {
		BossRig r = spawned();
		BmiFight fight = (BmiFight) r.fight;
		EnemyFighter b = fight.boss();
		front(r, 300);
		r.run(2);
		// LevelData4 거짓 → logLoadGRP-LogDamageBoss 0.5초 뒤 1039(999) → TakeDamage(999) → Dodge 220 → 통나무 빗나감
		fight.pullLever();
		assertTrue(until(r, 2, () -> r.bossMoves.contains(220)), "999 → Dodge 220: " + r.bossMoves);
		assertTrue(until(r, 6, () -> fight.logMisses == 1), "빗나감: " + fight.logHits + "/" + fight.logMisses);
		assertEquals(0, fight.stage());
	}

	@Test
	void demoReachesScriptedEnd() throws Exception {
		BossRig r = spawned();
		BmiFight fight = (BmiFight) r.fight;
		EnemyFighter b = fight.boss();
		fight.autoPlay = true;
		assertTrue(until(r, 400, fight::defeated), "결말: stage " + fight.stage() + " hp " + b.hp + " move " + move(b) + " " + fight.phases);
		// 단계 0 → 1 → 2 (통나무 2 번), 갑옷 파괴 232 → 단계 3, HP 99 → 240 → 242 → 243 → 통나무 → 245 (SCR_ExplodeDeath)
		assertTrue(r.bossMoves.containsAll(java.util.List.of(201, 203, 450, 455, 208, 210, 230, 232, 240, 242, 243, BmiFight.DEATH)), r.bossMoves.toString());
		assertEquals(3, fight.logHits, "통나무 3 번");
		assertTrue(fight.stage() >= 3);
		assertTrue(r.cams.containsAll(java.util.List.of("ArmorCam1", "ArmorCam2")), "1035(232) 갑옷 파괴 카메라: " + r.cams);
		// 1035(245) → FinalMpegStuff: 음악 정지, p03MP07 → 501 → LoadWad Pand03H, camMinoDeado
		assertTrue(until(r, 3, () -> "p03MP07".equals(r.movie)), r.log.toString());
		r.movieDone();
		r.run(1);
		assertTrue(r.log.stream().anyMatch(s -> s.startsWith("cam camMinoDeado")), r.log.toString());
		assertTrue(r.log.stream().anyMatch(s -> s.contains("LOAD_WAD PAND03H")), "LoadWad Pand03H: " + r.log);
	}
}
