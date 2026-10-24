package devple.gow.engine.game;

import devple.gow.engine.level.LevelCollision;
import devple.gow.engine.nav.Interactables;
import devple.gow.engine.nav.Platforms;
import devple.gow.engine.script.ScriptEntity;
import devple.gow.engine.script.ScriptHost;
import devple.gow.engine.script.ScriptListener;
import devple.gow.engine.script.ScriptWorld;
import devple.gow.engine.verify.TraversalVerifier;
import devple.gow.tools.content.Content;
import devple.gow.tools.content.Region;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 체크포인트 재시작 = 스냅숏 되읽기 (원작 0x21E3D0 → 0x22D440 → 0x22D660 → 0x22DA88, 43 문서 §26) 와 레벨 기억의 엔티티 발동 기록 (덩어리 +0x30,
 * 메시지 7 · 8).
 * <ul>
 *   <li>영상 8202–8224: ATHN05C CheckpointGroup4 를 지나 항아리 방의 깃털 상자 (ATHN05D Chest4) 를 연 뒤 물 방에서 죽고 "마지막 체크포인트에서 다시
 *       시작" 하면 상자는 닫혀 있고 깃털 수도 되돌아가 같은 카드 ("1 개를 더") 가 다시 나온다 — 예전 엔진은 영웅만 옮겨 상자가 열린 채였다.</li>
 *   <li>ATHN04A 광장 재방문: 북쪽 계단 DMC TriggerVol0 (재발동 −1) 은 발동 기록이 이어져 키클롭스 결투를 다시 열지 않는다 — 예전 엔진은 LevelData0 이
 *       3 을 넘어 결계가 안 열렸다 (43 §21.3).</li>
 * </ul>
 */
class CheckpointRestartTest {

	private static final Path ROM = Path.of(System.getProperty("gow.rom", "../gow-rom"));

