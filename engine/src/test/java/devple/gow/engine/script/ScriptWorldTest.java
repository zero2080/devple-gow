package devple.gow.engine.script;

import devple.gow.tools.anim.Mat4;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.script.EscScript;
import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScriptWorldTest {

	private static final Path ROM = Path.of(System.getProperty("gow.rom", "../gow-rom"));

	/** 통지 기록. */
	static final class Rec implements ScriptListener {
		final List<String> log = new ArrayList<>();
		final List<ScriptListener.SpawnRequest> spawns = new ArrayList<>();

		@Override
		public void message(int msgId, float seconds) {
			log.add("msg " + msgId + " " + seconds);
		}

		@Override
		public void checkpoint(ScriptEntity source) {
			log.add("checkpoint " + (source != null ? source.name() : null));
		}

		@Override
		public void level(LevelRequest r) {
			log.add("level " + r.op() + " " + r.wad());
		}

		@Override
		public void deathZone(ScriptActor actor, ScriptEntity source, int event, int arg) {
			log.add("death " + actor.name + " " + event + " " + arg + " " + source.group());
		}

		@Override
		public void objectEvent(ObjectEvent e) {
			log.add("event " + e.objectName() + " " + e.event() + " " + e.arg1());
		}

		@Override
		public void spawnEnemy(SpawnRequest r) {
			spawns.add(r);
			log.add("spawn " + r.source().name() + " tpl " + r.templateId());
		}

		@Override
		public void music(String func, List<Object> args) {
			log.add("music " + func + " " + args);
		}

		boolean has(String prefix) {
			return log.stream().anyMatch(s -> s.startsWith(prefix));
		}
	}

	private static LevelData level(String wad) throws Exception {
		Path p = ROM.resolve(wad);
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return new LevelData(WadFile.load(p));
	}

	/** 볼륨 안의 한 점 (월드). */
	private static float[] inside(LevelData.PlacedVolume v) {
		float[] c;
		if (!v.hull().balls.isEmpty()) {
			c = v.hull().balls.getFirst();
		} else {
			c = v.hull().hulls.getFirst().center();
		}
		float[] w = new float[3];
		Mat4.transformPoint(v.world(), c[0], c[1], c[2], w);
		if (v.contains(w[0], w[1], w[2])) {
			return w;
		}
		// hull 중심이 밖이면 경계구 안 격자 탐색
		float[] s = v.hull().sphere;
		for (int i = 0; i <= 20; i++) {
			for (int j = 0; j <= 20; j++) {
				for (int k = 0; k <= 20; k++) {
					float x = s[0] + s[3] * (i / 10f - 1), y = s[1] + s[3] * (j / 10f - 1), z = s[2] + s[3] * (k / 10f - 1);
					Mat4.transformPoint(v.world(), x, y, z, w);
					if (v.contains(w[0], w[1], w[2])) {
						return w;
					}
				}
			}
		}
		fail(v.hull().name + " 안의 점 없음");
		return w;
	}

	private static LevelData.PlacedVolume volume(LevelData lv, String name) {
		return lv.volumes.stream().filter(v -> v.hull().name.equals(name)).findFirst().orElseThrow();
	}

	private static final float[] FAR = {1e6f, 1e6f, 1e6f};

	@Test
	void loadCheckVolumeTriggersLoadCheck() throws Exception {
		LevelData lv = level("ATHN01A.WAD");
		Rec r = new Rec();
		ScriptWorld w = new ScriptWorld(lv, new ScriptHost(), r);
		w.start();
		w.tick(1 / 60f, FAR[0], FAR[1], FAR[2]);
		assertFalse(r.log.contains("level LOAD_CHECK ATHN01B"));
		float[] p = inside(volume(lv, "ENV_LoadCheckB"));
		w.tick(1 / 60f, p[0], p[1], p[2]);
		assertTrue(r.log.contains("level LOAD_CHECK ATHN01B"), r.log.toString());
	}

	@Test
	void startRunsLevelDataInit() throws Exception {
		ScriptHost host = new ScriptHost();
		ScriptWorld w = new ScriptWorld(level("ATHN01A.WAD"), host, new Rec());
		host.levelData().put(9, 99);
		w.start();
		// LevelData1: PreGrabNum(9) = 2, PostGrabNum(11) = 5, PostLaunchNum(13) = 5, Launch(0) = false
		assertEquals(2, host.levelData().get(9));
		assertEquals(5, host.levelData().get(11));
		assertEquals(5, host.levelData().get(13));
		assertEquals(false, host.levelData().get(0));
		assertEquals("Hero", w.player().name);
		assertEquals(1, w.player().group);
	}

	@Test
	void enemyDeathZoneKills() throws Exception {
		LevelData lv = level("ATHN01A.WAD");
		Rec r = new Rec();
		ScriptWorld w = new ScriptWorld(lv, new ScriptHost(), r);
		w.start();
		w.tick(1 / 60f, FAR[0], FAR[1], FAR[2]);
		float[] p = inside(volume(lv, "ENV_DeathZone1"));
		ScriptActor hero = w.player();
		// 영웅(그룹 1)은 ATHN01A 의 적 전용 DeathZone1(Filter 8) 에 반응하지 않는다
		w.tick(1 / 60f, p[0], p[1], p[2]);
		assertFalse(r.has("death Hero"), r.log.toString());
		ScriptActor skel = w.addActor("skel", 8, FAR);
		w.tick(1 / 60f, FAR[0], FAR[1], FAR[2]);
		skel.moveTo(p[0], p[1], p[2]);
		w.tick(1 / 60f, FAR[0], FAR[1], FAR[2]);
		assertTrue(r.log.contains("death skel 1029 0 DeathZone1"), r.log.toString());
		assertNotNull(hero);
	}

	@Test
	void heroDeathZoneForcesState() throws Exception {
		LevelData lv = level("ATHN05A.WAD");
		Rec r = new Rec();
		ScriptWorld w = new ScriptWorld(lv, new ScriptHost(), r);
		w.start();
		w.tick(1 / 60f, FAR[0], FAR[1], FAR[2]);
		float[] p = inside(volume(lv, "ENV_DeathZone"));
		for (int i = 0; i < 10; i++) {
			w.tick(1 / 60f, p[0], p[1], p[2]);
		}
		assertTrue(r.log.contains("death Hero 1019 504 DeathZone"), r.log.toString());
		assertTrue(r.log.contains("event Hero 1019 504"));
	}

	@Test
	void tutorialMessageAfterTwoKills() throws Exception {
		Rec r = new Rec();
		ScriptHost host = new ScriptHost();
		ScriptWorld w = new ScriptWorld(level("ATHN01A.WAD"), host, r);
		w.start();
		w.tick(1 / 60f, FAR[0], FAR[1], FAR[2]);
		for (int i = 0; i < 2; i++) {
			ScriptActor a = w.addActor("skel" + i, 8, FAR);
			w.tick(1 / 60f, FAR[0], FAR[1], FAR[2]);
			w.destroy(a);
			w.tick(1 / 60f, FAR[0], FAR[1], FAR[2]);
			if (i == 0) {
				assertFalse(r.has("msg 269"), r.log.toString());
			}
		}
		assertEquals(2, host.levelData().get(8));
		assertTrue(r.log.contains("msg 269 5.0"), r.log.toString());
		assertEquals(true, host.levelData().get(2));
	}

	@Test
	void playerStartZoneSpawnsTauntEnemies() throws Exception {
		Rec r = new Rec();
		ScriptWorld w = new ScriptWorld(level("ATHN01A.WAD"), new ScriptHost(), r);
		w.start();
		float[] s = w.startEntity().position();
		for (int i = 0; i < 60 * 12; i++) {
			w.tick(1 / 60f, s[0], s[1], s[2]);
		}
		assertTrue(r.has("level LOAD_WAD ATHN01B"), r.log.toString());
		assertTrue(r.spawns.stream().anyMatch(sp -> sp.source().name().equals("TauntEnemy1-SkellyLevel1AI1") && sp.actor().group == 8),
				r.log.toString());
	}

	/** 모든 레벨: 생성 + 시작 + 몇 틱 (예외 없음). */
	@Test
	void allLevelsInitialize() throws Exception {
		Assumptions.assumeTrue(Files.isDirectory(ROM), "gow-rom 없음");
		ScriptHost host = new ScriptHost();
		int levels = 0, ents = 0;
		try (var files = Files.list(ROM)) {
			for (Path p : files.filter(f -> f.toString().endsWith(".WAD")).sorted().toList()) {
				String n = p.getFileName().toString();
				if (n.startsWith("R_")) {
					continue;
				}
				LevelData lv = new LevelData(WadFile.load(p));
				ScriptWorld w = new ScriptWorld(lv, host, new Rec());
				if (w.entities().isEmpty()) {
					continue;
				}
				w.start();
				float[] s = w.startEntity() != null ? w.startEntity().position() : new float[3];
				for (int i = 0; i < 30; i++) {
					w.tick(1 / 30f, s[0], s[1], s[2]);
				}
				levels++;
				ents += w.entities().size();
			}
		}
		assertTrue(levels > 100, "레벨 " + levels);
		assertTrue(ents > 9000, "엔티티 " + ents);
	}

	/** 인자 수 표 검증: 전 레벨 모든 핸들러가 return 때 스택이 비고 underflow 가 없어야 한다. */
	@Test
	void arityKeepsStackBalanced() throws Exception {
		Assumptions.assumeTrue(Files.isDirectory(ROM), "gow-rom 없음");
		ScriptHost host = new ScriptHost();
		EscVm vm = new EscVm(host);
		List<String> bad = new ArrayList<>();
		vm.onUnbalanced(bad::add);
		int n = 0;
		try (var files = Files.list(ROM)) {
			for (Path p : files.filter(f -> f.toString().endsWith(".WAD")).toList()) {
				for (WadTag t : WadFile.load(p).resources()) {
					if (t.magic() != EscScript.MAGIC) {
						continue;
					}
					EscScript s = new EscScript(t.data());
					for (EscScript.Entity e : s.entities) {
						for (EscScript.Handler h : e.handlers()) {
							vm.run(new EscContext(p.getFileName() + ":" + t.name(), e), h);
							n++;
						}
					}
				}
			}
		}
		assertTrue(n > 10000);
		assertTrue(bad.isEmpty(), bad.size() + " " + bad.stream().limit(20).toList());
	}

	/** 볼륨 안에서 영역 번호 집합이 정확히 {region} 인 점 (월드). */
	private static float[] inRegion(LevelData.PlacedVolume v, int region) {
		float[] s = v.hull().sphere;
		float[] w = new float[3];
		for (int i = 0; i <= 30; i++) {
			for (int j = 0; j <= 30; j++) {
				for (int k = 0; k <= 30; k++) {
					Mat4.transformPoint(v.world(), s[0] + s[3] * (i / 15f - 1), s[1] + s[3] * (j / 15f - 1), s[2] + s[3] * (k / 15f - 1), w);
					if (v.regions(w[0], w[1], w[2]).equals(java.util.Set.of(region))) {
						return w.clone();
					}
				}
			}
		}
		fail(v.hull().name + " 영역 " + region + " 점 없음");
		return w;
	}

	/** 한 ENV 볼륨의 영역별 센서 (36 문서 §2): PAND00B Warpto00A 의 LoadVol(영역 0) 과 WarpVol(영역 1) 은 따로 발동한다. */
	@Test
	void volumeRegionsTriggerSeparately() throws Exception {
		LevelData lv = level("PAND00B.WAD");
		Rec r = new Rec();
		ScriptWorld w = new ScriptWorld(lv, new ScriptHost(), r);
		w.start();
		w.tick(1 / 60f, FAR[0], FAR[1], FAR[2]);
		LevelData.PlacedVolume v = volume(lv, "ENV_Warpto00A");
		float[] p0 = inRegion(v, 0), p1 = inRegion(v, 1);
		w.tick(1 / 60f, p0[0], p0[1], p0[2]);
		w.tick(1 / 60f, p0[0], p0[1], p0[2]);
		assertTrue(r.log.contains("level LOAD_FOR_WARP PAND00A"), r.log.toString());
		assertFalse(r.log.contains("level WARP null"), "LoadVol 만 밟았는데 Warp: " + r.log);
		w.tick(1 / 60f, p1[0], p1[1], p1[2]);
		w.tick(1 / 60f, p1[0], p1[1], p1[2]);
		assertTrue(r.log.contains("level WARP null"), r.log.toString());
	}

	/** Warp 도착 = 모드 2 Start 중 슬롯 0 이 떠나온 레벨인 것 (원작 0x1AE710), 기본 = 첫 모드 1 Start. */
	@Test
	void warpTargetAndDefaultStart() throws Exception {
		ScriptWorld b = new ScriptWorld(level("ATHN06B.WAD"), new ScriptHost(), null);
		assertEquals("WarpFrom6A", b.warpTarget("athn06a").name());
		assertEquals("WarpFrom6C", b.warpTarget("ATHN06C").name());
		assertEquals("Start", b.warpTarget("PAND00A").name());
		ScriptWorld e = new ScriptWorld(level("ATHN01E.WAD"), new ScriptHost(), null);
		assertEquals("InMouth", e.startEntity().name(), "첫 Start 는 Goto 지점");
		assertEquals("Start", e.defaultStart().name());
	}

	/** 이음 전환 도착: 서 있는 볼륨은 이미 안으로 본다 (되돌아가는 LoadCheck 가 바로 발동하지 않음). */
	@Test
	void seamlessPlacementSuppressesEntry() throws Exception {
		LevelData lv = level("ATHN01A.WAD");
		float[] p = inside(volume(lv, "ENV_LoadCheckB"));
		Rec r = new Rec();
		ScriptWorld w = new ScriptWorld(lv, new ScriptHost(), r);
		w.start();
		w.placePlayer(p[0], p[1], p[2], true);
		w.tick(1 / 60f, p[0], p[1], p[2]);
		assertFalse(r.log.contains("level LOAD_CHECK ATHN01B"), r.log.toString());
		w.tick(1 / 60f, FAR[0], FAR[1], FAR[2]);
		w.tick(1 / 60f, p[0], p[1], p[2]);
		assertTrue(r.log.contains("level LOAD_CHECK ATHN01B"), r.log.toString());
	}

	/** 동영상 끝 이벤트 501(인자 0 = 이름): ATHN01D 쌍발차기 문 → Semo02 끝 → Warp (ATHN01F 로). */
	@Test
	void movieDoneEventDrivesWarp() throws Exception {
		LevelData lv = level("ATHN01D.WAD");
		Rec r = new Rec();
		ScriptWorld w = new ScriptWorld(lv, new ScriptHost(), r);
		w.start();
		w.tick(1 / 60f, FAR[0], FAR[1], FAR[2]);
		float[] p = inside(volume(lv, "ENV_toAthn01F"));
		w.tick(1 / 60f, p[0], p[1], p[2]);
		assertTrue(r.log.contains("level LOAD_FOR_WARP ATHN01F"), r.log.toString());
		w.postEvent(ScriptWorld.EVENT_MOVIE_DONE, "Other", 0, 0, null);
		w.tick(1 / 60f, p[0], p[1], p[2]);
		assertFalse(r.log.contains("level WARP null"), "다른 동영상 이름이면 무시");
		w.postEvent(ScriptWorld.EVENT_MOVIE_DONE, "Semo02", 0, 0, null);
		for (int i = 0; i < 30; i++) {
			w.tick(1 / 60f, p[0], p[1], p[2]);
		}
		assertTrue(r.log.contains("level WARP null"), r.log.toString());
	}

	/** 이웃 레벨 스크립트 호스트: GlobalData 공유, LevelData 는 따로 (38 문서 §4). */
	@Test
	void neighbourHostSharesGlobalsOnly() {
		ScriptHost main = new ScriptHost();
		ScriptHost n = ScriptHost.neighbour(main);
		main.globalData().put(3, true);
		main.levelData().put(14, 50);
		assertEquals(true, n.globalData().get(3));
		assertNull(n.levelData().get(14));
		n.globalData().put(4, 7);
		assertEquals(7, main.globalData().get(4));
	}

	/**
	 * SpawnEnemy 슬롯 0 = MaximumCount (원작 0x1AC580): ATHN08B 분신 파도는 SpawnEnemy 마다 최대 수(7/8 → 4/5)가 있고, 그 수만큼 죽어야
	 * "카운터 == 최대" 조건으로 다음 파도가 열린다. 4 파 합 25 = LevelData31 → A07MP05 → Warp(ATHN08C).
	 */
	@Test
	void spawnMaximumCountDrivesWaves() throws Exception {
		LevelData lv = level("ATHN08B.WAD");
		Rec r = new Rec();
		List<String> movies = new ArrayList<>();
		ScriptListener l = new ScriptListener() {
			@Override
			public void spawnEnemy(SpawnRequest q) {
				r.spawnEnemy(q);
			}

			@Override
			public void level(LevelRequest q) {
				r.level(q);
			}

			@Override
			public void movie(String name, int op, int arg) {
				if (op == 0) {
					movies.add(name);
				}
			}
		};
		ScriptWorld w = new ScriptWorld(lv, new ScriptHost(), l);
		w.start();
		float[] p = inside(volume(lv, "ENV_beginEvil"));
		int killed = 0;
		for (int i = 0; i < 60 * 120 && !movies.contains("A07MP05"); i++) {
			w.tick(1 / 60f, p[0], p[1], p[2]);
			if (i % 20 == 0) {
				for (ScriptActor a : List.copyOf(w.actors())) {
					if (a.spawner != null && a.group != 60 && a.group != 2 && !a.destroyed()) { // 가족(60)·소리 로더(2) 는 그대로
						w.destroy(a);
						killed++;
					}
				}
			}
		}
		long wave4 = r.spawns.stream().filter(q -> q.templateId() == 668).count();
		assertEquals(25, wave4, "4 파 MaximumCount 합 (4×5 + 5)");
		assertEquals(4 + 46 + 25 + 25, r.spawns.stream().filter(q -> q.templateId() >= 666 && q.templateId() <= 668).count(),
				"1 파 4 + 2 파 7·7·8·8·8·8 + 3 파 25 + 4 파 25");
		assertTrue(movies.contains("A07MP05"), "LevelData30 == 31 → winEnt: " + killed + " " + movies);
		w.postEvent(ScriptWorld.EVENT_MOVIE_DONE, "A07MP05", 0, 0, w.player());
		w.tick(1 / 60f, p[0], p[1], p[2]);
		w.tick(1 / 60f, p[0], p[1], p[2]);
		assertTrue(r.log.contains("level WARP null"), r.log.toString());
	}

	/**
	 * 두 레벨 슬롯의 전역 이벤트 공유 (원작 PostEvent 큐 0x303DB8 하나): PAND00D FakeEyeDoor 의 205 (모드 5 송신기) 를
	 * PAND00C ElevatorHousing-EventSwitchStatue 가 받아 LevelData22 (엘리베이터 동상) 를 켠다.
	 */
	@Test
	void linkedWorldsShareEvents() throws Exception {
		LevelData c = level("PAND00C.WAD"), d = level("PAND00D.WAD");
		ScriptHost hc = new ScriptHost();
		ScriptWorld wc = new ScriptWorld(c, hc, new Rec());
		ScriptWorld wd = new ScriptWorld(d, ScriptHost.neighbour(hc), new Rec());
		wc.start();
		wd.start();
		wc.linkEvents(wd);
		assertNotEquals(true, hc.levelData().get(22));
		wd.postEvent(205, 0, 0, 0, wd.player());
		wd.tick(1 / 60f, FAR[0], FAR[1], FAR[2]);
		wc.tick(1 / 60f, FAR[0], FAR[1], FAR[2]);
		assertEquals(true, hc.levelData().get(22), "PAND00D → PAND00C 이벤트 205");
	}

	@Test
	void unlinkedWorldsDoNotShareEvents() throws Exception {
		LevelData c = level("PAND00C.WAD"), d = level("PAND00D.WAD");
		ScriptHost hc = new ScriptHost();
		ScriptWorld wc = new ScriptWorld(c, hc, new Rec());
		ScriptWorld wd = new ScriptWorld(d, ScriptHost.neighbour(hc), new Rec());
		wc.start();
		wd.start();
		wc.linkEvents(wd);
		wc.linkEvents(null);
		wd.postEvent(205, 0, 0, 0, wd.player());
		wd.tick(1 / 60f, FAR[0], FAR[1], FAR[2]);
		wc.tick(1 / 60f, FAR[0], FAR[1], FAR[2]);
		assertNotEquals(true, hc.levelData().get(22));
	}
}
