package devple.gow.engine.combat;

import devple.gow.engine.level.LevelCollision;
import devple.gow.engine.nav.NavRope;
import devple.gow.engine.nav.NavWorld;
import devple.gow.engine.script.ScriptEntity;
import devple.gow.engine.script.ScriptHost;
import devple.gow.engine.script.ScriptWorld;
import devple.gow.tools.combat.CombatFile;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 해골 -C 의 자기 벽·줄 이동 (32 문서 §적 자신의 벽·줄 이동) — 원본 레벨 데이터.
 * ATHN01D "CargoRopeEnemies" (AI1 021, 돛대 그물 옆 스폰), ATHN02B "ClimbWallEnemies"/"RopeEnemies" (AI1 023, 벽·수평 줄).
 */
class EnemyClimbTest {

	static final float DT = 1 / 60f;

	private static Path rom() {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(rom.resolve("R_PERMK.WAD")), "gow-rom 없음");
		return rom;
	}

	private static WadFile permk;

	private static EnemyFactory.Kind kind(String wad) throws Exception {
		if (permk == null) {
			permk = WadFile.load(rom().resolve("R_PERMK.WAD"));
		}
		return EnemyFactory.load(rom().resolve(wad), permk, -1);
	}

	record Level(LevelData data, NavWorld nav, ScriptWorld world) {
		float[] spawn(String name) {
			for (ScriptEntity e : world.entities()) {
				if ((e.group() + "#" + e.id()).equals(name)) {
					return e.position();
				}
			}
			throw new AssertionError(name);
		}
	}

	private static Level level(String wad) throws Exception {
		LevelData lv = new LevelData(WadFile.load(rom().resolve(wad)));
		NavWorld nw = new NavWorld(new LevelCollision(lv.sheets));
		nw.ropes().addAll(NavRope.of(lv));
		return new Level(lv, nw, new ScriptWorld(lv, new ScriptHost(), null));
	}

	/** 고정 대상 (영웅 대역): 이동 상태 st (0 지상 1 벽 3 줄). */
	private static Hittable target(Vector3f p, int st) {
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

			public int locomotionState() {
				return st;
			}

			public boolean invulnerable() {
				return true;
			}

			public Result receive(CombatFile.Attack a, Combatant at, float d, Vector3f kn) {
				return Result.IGNORED;
			}
		};
	}

	private static EnemyFighter spawn(EnemyFactory.Kind k, int tpl, Level lv, String entity) {
		EnemyFighter f = k.spawn(tpl);
		float[] q = lv.spawn(entity);
		f.pos.set(q[0], q[1], q[2]);
		f.setNavWorld(EnemyNav.of(lv.nav()));
		f.update(DT);
		return f;
	}

	/** 기술 시작 ID 들 (secs 초). */
	private static Set<Integer> run(EnemyFighter f, float secs) {
		Set<Integer> moves = new HashSet<>();
		boolean was = false;
		for (int i = 0; i < secs / DT; i++) {
			f.update(DT);
			boolean act = f.combat().active();
			if (act && !was) {
				moves.add(f.combat().move().id());
			}
			was = act;
		}
		return moves;
	}

	@Test
	void templateCapsAndSoldSpeeds() throws Exception {
		EnemyFactory.Kind sks = kind("R_SKS.WAD");
		AiTemplate c = sks.spawn(21).template();
		assertEquals(AiTemplate.CAP_CLIMB, c.moveCaps & AiTemplate.CAP_CLIMB, "021 Climb");
		assertNotEquals(0, c.moveCaps & AiTemplate.CAP_ROPE, "021 Rope");
		assertNotEquals(0, c.moveCaps & AiTemplate.CAP_WALL_PULLUP, "021 WallPullup");
		assertEquals(1000, c.pauseMoveMs);
		assertEquals(0, sks.spawn(20).template().moveCaps, "020 (일반 해골) 능력 없음");
		AiData ai = AiData.load(sks.assets().wad());
		AiData.Motion m = ai.motion(20);
		assertEquals(8f, m.ground(), 1e-4, "Sold_020 +0x178");
		assertEquals(80f, m.accel(), 1e-4);
		assertEquals(2.17f, m.wallSide(), 1e-2);
		AiData.Motion min = AiData.load(kind("R_MIN.WAD").assets().wad()).motion(50);
		assertEquals(3f, min.ground(), 1e-4, "미노타우로스 Sold_050 +0x178 3 m/s = 48 유닛/s");
		assertEquals(7.5f, AiData.load(permk).motion(0).maxSpeed(), 1e-4, "크라토스 Sold_000 +0xEC");
	}

	@Test
	void netSkeletonsCatchTheNetAndAttackHeroOnIt() throws Exception {
		EnemyFactory.Kind sks = kind("R_SKS.WAD");
		Level lv = level("ATHN01D.WAD");
		int onWall = 0;
		for (String e : new String[]{"CargoRopeEnemies1#150", "CargoRopeEnemies2#153", "CargoRopeEnemies4#163", "CargoRopeEnemies6#169"}) {
			EnemyFighter f = spawn(sks, 21, lv, e);
			if (f.nav().mode() == EnemyNav.Mode.WALL) {
				onWall++;
			}
		}
		assertEquals(4, onWall, "그물 옆 스폰 → 첫 프레임에 벽");
		// 영웅이 같은 그물 40 위 → DTre_021 "대상 벽 & 자신 벽 → attack up!/down!/left!/right!" (기술 15–18, 분기 상태 0x20 전용).
		// 벽을 타고 24 앞까지 다가가므로 높이 차 1.5 (×16) → attack left/right (높이 > 2 가 아님)
		EnemyFighter f = spawn(sks, 21, lv, "CargoRopeEnemies1#150");
		assertEquals(1, f.locomotionState());
		f.setFoe(target(new Vector3f(f.pos).add(0, 40, 0), 1));
		assertEquals(1f, f.brain().sense(DecisionTree.C_SELF_STATE, 0), "조건 4 = 벽");
		Set<Integer> moves = run(f, 12f);
		assertFalse(moves.isEmpty(), "벽 공격");
		assertTrue(moves.stream().allMatch(m -> m >= 15 && m <= 18), "벽 기술만 (분기 0x20) " + moves);
		assertTrue(f.brain().lastLeaf().name().startsWith("attack "), f.brain().lastLeaf().name());
		assertEquals(EnemyNav.Mode.WALL, f.nav().mode());
	}

	@Test
	void netSkeletonClimbsDownToHeroOnDeck() throws Exception {
		EnemyFactory.Kind sks = kind("R_SKS.WAD");
		Level lv = level("ATHN01D.WAD");
		EnemyFighter f = spawn(sks, 21, lv, "CargoRopeEnemies1#150");
		assertEquals(EnemyNav.Mode.WALL, f.nav().mode());
		Vector3f n = f.nav().wallNormal();
		Vector3f deck = new Vector3f(f.pos).fma(60, n);
		deck.y = lv.nav().groundHeight(deck.x, f.pos.y, deck.z, 2000);
		assertFalse(Float.isNaN(deck.y));
		f.setFoe(target(deck, 0));
		float y0 = f.pos.y;
		int frames = 0;
		while (f.nav().mode() != EnemyNav.Mode.GROUND && frames++ < 60 * 30) {
			f.update(DT);
		}
		assertEquals(EnemyNav.Mode.GROUND, f.nav().mode(), "get the hero! → 벽 평면 투영으로 내려와 갑판");
		assertEquals(deck.y, f.pos.y, 20f);
		// 내려오는 속도 ≈ Sold+0x110 3.7 m/s
		float secs = frames * DT;
		assertTrue((y0 - f.pos.y) / secs > 30f, String.format("%.0f 유닛 / %.1f 초", y0 - f.pos.y, secs));
	}

	@Test
	void ropeSkeletonsHangOnTheTraverseRopeAndRopeAttack() throws Exception {
		EnemyFactory.Kind skc = kind("R_SKC.WAD");
		Level lv = level("ATHN02B.WAD");
		EnemyFighter w = spawn(skc, 23, lv, "ClimbWallEnemies3#68");
		assertEquals(EnemyNav.Mode.WALL, w.nav().mode(), "ATHN02B 벽 스폰");
		EnemyFighter f = spawn(skc, 23, lv, "RopeEnemies2#90");
		for (int i = 0; i < 180 && f.nav().mode() != EnemyNav.Mode.ROPE; i++) {
			f.update(DT);
		}
		assertEquals(EnemyNav.Mode.ROPE, f.nav().mode(), "공중 → 손 32 안의 줄 잡기 (0x1C5C20)");
		assertTrue(f.nav().rope().horizontal());
		assertEquals(3, f.locomotionState());
		// 영웅이 같은 줄 30 앞 → DTre_023 "대상 줄 & 자신 줄 → rope attack!" (기술 29 / 7, 분기 상태 0x80 전용)
		Vector3f along = new Vector3f(f.nav().rope().b()).sub(f.nav().rope().a()).normalize();
		f.setFoe(target(new Vector3f(f.pos).fma(30, along), 3));
		Set<Integer> moves = run(f, 12f);
		assertFalse(moves.isEmpty(), "줄 공격");
		assertTrue(moves.stream().allMatch(m -> m == 29 || m == 7), "줄 기술만 " + moves);
		assertEquals("rope attack!", f.brain().lastLeaf().name());
		assertEquals(EnemyNav.Mode.ROPE, f.nav().mode());
	}

	private static ScriptEntity entity(Level lv, String name) {
		for (ScriptEntity e : lv.world().entities()) {
			if ((e.group() + "#" + e.id()).equals(name)) {
				return e;
			}
		}
		throw new AssertionError(name);
	}

	@Test
	void wallSpawnActionComesFromSpawnSlotAndEndsOnTheWall() throws Exception {
		EnemyFactory.Kind skc = kind("R_SKC.WAD");
		Level lv = level("ATHN02B.WAD");
		// 스폰 슬롯 7 (SpawnAction, 기본 −1): ATHN02B ClimbWallEnemies2 = 504 SKSWALLSPAWN, ATHN01D 그물 해골 = 없음
		ScriptEntity e = entity(lv, "ClimbWallEnemies2#66");
		assertEquals(504, ((Number) lv.world().slot(e, "SpawnAction")).intValue());
		Level d = level("ATHN01D.WAD");
		assertEquals(-1, ((Number) d.world().slot(entity(d, "CargoRopeEnemies1#150"), "SpawnAction")).intValue());
		EnemyFighter f = skc.spawn(23);
		float[] q = e.position();
		f.pos.set(q[0], q[1], q[2]);
		f.setNavWorld(EnemyNav.of(lv.nav()));
		f.setFoe(target(new Vector3f(f.pos).add(0, -300, 200), 0));
		assertTrue(f.startSpawnAction(504), "루트 액션 A504 (분기 0x148242 = 지상 초기 상태로 Match)");
		assertEquals(504, f.combat().move().id());
		assertEquals("SKSwallSpawn", f.combat().clipName());
		assertTrue(f.invulnerable(), "0–0.83 INVULNERABLE");
		f.update(DT);
		assertEquals(EnemyNav.Mode.WALL, f.nav().mode(), "동작 중 벽을 잡음");
		for (int i = 0; i < 60 && f.combat().active() && f.combat().move().id() == 504; i++) {
			f.update(DT);
			assertEquals(EnemyNav.Mode.WALL, f.nav().mode());
		}
		for (int i = 0; i < 150 && f.combat().active() && f.combat().move().id() == 504; i++) {
			f.update(DT);
		}
		assertFalse(f.combat().active() && f.combat().move().id() == 504, "2 초 뒤 끝");
		assertEquals(EnemyNav.Mode.WALL, f.nav().mode(), "벽 자세로 끝남 → 벽 상태 유지");
	}

	@Test
	void wallHitReactionsComeFromAttackerWallTagsAndKeepTheEnemyOnTheWall() throws Exception {
		EnemyFactory.Kind sks = kind("R_SKS.WAD");
		Level lv = level("ATHN01D.WAD");
		EnemyFighter f = spawn(sks, 21, lv, "CargoRopeEnemies1#150");
		assertEquals(EnemyNav.Mode.WALL, f.nav().mode());
		assertEquals(0x20, f.stateMask());
		// 지상 공격 자세 태그(10) → 벽 상태용 반응 분기 없음 (A101–A123 은 지상 0x2 전용)
		f.combat().fireFrom(CombatInput.EV_HIT, -1, 100, 0, 10);
		f.update(DT);
		assertFalse(f.combat().active(), "지상 태그 → 반응 없음");
		// 영웅 WALLJABRIGHT (자세 태그 71) → A175 WALLHITRIGHT2 (자신 HP ≥ 6)
		assertTrue(f.hp >= 6);
		f.combat().fireFrom(CombatInput.EV_HIT, -1, 100, 0, 71);
		f.update(DT);
		assertTrue(f.combat().active());
		assertEquals(175, f.combat().move().id());
		assertEquals("wallHitRight2", f.combat().clipName());
		for (int i = 0; i < 90; i++) {
			f.update(DT);
			assertEquals(EnemyNav.Mode.WALL, f.nav().mode(), "벽 피격 반응은 벽에 붙은 채 (wallHit* 애니 = 한 손 매달림)");
		}
		// HP ≤ 5: A172 WALLHITLEFT (태그 72) → AUTO [0–0.6] WALLCLIMBATL 반격
		f.hp = 4;
		while (f.combat().active()) {
			f.update(DT);
		}
		f.combat().fireFrom(CombatInput.EV_HIT, -1, 100, 0, 72);
		f.update(DT);
		assertEquals(172, f.combat().move().id());
		assertEquals(EnemyNav.Mode.WALL, f.nav().mode());
	}

	@Test
	void ropeHitReactionOnlyOnTraverseRope() throws Exception {
		EnemyFactory.Kind skc = kind("R_SKC.WAD");
		Level lv = level("ATHN02B.WAD");
		EnemyFighter f = spawn(skc, 23, lv, "RopeEnemies2#90");
		for (int i = 0; i < 180 && f.nav().mode() != EnemyNav.Mode.ROPE; i++) {
			f.update(DT);
		}
		assertEquals(EnemyNav.Mode.ROPE, f.nav().mode());
		assertEquals(EnemyNav.SUB_TRAVERSE, f.nav().rope().sub(), "ATHN02B gorope8 = f1 만 → 0x20000");
		assertEquals(0x80, f.stateMask());
		f.combat().fireFrom(CombatInput.EV_HIT, -1, 100, 0, 10);
		f.update(DT);
		assertEquals(165, f.combat().move().id(), "A165 ROPEHOHR (분기 0x1482C0 = 줄 0x80)");
		for (int i = 0; i < 60; i++) {
			f.update(DT);
			assertEquals(EnemyNav.Mode.ROPE, f.nav().mode());
		}
	}

	@Test
	void netSkeletonsSpreadOverTheNetByEncirclementCells() throws Exception {
		EnemyFactory.Kind sks = kind("R_SKS.WAD");
		Level lv = level("ATHN01D.WAD");
		EnemyFighter a = spawn(sks, 21, lv, "CargoRopeEnemies1#150");
		EnemyFighter b = spawn(sks, 21, lv, "CargoRopeEnemies1#150");
		assertEquals(EnemyNav.Mode.WALL, b.nav().mode());
		Hittable hero = target(new Vector3f(a.pos).add(0, 40, 0), 1);
		for (EnemyFighter f : new EnemyFighter[]{a, b}) {
			f.setFoe(hero);
			f.brain().setSightBlocked((eye, to) -> { // GameScene 과 같은 벽 칸 판정 (그물 너머 칸 제외)
				Vector3f d = new Vector3f(to).sub(eye);
				float len = d.length();
				return len > 1f && lv.nav().wallcast(eye, d.div(len), len - 1f) != null;
			});
		}
		int overlap = 0, frames = 0;
		for (int i = 0; i < 60 * 16; i++) {
			a.update(DT);
			b.update(DT);
			if (i > 60 * 4) {
				frames++;
				if (a.pos.distance(b.pos) < 12f) {
					overlap++;
				}
			}
		}
		// 원작 AI_MoveTo 목표 = 포위 칸 (칸은 대상당 한 적만, 0x1CDBF0) → 벽 평면 투영이 좌우로 갈라진다.
		// 대상 직행(이전 근사)이면 719/719 프레임 겹침, 칸 투영이면 약 절반 (칸이 벽 법선 방향으로만 다르면 같은 점에 투영 — 원작도 같은 투영,
		// 원작의 생물끼리 충돌 밀어내기는 미분석)
		assertTrue(overlap < frames * 2 / 3, "겹친 프레임 " + overlap + "/" + frames);
		assertEquals(EnemyNav.Mode.WALL, a.nav().mode());
		assertEquals(EnemyNav.Mode.WALL, b.nav().mode());
	}
}