	private static LevelData level(String name) throws Exception {
		Path p = Content.file(ROM, name + ".WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return new LevelData(WadFile.load(p));
	}

	private static GameSession session() throws Exception {
		Assumptions.assumeTrue(Files.exists(Content.file(ROM, Region.of(ROM).perm())), "gow-rom 없음");
		GameSession s = new GameSession(ROM);
		s.player = new PlayerState(GameRules.load(s.permk));
		return s;
	}

	private static Tweaks tweaks(LevelData lv) throws Exception {
		Tweaks t = new Tweaks();
		t.load(WadFile.load(Content.file(ROM, Region.of(ROM).perm())));
		t.load(lv.wad);
		return t;
	}

	/** 스폰·애니·Vis 기록. */
	private static class Rec implements ScriptListener {
		final List<SpawnRequest> spawns = new ArrayList<>();
		final List<String> anims = new ArrayList<>();

		@Override
		public void spawnEnemy(SpawnRequest r) {
			spawns.add(r);
		}

		@Override
		public void animate(ScriptEntity source, List<String> objects, String anim) {
			anims.add(anim);
		}
	}

	/** Creator 상자를 SceneNav 와 같은 길로 목록에 넣는 세계 (NeighbourChestTest). */
	private static ScriptWorld chestWorld(LevelData lv, ScriptHost host, Interactables into, Tweaks tw, ScriptWorld.Memory mem) {
		ScriptWorld[] w = new ScriptWorld[1];
		w[0] = new ScriptWorld(lv, host, new ScriptListener() {
			@Override
			public void create(ScriptEntity source) {
				SceneNav.addChest(into, w[0], source, tw);
			}
		});
		if (mem != null) {
			w[0].restore(mem);
		}
		w[0].start();
		return w[0];
	}

	@Test
	void featherChestIsClosedAgainAfterTheCheckpointRestart() throws Exception {
		GameSession s = session();
		LevelData c = level("ATHN05C"), d = level("ATHN05D");
		Tweaks tw = tweaks(d);
		s.player.phoenixFeathers = 4; // 영상: 이 상자가 깃털 5 개째 ("1 개를 더")
		s.player.redOrbs = 1200;
		s.playTime = 600;
		// 두 슬롯: 지금 05C (방 바닥) · 이웃 05D (상자 Creator)
		assertNull(s.enterLevel("ATHN05C"));
		ScriptWorld w5c = new ScriptWorld(c, s.scriptHost, null);
		w5c.start();
		assertNull(s.enterLevel("ATHN05D"));
		Interactables items = new Interactables();
		ScriptWorld w5d = chestWorld(d, ScriptHost.neighbour(s.scriptHost), items, tw, null);
		assertFalse(items.byOwner("gochest4").done);

		// CheckpointGroup4 (−1440, 896, 2464) → 프레임 끝 스냅숏: 사망 수 0, 두 슬롯을 표에 (0x1B3E48 → 0x1B4080(…, 0))
		s.player.deaths = 0;
		s.rememberLevel("ATHN05C", w5c, List.of(), java.util.Set.of());
		s.rememberLevel("ATHN05D", w5d, List.of(), java.util.Set.of());
		s.checkpoint = SaveGame.capture(s, "ATHN05C", "ATHN05D", true, new float[]{-1440, 864, 2460}, (float) Math.PI);

		// 체크포인트 뒤: 상자를 열어 깃털 5 개째, 오브도 쓰고, 05D 진행 (LevelData), 시간이 흐름
		w5d.markOpened("gochest4"); // SceneNav.interactDone
		s.player.addItem(11, 1);
		assertEquals(5, s.player.phoenixFeathers);
		s.player.redOrbs = 50;
		w5d.presetState("L5=1");
		s.playTime = 700;
		// 물 방에서 죽음 (0x1D6FDC: +0x48 사망 수 + 1)
		s.player.hp = 0;
		s.player.deaths++;

		// "마지막 체크포인트에서 다시 시작"
		s.checkpoint.applyCheckpoint(s, false);
		assertEquals(4, s.player.phoenixFeathers, "깃털 수가 스냅숏으로");
		assertEquals(1200, s.player.redOrbs);
		assertEquals(1, s.player.deaths, "사망 수는 지금 것 (Load_PlayerBlock 이 +0x48 을 남긴다)");
		assertEquals(600, s.playTime, 1e-3, "플레이 시간도 스냅숏 (+0x4C)");
		// 새 씬: 스냅숏 레벨 + 다른 슬롯 (0x1B4528) — 레벨 기억 표가 두 슬롯 덩어리를 갖는다
		ScriptWorld.Memory m5c = s.enterLevel("ATHN05C"), m5d = s.enterLevel("ATHN05D");
		assertNotNull(m5c);
		assertNotNull(m5d);
		assertFalse(m5d.opened().contains("gochest4"), "체크포인트 뒤에 연 상자는 기억에 없다");
		Interactables again = new Interactables();
		ScriptWorld r5d = chestWorld(d, ScriptHost.neighbour(s.scriptHost), again, tw, m5d);
		assertTrue(r5d.restored());
		assertNotNull(again.byOwner("gochest4"), "만들어 둔 Creator 를 다시 (0x1A6C70)");
		assertFalse(again.byOwner("gochest4").done, "상자는 닫힌 채 — 다시 열 수 있다 (영상 8224)");
		assertEquals(m5d.levelData().get(5), r5d.host().levelData().get(5));
		assertNotEquals(1, r5d.host().levelData().get(5), "05D LevelData 도 스냅숏 때로 (체크포인트 뒤 L5 = 1 은 없다)");
		// 다시 열면 같은 카드 (깃털 5 개째)
		s.player.addItem(11, 1);
		assertEquals(5, s.player.phoenixFeathers);
	}

	@Test
	void restartRestoresTheSnapshotButKeepsDeathsAndHints() throws Exception {
		GameSession s = session();
		LevelData c = level("ATHN05C");
		s.difficulty = 1;
		s.player.hp = 10; // 스냅숏 체력 10
		s.player.hintMask = 0x4;
		s.player.hintCount1 = 1;
		s.scriptHost.timers.create(30);
		s.scriptHost.globalData().put(ScriptHost.globalKey("ZoneArea"), 5);
		s.enterLevel("ATHN05C");
		ScriptWorld w = new ScriptWorld(c, s.scriptHost, null);
		w.start();
		s.rememberLevel("ATHN05C", w, List.of(), java.util.Set.of());
		SaveGame cp = SaveGame.capture(s, "ATHN05C", null, true, new float[]{0, 0, 0}, 0);
		// 뒤: 안내 비트 · 타이머 · GlobalData 가 바뀌고 다섯 번 죽음
		s.player.hintMask |= 0x10;
		s.player.hintCount1 = 3;
		s.scriptHost.timers.destroy(1);
		s.scriptHost.globalData().put(ScriptHost.globalKey("DoorKey"), true);
		s.player.deaths = 5;
		cp.applyCheckpoint(s, false);
		assertEquals(5, s.player.deaths);
		assertEquals(0x14, s.player.hintMask, "첫 안내 비트 (+0x50) 는 지금 것");
		assertEquals(1, s.player.hintCount1, "안내 누적 (+0x54) 은 스냅숏");
		// 0x1DC294: max(10, 25) = 25 → 사망 ≥ 4: + (5 − 3) × 10 = 45, 최대 HP 로 자르고, 저장값 < 75 라 75 로 자름 → 45
		assertEquals(45, s.player.hp, 1e-3);
		assertTrue(s.scriptHost.timers.active(1), "GameMgr 타이머 표 (0x1B15E0)");
		assertNull(s.scriptHost.global("DoorKey"), "GlobalData 는 스냅숏 그대로 (뒤에 생긴 변수 없음)");
		assertEquals(5, s.scriptHost.global("ZoneArea"));
		assertEquals(1, s.difficulty);
		// 쉬움으로 다시 하기 (0x29F484): 난이도 0, 다음 재시작도 0 (메모리카드 불러오기만 지움)
		cp.applyCheckpoint(s, true);
		assertEquals(0, s.difficulty);
		cp.applyCheckpoint(s, false);
		assertEquals(0, s.difficulty, "0x29F484 는 남는다");
		cp.applyTo(s);
		assertEquals(1, s.difficulty, "메모리카드 불러오기는 세이브 난이도");
		assertFalse(s.easyRestart);
		assertSame(cp, s.checkpoint, "불러온 세이브가 곧 체크포인트 (0x21FB70: 카드 버퍼 = 스냅숏, 상태 9 는 찍지 않음)");
	}

	/** 키클롭스 (템플릿 40) 스폰만 — 광장은 시작에 시민·병사도 낸다 (A04MI04 aoXSC 328 …). */
	private static List<ScriptListener.SpawnRequest> cyclopes(Rec rec) {
		return rec.spawns.stream().filter(r -> r.templateId() == 40).toList();
	}

	/** 광장 결투를 실제 흐름으로: TriggerVol0 → 키클롭스 1 → 처치 (LevelData0 1) → 둘 → 처치 (3) → 결계 VisOff · AniOut (지연 1.5 초). */
	private static float[] duel(ScriptWorld w, LevelData lv, Rec rec, boolean finish) {
		ScriptEntity trig = w.find("DMCsqr-DMC-TriggerVol0-PlayerEntry");
		assertNotNull(trig);
		float[] in = TraversalVerifier.interior(trig.volume, new LevelCollision(lv.sheets));
		assertNotNull(in);
		for (int i = 0; i < 60; i++) {
			w.tick(1 / 60f, in[0], in[1], in[2]);
		}
		assertEquals(1, rec.spawns.stream().filter(r -> r.templateId() == 40).count(), "키클롭스 1 (SpawnEnemy 9, 지연 0.5 초)");
		if (finish) {
			w.destroy(cyclopes(rec).getFirst().actor());
			w.tick(1 / 60f, in[0], in[1], in[2]);
			List<ScriptListener.SpawnRequest> two = cyclopes(rec).subList(1, cyclopes(rec).size());
			assertEquals(2, two.size(), "LevelData0 == 1 → SpawnEnemy 10 · 11");
			two.forEach(r -> w.destroy(r.actor()));
			w.tick(1 / 60f, in[0], in[1], in[2]);
			assertEquals(3, w.host().levelData().get(0));
		}
		return in;
	}

	@Test
	void plazaDuelTriggerIsNotReArmedOnRevisit() throws Exception {
		GameSession s = session();
		LevelData lv = level("ATHN04A");
		s.enterLevel("ATHN04A");
		Rec first = new Rec();
		ScriptWorld w1 = new ScriptWorld(lv, s.scriptHost, first);
		w1.start();
		float[] in = duel(w1, lv, first, true);
		for (int i = 0; i < 120; i++) { // 결계 AniOut 지연 1.5 초 → dogOut
			w1.tick(1 / 60f, in[0] + 2000, in[1], in[2]);
		}
		assertTrue(first.anims.contains("dogOut"));
		ScriptWorld.Memory left = w1.memory();
		int trigId = w1.find("DMCsqr-DMC-TriggerVol0-PlayerEntry").id();
		assertTrue(left.fired().containsKey(trigId), "재발동 −1 센서의 발동 기록 (메시지 7 → 0x1B2280)");
		assertEquals(3f, left.fired().get(w1.find("DMCCyclops-CyclopsLevel1AI2").id())[1] + left.fired().get(w1.find("DMCCyclops-CyclopsLevel1AI3").id())[1]
				+ left.fired().get(w1.find("DMCCyclops-CyclopsLevel1AI1").id())[1], "SpawnEnemy 만든 수 (+0x100)");
		s.rememberLevel("ATHN04A", w1, List.of(), java.util.Set.of());
		s.enterLevel("ATHN04C"); // 지붕 · 05A … 05D 로
		s.playTime += 900;

		// 05D 엘리베이터로 돌아온 광장: 북쪽 계단 볼륨에 다시 들어가도 결투가 다시 열리지 않는다
		Rec back = new Rec();
		ScriptWorld w2 = new ScriptWorld(lv, s.scriptHost, back);
		w2.restore(s.enterLevel("ATHN04A"));
		w2.start();
		w2.tick(1 / 60f, in[0] + 2000, in[1], in[2]);
		for (int i = 0; i < 120; i++) {
			w2.tick(1 / 60f, in[0], in[1], in[2]);
		}
		assertTrue(back.spawns.stream().noneMatch(r -> r.templateId() == 40), "키클롭스 없음");
		assertEquals(3, s.scriptHost.levelData().get(0), "LevelData0 = 3 그대로 (결계 조건 LevelData0 == LevelData4)");
		assertFalse(back.anims.contains("dogIn"), "결계가 다시 서지 않는다");

		// 대조: 발동 기록이 없는 기억 (예전 엔진) — 같은 자리에서 결투가 다시 열린다
		ScriptWorld.Memory m = s.enterLevel("ATHN04A");
		ScriptWorld.Memory old = new ScriptWorld.Memory(m.levelData(), m.created(), m.vis(), m.visBy(), m.opened());
		Rec rerun = new Rec();
		ScriptWorld w3 = new ScriptWorld(lv, new GameSession(ROM).scriptHost, rerun);
		w3.restore(old);
		w3.start();
		w3.tick(1 / 60f, in[0] + 2000, in[1], in[2]);
		for (int i = 0; i < 120; i++) {
			w3.tick(1 / 60f, in[0], in[1], in[2]);
		}
		assertTrue(rerun.spawns.stream().anyMatch(r -> r.templateId() == 40), "발동 기록이 없으면 키클롭스가 다시");
		assertTrue(rerun.anims.contains("dogIn"));
	}

	@Test
	void livingEnemiesAndDelayedTriggersComeBackFromTheMemory() throws Exception {
		GameSession s = session();
		LevelData lv = level("ATHN04A");
		s.enterLevel("ATHN04A");
		Rec first = new Rec();
		ScriptWorld w1 = new ScriptWorld(lv, s.scriptHost, first);
		w1.start();
		float[] in = duel(w1, lv, first, false);
		ScriptListener.SpawnRequest cyc = cyclopes(first).getFirst();
		cyc.actor().moveTo(in[0] + 30, in[1], in[2] - 40); // 싸우며 움직인 자리
		ScriptWorld.Memory m = w1.memory();
		int cycId = w1.find("DMCCyclops-CyclopsLevel1AI1").id();
		assertEquals(1, m.enemies().stream().filter(e -> (int) e[0] == cycId).count(), "살아 있는 키클롭스 (원작 +0x20 형 3 기록)");

		Rec back = new Rec();
		ScriptWorld w2 = new ScriptWorld(lv, new GameSession(ROM).scriptHost, back);
		w2.restore(m);
		w2.start();
		assertEquals(1, cyclopes(back).size(), "SpawnEnemy 9 시작 메시지에서 다시 (0x1AC630 → 0x1A6C70)");
		assertEquals(m.enemies().size(), back.spawns.size(), "시작 스폰 (SpawnAtStart) 은 다시 돌지 않고 기록된 적만");
		ScriptListener.SpawnRequest r = cyclopes(back).getFirst();
		assertEquals(40, r.templateId());
		assertEquals(-1, r.spawnAction(), "등장 동작 없이");
		assertEquals(in[0] + 30, r.world()[12], 1e-3);
		assertEquals(in[2] - 40, r.world()[14], 1e-3);
		assertSame(w2.find("DMCCyclops-CyclopsLevel1AI1"), r.source());
		// 다시 낸 적을 처치하면 결투가 이어진다 (LevelData0 → 1 → 둘)
		w2.destroy(r.actor());
		w2.tick(1 / 60f, in[0], in[1], in[2]);
		assertEquals(1, w2.host().levelData().get(0));
		assertEquals(3, cyclopes(back).size());

		// 미뤄 둔 발동 (+0x38): 마지막 처치 직후 (AniOut 지연 1.5 초 안) 에 기억하면 되살린 세계에서 남은 시간 뒤 dogOut
		cyclopes(back).subList(1, 3).forEach(q -> w2.destroy(q.actor()));
		w2.tick(1 / 60f, in[0], in[1], in[2]);
		assertEquals(3, w2.host().levelData().get(0));
		ScriptWorld.Memory mid = w2.memory();
		assertFalse(mid.pending().isEmpty(), "AniOut 대기");
		Rec third = new Rec();
		ScriptWorld w3 = new ScriptWorld(lv, new GameSession(ROM).scriptHost, third);
		w3.restore(mid);
		w3.start();
		assertFalse(third.anims.contains("dogOut"));
		for (int i = 0; i < 100; i++) {
			w3.tick(1 / 60f, in[0] + 2000, in[1], in[2]);
		}
		assertTrue(third.anims.contains("dogOut"), "남은 지연 뒤 결계가 내려간다");
	}

	@Test
	void agingAddsTheTimeAwayToFiredRecords() {
		ScriptWorld.Memory m = new ScriptWorld.Memory(java.util.Map.of(), java.util.Set.of(), java.util.Map.of(), java.util.Map.of(), java.util.Set.of(),
				java.util.Map.of(7, new float[]{2, 0, 0}), List.of(), List.<float[]>of(new float[]{9, 5, 1}), List.of(), java.util.Set.of(), 100f);
		ScriptWorld.Memory a = m.aged(103);
		assertEquals(5, a.fired().get(7)[0], 1e-4, "2 초 전 + 3 초 = 5 초 전 (원작 절대 시각 0x29D920)");
		assertEquals(2, a.pending().getFirst()[1], 1e-4, "남은 5 초 − 3 초");
		assertSame(m, m.aged(99));
	}

	@Test
	void snapshotWaitsWhileAirborneOrOnARope() {
		assertFalse(GameScene.snapshotWaits(0x1, 100), "지상");
		assertFalse(GameScene.snapshotWaits(0x20, 100), "벽·덩굴 (0x20) 은 찍는다 — 오라클 덩굴 체크포인트");
		assertTrue(GameScene.snapshotWaits(0x4, 100), "점프");
		assertTrue(GameScene.snapshotWaits(0x8, 100), "떨어짐");
		assertTrue(GameScene.snapshotWaits(0x400, 100), "줄");
		assertTrue(GameScene.snapshotWaits(0x1, 0.5f), "HP < 1");
	}

	@Test
	void snapshotRoundTripsThroughTheSaveFile(@TempDir Path tmp) throws Exception {
		GameSession s = session();
		LevelData lv = level("ATHN04A");
		s.enterLevel("ATHN04A");
		Rec rec = new Rec();
		ScriptWorld w = new ScriptWorld(lv, s.scriptHost, rec);
		w.start();
		duel(w, lv, rec, false);
		s.scriptHost.timers.create(12);
		List<Platforms.State> anims = List.of(new Platforms.State("godmcsqr", "dogIn", 0.5f, 1, false, false, false, true, 0),
				new Platforms.State("golift", null, 0, 1, false, false, false, false, 32));
		s.rememberLevel("ATHN04A", w, anims, java.util.Set.of("gobreakdoorstone"));
		SaveGame g = SaveGame.capture(s, "ATHN04A", "ATHN04B", false, new float[]{1, 2, 3}, 0.5f);
		g.heroState = "WALL_CLIMB";
		g.music = "SND_M_ATH4C1_2";
		g.write(tmp.resolve("slot0.save"));
		SaveGame r = SaveGame.read(tmp.resolve("slot0.save"));
		assertEquals("ATHN04B", r.other);
		assertFalse(r.otherActive);
		assertEquals("WALL_CLIMB", r.heroState, "이동 상태 (스냅숏 +0x40)");
		assertEquals("SND_M_ATH4C1_2", r.music);
		assertArrayEquals(g.timers, r.timers);
		assertEquals(g.initedGlobals, r.initedGlobals);
		assertFalse(r.initedGlobals.isEmpty(), "광장 GlobalData 엔티티 초기화 기록");
		GameSession t = session();
		r.applyTo(t);
		ScriptWorld.Memory m = t.enterLevel("ATHN04A");
		ScriptWorld.Memory orig = w.memory();
		assertEquals(orig.fired().keySet(), m.fired().keySet(), "발동 기록 (levelFired.*)");
		assertEquals(orig.enemies().size(), m.enemies().size(), "살아 있는 적 (levelEnemies.*)");
		assertTrue(m.enemies().stream().anyMatch(e -> (int) e[0] == w.find("DMCCyclops-CyclopsLevel1AI1").id()));
		assertEquals(anims, m.anims(), "Animator 몸 (levelAnims.*)");
		assertEquals(java.util.Set.of("gobreakdoorstone"), m.broken(), "부순 물체 (levelBroken.*)");
		assertTrue(t.scriptHost.timers.active(1));
	}
}
