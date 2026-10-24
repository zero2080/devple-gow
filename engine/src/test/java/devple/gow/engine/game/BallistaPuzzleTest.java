package devple.gow.engine.game;

import devple.gow.engine.nav.Interactables;
import devple.gow.engine.nav.Platforms;
import devple.gow.engine.nav.TaskProgress;
import devple.gow.engine.script.ScriptActor;
import devple.gow.engine.script.ScriptEntity;
import devple.gow.engine.script.ScriptHost;
import devple.gow.engine.script.ScriptListener;
import devple.gow.engine.script.ScriptWorld;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ATHN02C 발리스타 퍼즐 (43 문서 §14, QA vs PS2 영상 3274–3378 초): 회전판 레버 Gunlever (Push 012 Type 5, 과제 140) → LazySueGroup 회전판
 * 90° (3 초) 뒤 다음 멈춤 자리에 섬 → 발리스타 크랭크 BigGun1Crank (Push 202 "AthnGun" Type 2 = 대포, 과제 320) → 100 % 에 1020 → ArrowEmitter/200
 * → Arrow/099 (피해 10000) → breakableDoor01 (Brkb 950 Hit Points 10000) 파괴 → LevelData14.
 */
class BallistaPuzzleTest {

	static final Path ROM = Path.of(System.getProperty("gow.rom", "../gow-rom"));

	static LevelData level(String wad) throws Exception {
		Assumptions.assumeTrue(Files.exists(ROM.resolve(wad)), "gow-rom 없음");
		return new LevelData(WadFile.load(ROM.resolve(wad)));
	}

	/** 스크립트 Animator → 발판 애니 (GameScene 과 같은 경로: SceneNav.applyAnimator). */
	static final class Rig implements ScriptListener {
		final List<String> log = new ArrayList<>();
		ScriptWorld w;
		Platforms ps;

		@Override
		public void animate(ScriptEntity source, List<String> objects, String anim) {
			log.add("anim " + source.name());
			SceneNav.applyAnimator(w, ps, source, objects, anim);
		}

		@Override
		public void objectEvent(ObjectEvent e) {
			log.add("event " + e.objectName() + " " + e.event() + " " + e.arg1());
		}
	}

	private static void run(ScriptWorld w, Platforms ps, float sec) {
		for (float s = 0; s < sec; s += 1 / 60f) {
			ps.update(1 / 60f);
			for (Platforms.Body b : ps.bodies()) {
				if (b.playing() || b.followMoved()) {
					w.poseOwner(b.owner, b.joints());
				}
			}
			w.tick(1 / 60f, 1e6f, 1e6f, 1e6f);
		}
	}

	/** 레버 한 번 = 1023 (0 → 100, 과제 140) — 원작 레버 0x2127B0 → 0x2118E8. */
	private static void pullLever(ScriptWorld w, Platforms ps) {
		w.postEvent(1023, 0, 140, 100, w.player());
		run(w, ps, 0.1f);
		w.postEvent(1023, 100, 140, 0, w.player());
		run(w, ps, 0.1f);
	}

	@Test
	void turntableStopsAtTheNextDetent() throws Exception {
		LevelData lv = level("ATHN02C.WAD");
		Rig rig = new Rig();
		ScriptHost host = new ScriptHost();
		ScriptWorld w = new ScriptWorld(lv, host, rig);
		rig.w = w;
		rig.ps = Platforms.of(lv, SceneNav.animatorOwners(w));
		w.start();
		run(w, rig.ps, 0.5f);
		Platforms.Body turntable = rig.ps.body("golazysuegroup");
		assertNotNull(turntable);

		// 시작 자리 (0 초, 북쪽) → 레버 → 정방향 3 초 → AniPause (모드 0x12) 가 3 초에 세움. 조건이 다음 멈춤 자리를 계산하는 것은 jump_if = 거짓이면 점프 (0x16F5A4)
		pullLever(w, rig.ps);
		run(w, rig.ps, 4);
		assertEquals(3f, turntable.time(), 1e-3f, "가운데 (동쪽, 문 쪽)");
		assertFalse(turntable.playing(), "0x12 = 시간 3 에 세움 (예전: 반복 재생)");
		assertEquals(Boolean.TRUE, host.levelData().get(3), "Marker320 → GunMidZone");
		assertEquals(3f, ((Number) host.levelData().get(6)).floatValue(), 1e-6f);

		// 가운데 → 끝 (남쪽)
		pullLever(w, rig.ps);
		run(w, rig.ps, 4);
		assertEquals(6f, turntable.time(), 1e-3f, "끝 (남쪽, 비밀 벽 쪽)");
		assertEquals(Boolean.TRUE, host.levelData().get(2), "Marker320 → GunEndZone");
		assertEquals(Boolean.FALSE, host.levelData().get(3));

		// 끝 → 가운데 (역방향 AnRev 모드 9)
		pullLever(w, rig.ps);
		run(w, rig.ps, 4);
		assertEquals(3f, turntable.time(), 1e-3f, "역방향으로 가운데");
		assertTrue(rig.log.contains("anim LazySueGroup-AnRev"), rig.log.toString());
	}

