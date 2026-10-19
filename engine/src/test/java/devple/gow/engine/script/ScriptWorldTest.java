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
}
