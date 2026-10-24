package devple.gow.engine.game;

import devple.gow.engine.level.LevelCollision;
import devple.gow.engine.script.ScriptEntity;
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
 * 레벨 기억 (원작 레벨 상태 표 0x336B28 · 첫 적재 관문 0x1A9F30 → 0x1B3C58, {@link ScriptWorld.Memory}, 43 문서 §21).
 * ATHN05D 엘리베이터로 돌아온 ATHN04A 광장 (영상 8416–8458): 첫 방문의 LevelData (키클롭스 처치 LevelData0 = 3) 가 이어져 시민 (SecTP, 조건
 * LevelData0 &lt; LevelData4) 이 다시 나오지 않는다. 예전 엔진은 늘 처음부터 시작해 광장에 시민 8 명이 다시 나왔다.
 */
class LevelMemoryTest {

	private static final Path ROM = Path.of(System.getProperty("gow.rom", "../gow-rom"));

	private static LevelData level(String name) throws Exception {
		Path p = Content.file(ROM, name + ".WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return new LevelData(WadFile.load(p));
	}

	private static GameSession session() throws Exception {
		Assumptions.assumeTrue(Files.exists(Content.file(ROM, Region.of(ROM).perm())), "gow-rom 없음");
		return new GameSession(ROM);
	}

	/** 생성·스폰·Vis 기록 리스너. */
	private static final class Rec implements ScriptListener {
		final List<String> created = new ArrayList<>();
		final List<SpawnRequest> spawns = new ArrayList<>();
		final List<String> visOn = new ArrayList<>(), visOff = new ArrayList<>();

		@Override
		public void create(ScriptEntity source) {
			created.add(source.name());
		}

		@Override
		public void spawnEnemy(SpawnRequest r) {
			spawns.add(r);
		}

		@Override
		public void vis(ScriptEntity source, List<String> objects, int[] values) {
			if (values.length > 0 && values[0] == 1) {
				visOn.addAll(objects);
			} else if (values.length > 0 && values[0] == 2) {
				visOff.addAll(objects);
			}
		}
	}

	private static float[] sectionZone1(ScriptWorld w, LevelData lv) {
		ScriptEntity z = w.find("SectionZone1-pCube4410-EntrySensor1");
		assertNotNull(z);
		float[] p = TraversalVerifier.interior(z.volume, new LevelCollision(lv.sheets));
		assertNotNull(p);
		return p;
	}

	@Test
	void plazaRevisitKeepsLevelDataAndSkipsFirstLoadMessages() throws Exception {
		GameSession s = session();
		LevelData lv = level("ATHN04A");
		// 첫 방문: 표에 칸을 열고 처음부터 (Init · 시작 생성 · 모드 1 생성 센서)
		assertNull(s.enterLevel("ATHN04A"));
		Rec first = new Rec();
		ScriptWorld w1 = new ScriptWorld(lv, s.scriptHost, first);
		w1.start();
		assertFalse(w1.restored());
		assertEquals(3, s.scriptHost.levelData().get(4), "LevelData1 Init: 키클롭스 수 3");
		assertTrue(first.created.contains("Chest4"), "상자 Creator 시작 생성");
		assertTrue(first.visOff.stream().anyMatch(o -> o.toLowerCase().contains("suicidebody")), "SuicideBody-CreationSensor1 (모드 1) → VisOff");
		// 광장 결투가 끝남 (DMCsqr DestructionSensor 가 LevelData0 을 3 까지) · 여자 투신 (GlobalData ShesDeadJim)
		w1.presetState("L0=3,GShesDeadJim=true");
		s.rememberLevel("ATHN04A", w1); // 지붕 · 05C · 05D 로 가며 슬롯에서 내려감
		assertNull(s.enterLevel("ATHN05D"));

		// 다시 읽음 (05D 엘리베이터 Warp)
		ScriptWorld.Memory m = s.enterLevel("ATHN04A");
		assertNotNull(m, "표에 칸과 덩어리가 있다");
		Rec back = new Rec();
		ScriptWorld w2 = new ScriptWorld(lv, s.scriptHost, back);
		w2.restore(m);
		w2.start();
		assertTrue(w2.restored());
		assertEquals(3, s.scriptHost.levelData().get(0), "LevelData 가 이어진다 (풀 0x301548 · 기준 +0x18 되살림)");
		assertEquals(3, s.scriptHost.levelData().get(4), "Init 을 다시 돌리지 않는다");
		assertTrue(back.created.contains("Chest4"), "만들어 둔 Creator 를 되살림 (0x1A6C70)");
		assertTrue(back.visOn.stream().anyMatch(o -> o.toLowerCase().contains("suicidebody")),
				"모드 5 SpecialCreationSensor 는 매번 — ShesDeadJim 이면 시신을 다시 켠다");

		// 광장 SectionZone1 에 들어서도 시민이 나오지 않는다 (SecTP 조건 LevelData0 < LevelData4)
		float[] p = sectionZone1(w2, lv);
		for (int i = 0; i < 30; i++) {
			w2.tick(1 / 60f, p[0], p[1], p[2]);
		}
		assertTrue(back.spawns.stream().noneMatch(r -> r.templateId() == 313), "되살린 광장: 시민 없음");

		// 대조: 처음부터면 같은 자리에서 시민 (AOXY1 템플릿 313) 이 나온다 (예전 엔진의 재방문)
		GameSession fresh = session();
		Rec r3 = new Rec();
		ScriptWorld w3 = new ScriptWorld(lv, fresh.scriptHost, r3);
		w3.start();
		for (int i = 0; i < 30; i++) {
			w3.tick(1 / 60f, p[0], p[1], p[2]);
		}
		assertTrue(r3.spawns.stream().anyMatch(r -> r.templateId() == 313), "처음 방문 광장: 시민");
	}

	/** 판도라 신전 입구 (0x1B38A8 → 0x1B3A10): "Pand00A" 바로 뒤 "Pand00B" 를 처음 읽으면 표를 두 칸만 남기고 비운다. */
	@Test
	void pandoraTempleEntranceClearsTheTable() throws Exception {
		GameSession s = session();
		LevelData lv = level("ATHN04A");
		s.enterLevel("ATHN04A");
		ScriptWorld w = new ScriptWorld(lv, s.scriptHost, null);
		w.start();
		s.rememberLevel("ATHN04A", w);
		s.enterLevel("DEST03");
		s.enterLevel("PAND00A");
		assertNotNull(s.levelMemory.get("ATHN04A"));
		assertNull(s.enterLevel("PAND00B"));
		assertTrue(s.levelMemory.isEmpty());
		assertEquals(List.of("PAND00A", "PAND00B"), s.levelTable());
		assertNull(s.enterLevel("ATHN04A"), "비운 뒤엔 처음부터");
	}

	@Test
	void saveCarriesTheLevelTable(@TempDir Path tmp) throws Exception {
		GameSession s = session();
		s.player = new PlayerState(GameRules.load(s.permk));
		LevelData lv = level("ATHN04A");
		s.enterLevel("ATHN04A");
		ScriptWorld w = new ScriptWorld(lv, s.scriptHost, null);
		w.start();
		w.presetState("L0=3");
		w.markOpened("gochest4"); // 연 상자 (NeighbourChestTest)
		s.rememberLevel("ATHN04A", w);
		SaveGame.capture(s, "ATHN05D", new float[]{0, 0, 0}, 0).write(tmp.resolve("slot0.save"));
		GameSession t = session();
		SaveGame.read(tmp.resolve("slot0.save")).applyTo(t);
		ScriptWorld.Memory m = t.enterLevel("ATHN04A");
		assertNotNull(m);
		assertEquals(3, m.levelData().get(0));
		assertTrue(m.created().contains("Chest4"));
		assertEquals(java.util.Set.of("gochest4"), m.opened(), "연 상자도 세이브에 (levelOpened)");
		assertFalse(m.vis().isEmpty());
		assertEquals(w.memory().vis().keySet(), m.vis().keySet());
	}
}
