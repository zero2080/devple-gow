package devple.gow.engine.combat;

import devple.gow.tools.combat.CombatFile;
import devple.gow.tools.content.Content;
import devple.gow.tools.content.Region;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 무리 전투 (50 문서): 포위 격자 (32 격자 붙기·몸 점유·A*·그림자), 칸 = 반경 원 위, 가까운 토큰 보유자의 연속 공격, 대기 반경 도발,
 * 자동 조준 0x2077A0 (FLAG_1 창까지), 영웅 대상 고르기 0x1D9CB0.
 */
class GroupCombatTest {

	static final float DT = 1 / 60f;
	static final int N = AiTargets.Grid.N, C = AiTargets.Grid.CENTER;

	// ------------------------------------------------------------------ 격자 (ROM 없음)

	/** 0x20B038: 격자 중심 = 대상이 든 32 칸 중심. 칸 안에서 움직이면 그대로, 칸 경계를 넘으면 32 씩. */
	@Test
	void gridSnapsToLatticeAndShiftsByWholeCells() {
		AiTargets.Grid g = new AiTargets.Grid();
		g.update(new Vector3f(100, 0, 40), null);
		assertEquals(112, g.centerX(), 1e-3f, "100 → 칸 [96,128) 중심");
		assertEquals(48, g.centerZ(), 1e-3f);
		Vector3f c5 = g.cellPos(C + 3, new Vector3f());
		g.update(new Vector3f(110, 0, 50), null); // 같은 칸 안
		assertEquals(112, g.centerX(), 1e-3f);
		assertEquals(c5.z, g.cellPos(C + 3, new Vector3f()).z, 1e-3f, "칸이 영웅과 함께 미끄러지지 않는다");
		g.update(new Vector3f(131, 0, 50), null); // x 경계 128 넘음
		assertEquals(144, g.centerX(), 1e-3f, "한 칸 이동");
		g.update(new Vector3f(131, 50, 50), null); // 높이 32 넘게 → 재구성
		assertEquals(50, g.centerY(), 1e-3f);
	}

	/** 0x1E10E8 점유 = 몸이 있는 칸, 0x20C1B0 A* 는 점유 칸을 돌아가고 목표가 점유면 시작 칸 (경로 없음). */
	@Test
	void occupancyIsBodiesAndPathAvoidsThem() {
		AiTargets.Grid g = new AiTargets.Grid();
		g.update(new Vector3f(16, 0, 16), null);
		assertTrue(g.occupied(C), "가운데 = 대상 칸");
		int start = (C / N - 3) * N + C % N; // i−3
		int goal = (C / N + 3) * N + C % N;  // i+3, 같은 열 — 곧장 가면 가운데를 지난다
		int at = start, steps = 0;
		while (at != goal && steps++ < 20) {
			int nx = g.nextStep(at, goal, false);
			assertNotEquals(at, nx, "경로 있음");
			assertEquals(1, Math.abs(nx / N - at / N) + Math.abs(nx % N - at % N), "4방향 이웃");
			assertNotEquals(C, nx, "가운데 (대상 칸) 를 지나지 않음");
			at = nx;
		}
		assertEquals(8, steps, "6 칸 + 돌아가는 2 칸");
		Vector3f body = g.cellPos(goal, new Vector3f());
		g.mark(body, 9.6f);
		assertTrue(g.occupied(goal));
		assertEquals(start, g.nextStep(start, goal, false), "목표 칸에 다른 몸 → 경로 없음");
		g.update(new Vector3f(16, 0, 16), null);
		assertFalse(g.occupied(goal), "점유는 매 프레임 다시 (예약 아님)");
	}

	/** 0x20C8E8: 사분면 벽 칸 → 그 칸에서 격자 모서리까지 그림자, 가운데 줄 벽 → 바깥쪽 나머지. */
	@Test
	void shadowsBehindWallCells() {
		AiTargets.Grid g = new AiTargets.Grid();
		g.update(new Vector3f(16, 0, 16), null);
		g.wall[2 * N + 8] = true;     // i 2 (위), j 8 (오른쪽) 사분면
		g.wall[5 * N + 2] = true;     // 가운데 줄 왼쪽
		g.shadows();
		assertTrue(g.blockedOrShadow(0 * N + 10) && g.blockedOrShadow(2 * N + 10) && g.blockedOrShadow(0 * N + 8));
		assertFalse(g.blockedOrShadow(3 * N + 8), "벽보다 안쪽은 아님");
		assertTrue(g.blockedOrShadow(5 * N) && g.blockedOrShadow(5 * N + 1));
		assertFalse(g.blockedOrShadow(5 * N + 3));
	}

