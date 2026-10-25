package devple.gow.engine.combat;

import devple.gow.engine.boss.CombatScriptBridge;
import devple.gow.engine.game.Tweaks;
import devple.gow.engine.script.ScriptActor;
import devple.gow.engine.script.ScriptListener;
import devple.gow.tools.combat.CombatFile;
import devple.gow.tools.content.Content;
import devple.gow.tools.content.Region;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.level.WaypointGraph;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 32 문서 §순찰 · 43 §32.6: 웨이포인트 그래프 (WYP_, 0x187BC8) 와 원작 순찰 (생성 InitState 2 · 4 → 0x245DB0 → 0x1D17B8, 한 걸음 0x1D0348,
 * 스크립트 1025 → 0x265478). 예전 엔진은 WYP 를 읽지 않고 {@code AiBrain.setPatrol} 을 부르는 곳이 없어 순찰하는 적이 하나도 없었다
 * (DEST03 타이탄이 스폰 자리에 "main" 으로 서 있었다).
 */
class PatrolTest {

	static final float DT = 1 / 60f;

	private static Path rom() {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(Content.file(rom, Region.of(rom).perm())), "gow-rom 없음");
		return rom;
	}

	private static WadFile permk;

	private static WadFile permk() throws Exception {
		if (permk == null) {
			permk = WadFile.load(Content.file(rom(), Region.of(rom()).perm()));
		}
		return permk;
	}

	private static LevelData level(String name) throws Exception {
		return new LevelData(WadFile.load(Content.file(rom(), name + ".WAD")));
	}

	private static Hittable dummy(Vector3f p) {
		return new Hittable() {
			public Vector3f position() {
				return p;
			}

			public float hp() {
				return 100;
			}

			public int classId() {
				return 0;
			}

			public boolean invulnerable() {
				return true;
			}

			public Result receive(CombatFile.Attack a, Combatant at, float d, Vector3f kn) {
				return Result.IGNORED;
			}
		};
	}

	@Test
	void waypointGraphsParse() throws Exception {
		LevelData d3 = level("DEST03");
		Map<String, WaypointGraph> g = d3.waypoints();
		assertEquals(Set.of("site1030", "patrol1030", "site1031"), g.keySet());
		WaypointGraph ring = d3.patrol(1030);
		assertNotNull(ring);
		assertEquals(50, ring.size());
		assertEquals(2522.7f, ring.x(0), 0.1f);
		assertEquals(1139.9f, ring.z(0), 0.1f);
		assertArrayEquals(new int[]{1}, ring.out(0));
		assertEquals(0, ring.in(0).length, "순찰 고리는 한 방향 (나가는 간선 하나)");
		assertEquals(20f, ring.radius(0), 1e-4f);
		assertEquals(10f, ring.radius(5), 1e-4f);
		// 0 → 1 → 3 → 5 → … 동쪽 끝 48 → 47 → … → 2 → 49 → 0: 50 걸음에 모든 노드를 한 번씩
		Set<Integer> seen = new HashSet<>();
		int n = 0;
		List<Integer> path = new ArrayList<>();
		for (int i = 0; i < 50; i++) {
			seen.add(n);
			path.add(n);
			n = ring.out(n)[0];
		}
		assertEquals(0, n, "고리");
		assertEquals(50, seen.size());
		assertEquals(List.of(0, 1, 3, 5, 7, 8), path.subList(0, 6));
		// 자리 그래프는 양방향 (낮은 번호의 나가는 간선 + 높은 번호의 들어오는 간선)
		WaypointGraph site = g.get("site1031");
		assertEquals(121, site.size());
		assertArrayEquals(new int[]{1, 2, 3, 10}, site.out(0));
		assertArrayEquals(new int[]{0}, site.in(1));
		// 슬롯 9 문자열 → 번호 (0x1AC9B8: "patrol" 대소문자 무시 + 10 진수)
		assertEquals(1030, WaypointGraph.patrolNumber("Patrol1030"));
		assertEquals(3, WaypointGraph.patrolNumber("patrol3"));
		assertEquals(1, WaypointGraph.patrolNumber("PATROL1"));
		assertEquals(-1, WaypointGraph.patrolNumber("Site3"));
		assertEquals(-1, WaypointGraph.patrolNumber(null));
		assertEquals(-1, WaypointGraph.patrolNumber(""));
		// DEST01 세이렌 경로
		LevelData d1 = level("DEST01");
		assertEquals(19, d1.patrol(1).size());
		assertEquals(40, d1.patrol(2).size());
		assertEquals(27, d1.patrol(3).size());
	}

	/**
	 * ATHN02C 복도 미노타우로스 (템플릿 50, InitState 2, Patrol14 다섯 노드 — 0→1→2→3→4 한 줄, 끝 노드 간선 없음): 노드 반경 (1 × 16) 안에서 다음
	 * 노드로 복도를 걸어와 끝에서 순찰을 놓는다 (0x1D0748 → 0x411). 걸음 = Sold 속도 × AI1 0x64 %.
	 */
	@Test
	void minotaurWalksTheHallwayLine() throws Exception {
		LevelData lv = level("ATHN02C");
		WaypointGraph ring = lv.patrol(14);
		assertNotNull(ring);
		EnemyFighter f = EnemyFactory.load(Content.file(rom(), "R_MIN.WAD"), permk(), -1).spawn(50);
		f.brain().setPatrols(lv::patrol);
		f.pos.set(ring.x(0), ring.y(0), ring.z(0));
		assertTrue(f.brain().startIdle(2, 14));
		assertTrue(f.brain().patrolling());
		assertEquals(0, f.brain().patrolNode(), "가장 가까운 노드부터 (0x1D1854)");
		assertFalse(f.brain().passive());
		List<Integer> visits = new ArrayList<>();
		int last = f.brain().patrolNode();
		float maxStep = 0;
		Vector3f prev = new Vector3f(f.pos);
		for (float t = 0; t < 90; t += DT) {
			f.update(DT);
			maxStep = Math.max(maxStep, prev.distance(f.pos) / DT);
			prev.set(f.pos);
			if (f.brain().patrolNode() != last) {
				visits.add(f.brain().patrolNode());
				last = f.brain().patrolNode();
			}
		}
		assertEquals(List.of(1, 2, 3, 4, -1), visits, "나가는 간선 순서, 끝 노드 4 에서 순찰 끝");
		assertFalse(f.brain().patrolling());
		assertEquals(AiBrain.State.IDLE, f.brain().state());
		assertEquals(ring.x(4), f.pos.x, 16f + 1f, "끝 노드 반경 안");
		// 지상 속도 = Sold +0x178 (미노 3 m/s) × AI1 0x64 (#cd7da53a) % × 16 [근사: 가속 · 회전 포함 최대]
		float want = 3f * f.template().speedPctAlt / 100f * 16f;
		assertEquals(want, maxStep, want * 0.25f, "순찰 걸음 속도");
	}

	/** 모드 4 (ai+0x48 비트 0, 0x26B190): 시야 안 대상도 싸움에 들지 않는다. 모드 2 는 인지하면 전투. */
	@Test
	void passivePatrolIgnoresTheHero() throws Exception {
		LevelData lv = level("ATHN02C");
		WaypointGraph ring = lv.patrol(14);
		for (int mode : new int[]{2, 4}) {
			EnemyFighter f = EnemyFactory.load(Content.file(rom(), "R_MIN.WAD"), permk(), -1).spawn(50);
			f.brain().setPatrols(lv::patrol);
			f.pos.set(ring.x(0), ring.y(0), ring.z(0));
			f.brain().startIdle(mode, 14);
			Vector3f hero = new Vector3f(f.pos).add(0, 0, -60); // 바로 앞 (정면 −Z, yaw 0)
			f.yaw = 0;
			f.setFoe(dummy(hero));
			for (int i = 0; i < 30; i++) {
				f.update(DT);
			}
			if (mode == 4) {
				assertTrue(f.brain().passive());
				assertEquals(AiBrain.State.PATROL, f.brain().state(), "모드 4 는 보고도 순찰");
			} else {
				assertEquals(AiBrain.State.COMBAT, f.brain().state(), "모드 2 는 인지하면 전투");
			}
		}
	}

	/** 스크립트 1025 (0x265478): 싸우던 적도 대기 모드로 돌아가 순찰 번호 = 인자 2 (DEST01 SwitchSiteZone-SwitchSite (2, 1) 과 같은 꼴). */
	@Test
	void event1025SwitchesToAPatrol() throws Exception {
		LevelData lv = level("ATHN02C");
		EnemyFighter f = EnemyFactory.load(Content.file(rom(), "R_MIN.WAD"), permk(), -1).spawn(50);
		f.brain().setPatrols(lv::patrol);
		WaypointGraph p15 = lv.patrol(15);
		f.pos.set(p15.x(2), p15.y(2), p15.z(2));
		f.yaw = 0;
		f.setFoe(dummy(new Vector3f(f.pos).add(0, 0, -60)));
		for (int i = 0; i < 30; i++) {
			f.update(DT);
		}
		assertEquals(AiBrain.State.COMBAT, f.brain().state());
		CombatScriptBridge bridge = new CombatScriptBridge(null);
		ScriptActor a = new ScriptActor("mino", 98, new float[]{f.pos.x, f.pos.y, f.pos.z}, null);
		bridge.attach(f, a);
		assertTrue(bridge.objectEvent(new ScriptListener.ObjectEvent(null, a.name, a, CombatScriptBridge.EVENT_IDLE_MODE, 4, 15)));
		assertTrue(f.brain().patrolling());
		assertSame(p15, f.brain().patrolGraph());
		assertEquals(2, f.brain().patrolNode(), "지금 자리에서 가장 가까운 노드");
		assertTrue(f.brain().passive(), "모드 4");
		assertNotEquals(AiBrain.State.COMBAT, f.brain().state());
		// 모드 0 = 서기 (0x25BA98): 순찰을 놓는다
		bridge.objectEvent(new ScriptListener.ObjectEvent(null, a.name, a, CombatScriptBridge.EVENT_IDLE_MODE, 0, 0));
		assertFalse(f.brain().patrolling());
	}

	/**
	 * DEST03 타이탄 (레벨 템플릿 aoTIT 321, DecisionTree 0 · AI Mode 5, Sold 1 m/s): Navigation ANIWalk "TIT1d01Mi02" 로 기며 Patrol1030 을 돈다
	 * (영상 2970 오른쪽 위 모래 언덕). 예전엔 걷기 클립이 첫 act "main" 이고 결정 트리가 없어 서 있기만 했다.
	 */
	@Test
	void titanCrawlsAlongPatrol1030() throws Exception {
		Path wad = Content.file(rom(), "DEST03.WAD");
		LevelData lv = new LevelData(WadFile.load(wad));
		Tweaks lt = new Tweaks();
		lt.load(lv.wad);
		String obj = EnemyFactory.levelTemplateObject(lt, 321);
		assertEquals("aotit", obj);
		EnemyFactory.Kind k = EnemyFactory.load(wad, permk(), 321, obj);
		assertEquals("TIT1d01Mi02", k.walk(), "Navigation ANIWalk");
		assertEquals("TIT1d01Mi03", k.idle(), "Navigation ANIStand");
		EnemyFighter f = k.spawn(321);
		assertNull(f.brain().tree);
		assertEquals(5, f.template().mode, "AI Mode 5: 대상을 잡지 않음");
		f.brain().setPatrols(lv::patrol);
		f.pos.set(2452, 61, 1171); // Titan-aoTITAI1 스폰 자리
		f.setFoe(dummy(new Vector3f(3680, 550, 3150)));
		assertTrue(f.brain().startIdle(2, 1030));
		assertEquals(0, f.brain().patrolNode());
		Vector3f start = new Vector3f(f.pos);
		for (float t = 0; t < 60; t += DT) {
			f.update(DT);
		}
		assertEquals("TIT1d01Mi02", f.animator().current().name, "기어가기");
		float moved = start.distance(f.pos);
		// 1 m/s × #cd7da53a 50 % × 16 = 8 유닛/초 → 60 초에 ≈ 480 [근사: 가속]
		assertEquals(480f, moved, 80f, "60 초 이동 " + f.pos);
		assertTrue(f.brain().patrolNode() != 0, "다음 노드로 " + f.brain().patrolNode());
		assertEquals(AiBrain.State.PATROL, f.brain().state());
	}
}