	@Test
	void jumpIfBranchesWhenFalse() throws Exception {
		// MedusaRmEnemies 스폰 지연 (ATHN02D): if (LevelData3 == 0) 7 else 1 — 처음 셋은 7 초, 다시 나오기는 1 / 2 / 1.25 초
		LevelData lv = level("ATHN02D.WAD");
		ScriptHost host = new ScriptHost();
		ScriptWorld w = new ScriptWorld(lv, host, new ScriptListener() {
		});
		w.start();
		ScriptEntity sp = w.find("MedusaRmEnemies-MinotaurLevel1AI6");
		host.levelData().put(3, 0);
		assertEquals(7, ((Number) w.slot(sp, 12)).intValue());
		host.levelData().put(3, 2);
		assertEquals(1, ((Number) w.slot(sp, 12)).intValue());
	}

	@Test
	void destructionSensorFilterAppliesToEveryMode() throws Exception {
		LevelData lv = level("ATHN02C.WAD");
		ScriptHost host = new ScriptHost();
		ScriptWorld w = new ScriptWorld(lv, host, new ScriptListener() {
		});
		w.start();
		run(w, new Platforms(), 0.2f);
		ScriptActor door = w.markers("gobreakabledoor01").getFirst();
		assertEquals(27, door.group);
		w.destroy(door);
		run(w, new Platforms(), 0.2f);
		assertEquals(Boolean.TRUE, host.levelData().get(14), "breakableDoor01-DestructionSensor1 (모드 3, Filter 27)");
		assertEquals(0, ((Number) host.levelData().get(21)).intValue(),
				"EnemyBalistaZone-DestructionSensor1 (모드 4, Filter 98) 은 그룹 27 Marker 에 발동하지 않음 (0x1AE1D0)");
	}

	@Test
	void ballistaCrankIsACannon() throws Exception {
		LevelData lv = level("ATHN02C.WAD");
		Tweaks tw = new Tweaks();
		tw.load(lv.wad);
		Interactables items = Interactables.of(lv, tw);
		Interactables.Item gun = items.byOwner("gobiggun1crank"), lever = items.byOwner("gogunlever");
		assertEquals(Interactables.Kind.LEVER, gun.kind, "Push Type 2 = 크랭크류 (예전: 이름 AthnGun 이라 밀기 상자)");
		assertTrue(gun.cannon());
		assertEquals(320, gun.id);
		assertFalse(lever.crank, "Type 5 = 레버");
		assertEquals(Integer.valueOf(200), lv.arrowEmitters.get("gobiggun1crank"), "Creator 200 = ArrowEmitter/200");

		// 감기: 끝에서 99 % 로 멈춤 → 발사 (+0x29A 비트 2) → 100 % → 1015 리셋 되감기 → 0 에서 다시
		TaskProgress t = new TaskProgress(320, 4f);
		TaskProgress.Event last = null;
		float spd = 0;
		for (int i = 0; i < 60 * 6; i++) {
			spd = Math.min(spd + gun.acc / 60f, gun.maxSpeed);
			TaskProgress.Event e = t.cannon(spd, 1 / 60f);
			last = e != null ? e : last;
		}
		assertTrue(t.wound());
		assertFalse(t.done);
		assertEquals(99, last.percent(), "끝에서 길이 − 0.01 보고 (0x212AD0)");
		assertEquals(100, t.fire().percent());
		assertTrue(t.done);
		while (t.time > 0) {
			t.rewind(Math.max(t.speed - gun.rewindAcc / 60f, -gun.maxBack), 1 / 60f);
		}
		assertFalse(t.done);
		assertEquals(0f, t.time, 1e-6f);
	}

	@Test
	void arrowBreaksTheDoorAtTheMidDetent() throws Exception {
		LevelData lv = level("ATHN02C.WAD");
		Tweaks tw = new Tweaks();
		tw.load(lv.wad);
		ScriptHost host = new ScriptHost();
		ScriptWorld w = new ScriptWorld(lv, host, new ScriptListener() {
		});
		w.start();
		Platforms ps = Platforms.of(lv, SceneNav.animatorOwners(w), Set.of("gobiggun1crank"));
		ps.poseAt("golazysuegroup", null, 3f); // 가운데: 발리스타가 동쪽
		ps.update(0);
		Breakables br = new Breakables(lv, tw);
		Breakables.Item door = br.item("gobreakabledoor01");
		assertEquals(10000f, door.hp, "Brkb 950 Hit Points");
		float[] aim = ps.jointWorld("gobiggun1crank", tw.s("/TweakTemplates/ArrowEmitter/200/", "Aim Joint", null));
		assertNotNull(aim, "arrowjoint");
		assertEquals(1f, aim[8], 1e-3f, "가운데 자리에서 +Z = 동쪽");
		ArrowShots shots = new ArrowShots();
		List<String> broken = new ArrayList<>();
		ArrowShots.Shot s = shots.fire(tw, 200, aim, ps.family("gobiggun1crank"), null);
		assertNotNull(s);
		var col = new devple.gow.engine.level.LevelCollision(lv.sheets);
		for (int i = 0; i < 120 && !shots.shots().isEmpty(); i++) {
			shots.update(1 / 60f, new ArrowShots.Targets(br, w, broken::add, List.of(), null, col, null, ps, null, null));
		}
		assertEquals("gobreakabledoor01", s.hit, "화살 960/초 → 문 (Damage 10000)");
		assertEquals(List.of("gobreakabledoor01"), broken);
		run(w, ps, 0.2f);
		assertEquals(Boolean.TRUE, host.levelData().get(14), "Marker27 파괴 → LevelData14");
	}
}