	/** 0x1D9CB0: 스틱 ±60° 안 |d|/c² 최소, 스틱 ≤ 0.8 이면 이전 대상 유지, 스틱 중립 + 이전 없음 → 없음. */
	@Test
	void heroTargetFollowsStickConeAndSticks() {
		Vector3f hero = new Vector3f();
		Vector3f near = new Vector3f(0, 0, -50), right = new Vector3f(100, 0, 0), front = new Vector3f(0, 0, -120);
		List<Vector3f> c = List.of(near, right, front);
		assertSame(right, HeroTargeting.select(null, c, v -> v, hero, 1, 0), "오른쪽으로 → 오른쪽 적");
		assertSame(near, HeroTargeting.select(null, c, v -> v, hero, 0, -1), "앞으로 → 앞의 가까운 적");
		assertNull(HeroTargeting.select(null, c, v -> v, hero, 0, 0), "중립 + 이전 없음");
		assertSame(right, HeroTargeting.select(right, c, v -> v, hero, 0, -0.7f), "0.8 이하 → 유지");
		assertSame(near, HeroTargeting.select(right, c, v -> v, hero, 0, -0.9f), "0.8 넘음 → 다시");
		// 45° 오른쪽 앞: 앞 적 (c = 0.707) 과 오른쪽 적 (0.707) 중 가까운 쪽
		float s = (float) Math.sqrt(0.5);
		assertSame(near, HeroTargeting.select(null, c, v -> v, hero, s, -s));
		// 콘 밖 (c ≤ 0.5)
		assertNull(HeroTargeting.select(null, List.of(right), v -> v, hero, 0, -1));
	}

	// ------------------------------------------------------------------ AI (ROM)

	private static Path rom() {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(Content.file(rom, Region.of(rom).perm())), "gow-rom 없음");
		return rom;
	}

	private static WadFile permk;

	private static EnemyFactory.Kind kind(String wad) throws Exception {
		Path rom = rom();
		if (permk == null) {
			permk = WadFile.load(Content.file(rom, Region.of(rom).perm()));
		}
		return EnemyFactory.load(Content.file(rom, wad), permk, -1);
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

	/**
	 * 0x20BFB0: 새 칸은 격자 중심에서 반경 원 위 — 토큰 보유 0x94 × 16 (64), 없음 0x96 × 16 (160). 해골 6 마리 (토큰 3), 막힘 없는 평지.
	 * 예전 엔진은 ±반경 정사각형 안 균등이라 대기하는 적이 영웅 바로 옆 칸도 골랐다.
	 */
	@Test
	void newCellsLieOnTheTokenAndWaitRings() throws Exception {
		EnemyFactory.Kind sks = kind("R_SKS.WAD");
		Vector3f tp = new Vector3f(16, 0, 16);
		Hittable tgt = dummy(tp);
		AiTargets.slot(tgt, AiData.Soldier.HERO);
		List<EnemyFighter> fs = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			EnemyFighter f = sks.spawn(20);
			double a = i * Math.PI / 3;
			f.pos.set(tp.x + (float) Math.cos(a) * 260, 0, tp.z + (float) Math.sin(a) * 260);
			f.setCrowd(() -> fs);
			f.setFoe(tgt);
			fs.add(f);
		}
		int[] ring = new int[2], all = new int[2]; // [0] 토큰 없음, [1] 토큰
		Map<EnemyFighter, Integer> last = new java.util.HashMap<>();
		for (float t = 0; t < 30; t += DT) {
			for (EnemyFighter f : fs) {
				f.update(DT);
				AiBrain b = f.brain();
				int c = b.cell();
				Integer prev = last.put(f, c);
				if (c >= 0 && (prev == null || prev != c) && b.state() == AiBrain.State.COMBAT) {
					AiTargets.Grid g = b.slot().grid;
					Vector3f cp = g.cellPos(c, new Vector3f());
					float r = (float) Math.hypot(cp.x - g.centerX(), cp.z - g.centerZ());
					int k = b.hasToken() ? 1 : 0;
					float want = b.hasToken() ? 64 : 160;
					all[k]++;
					if (Math.abs(r - want) <= 24) { // 칸 양자화 ±16√2
						ring[k]++;
					}
				}
			}
		}
		assertTrue(all[0] >= 5 && all[1] >= 5, "표본 " + all[0] + "/" + all[1]);
		assertTrue(ring[0] >= all[0] * 0.7, "대기 반경 원 위 " + ring[0] + "/" + all[0]);
		assertTrue(ring[1] >= all[1] * 0.6, "토큰 반경 원 위 " + ring[1] + "/" + all[1]);
	}

	/**
	 * 0x1CDB4C: 토큰 보유자가 가까우면 (거리 ≤ 0x78 × 16 = 64) 공격 성공 뒤에도 칸·타이머를 유지 → 공격 후 대기 (pause × 난이도) 가 끝나면
	 * 같은 자리에서 다시 공격한다. 예전 엔진은 공격마다 칸을 놓고 새 칸으로 걸어가 2 초를 기다렸다.
	 */
	@Test
	void closeTokenHolderKeepsItsCellAndChainsAttacks() throws Exception {
		EnemyFighter f = kind("R_SKS.WAD").spawn(20);
		Vector3f tp = new Vector3f(16, 0, 16);
		f.pos.set(16, 0, -24); // 40 앞
		f.setFoe(dummy(tp));
		List<float[]> at = new ArrayList<>(); // 시작, 끝, 칸
		DecisionTree.Attack cur = null;
		for (float t = 0; t < 20; t += DT) {
			f.update(DT);
			DecisionTree.Attack a = f.brain().currentAttack();
			if (a != cur) {
				if (cur != null && !at.isEmpty()) {
					at.get(at.size() - 1)[1] = t;
				}
				if (a != null && a.costA() > 0) {
					at.add(new float[]{t, -1, f.brain().cell()});
				}
				cur = a;
			}
		}
		assertTrue(at.size() >= 4, "공격 " + at.size());
		int kept = 0;
		for (int i = 1; i < at.size(); i++) {
			float gap = at.get(i)[0] - at.get(i - 1)[1];
			if (at.get(i - 1)[1] > 0 && gap < 2.0f + 0.3f && at.get(i)[2] == at.get(i - 1)[2]) {
				kept++; // pause 2000 ms × Normal 1 뒤 바로, 같은 칸
			}
		}
		assertTrue(kept >= at.size() / 2, "같은 칸에서 pause 뒤 바로 다시 " + kept + "/" + (at.size() - 1));
	}

	/** 0x1CDAF4: 토큰 없는 적은 거리 ≥ 0.75 × 대기 반경이면 시도 조건이 서는 대로 비용 0 행동 (도발) 을 잇는다 (타이머를 되돌리지 않음). */
	@Test
	void waitingEnemyTauntsBackToBack() throws Exception {
		EnemyFighter f = kind("R_SKS.WAD").spawn(20);
		Vector3f tp = new Vector3f(16, 0, 16);
		Hittable tgt = dummy(tp);
		AiTargets.slot(tgt, new AiData.Soldier(4, 0, 10, 10)); // 토큰 용량 0 → 토큰 없음
		f.pos.set(16, 0, 16 - 150);
		f.setFoe(tgt);
		float first = -1, taunting = 0, total = 0;
		for (float t = 0; t < 20; t += DT) {
			f.update(DT);
			boolean taunt = f.combat().active() && f.combat().move().id() <= 2;
			if (taunt && first < 0) {
				first = t;
			}
			if (first >= 0) {
				total += DT;
				taunting += taunt ? DT : 0;
			}
		}
		assertFalse(f.brain().hasToken());
		assertTrue(first >= 0 && f.brain().attacksStarted >= 4, "도발 " + f.brain().attacksStarted);
		assertTrue(taunting >= total * 0.95f, String.format("첫 도발 뒤 %.1f / %.1f 초 도발 중 (끊김 없이 이어짐)", taunting, total));
	}

	/** 0x2077A0: 기술 중 매 프레임 남은 각의 10 % 씩 대상 쪽으로, FLAG_1 창 (해골 SKU3HIT [0.60–1.00]) 부터는 돌지 않는다. */
	@Test
	void enemyAutoAimTracksUntilFlag1() throws Exception {
		EnemyFighter f = kind("R_SKS.WAD").spawn(20);
		Vector3f tp = new Vector3f(16, 0, 16);
		f.pos.set(16, 0, -34); // 50 앞
		f.setFoe(dummy(tp));
		int frames = 0;
		while (!(f.combat().active() && f.combat().move().id() == 11) && frames++ < 60 * 20) {
			f.update(DT);
		}
		assertTrue(f.combat().active() && f.combat().move().id() == 11, "SKU3HIT 시작");
		// 대상을 옆 (오른쪽 90°) 으로 옮긴다
		tp.set(f.pos.x + 50, 0, f.pos.z);
		float before = Math.abs((float) Math.IEEEremainder((float) Math.atan2(-(tp.x - f.pos.x), -(tp.z - f.pos.z)) - f.yaw(), Math.PI * 2));
		assertTrue(before > Math.toRadians(60), "처음엔 옆");
		while (f.combat().active() && f.combat().progress() < 0.55f) {
			f.update(DT);
		}
		float want = (float) Math.atan2(-(tp.x - f.pos.x), -(tp.z - f.pos.z));
		float err = Math.abs((float) Math.IEEEremainder(want - f.yaw(), Math.PI * 2));
		assertTrue(err < Math.toRadians(25), "FLAG_1 전: 대상 쪽으로 돎, 남은 각 " + Math.toDegrees(err));
		while (f.combat().active() && f.combat().progress() < 0.62f) {
			f.update(DT);
		}
		assertTrue(f.combat().eventOn(15), "FLAG_1 창");
		tp.set(f.pos.x - 50, 0, f.pos.z); // 반대편으로
		float yaw0 = f.yaw();
		for (int i = 0; i < 10 && f.combat().active() && f.combat().move().id() == 11; i++) {
			f.update(DT);
		}
		assertEquals(0, Math.IEEEremainder(f.yaw() - yaw0, Math.PI * 2), Math.toRadians(3), "FLAG_1 뒤: 돌지 않음");
	}
}
