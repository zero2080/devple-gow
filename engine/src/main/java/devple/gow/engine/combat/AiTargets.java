package devple.gow.engine.combat;

import org.joml.Vector3f;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 공격 대상별 공유 상태 = 원작 "대상 슬롯" 구조체 (엔티티 vtable+0x78, 17 문서 §4). 같은 대상을 노리는 적들이 공유한다.
 * <pre>
 * +0x0C 토큰 예산 사용량   — 토큰을 쥔 적들의 Sold 비용 합. 예산 + 비용 ≤ 대상 Sold +0x246 일 때만 새로 쥘 수 있다 (0x200130)
 * +0x0E / +0x10 공격 슬롯 A/B 사용량 — 결정 트리 공격 항목의 비용 A/B. 용량 = 대상 Sold +0x248/+0x24A (0x1CCB90)
 * +0x16 bit 2 — (적 자신의 슬롯에서) "토큰 없음"
 * </pre>
 * 크라토스(용량 12/10/10)에 대해: 해골(비용 4) 토큰 3명, 공격(비용 A 3) 동시 3회 / 미노타우르스(비용 2) 토큰 6명,
 * 도끼 공격(비용 A 5) 동시 2회.
 * <p>
 * 영웅 기술 사용 기록(0x3044D0, BlockMod 판단용)도 여기에 둔다.
 */
public final class AiTargets {

	/** 전역 정지 0x29DB58 (getter 0x1BF108, 스크립트 Idle(true) → 0x1BF080): 켜져 있으면 모든 적이 토큰을 내려놓고 비용 있는 공격을 하지 않는다 (0x1CDEC8, 0x1CCB60). */
	public static volatile boolean globalHold;

	/**
	 * 대상 주위 포위 격자 (대상 슬롯 +0x04, 생성 0x20AAC8, 갱신 0x20B038 — 대상 자신의 갱신 0x1D0C08 / 영웅 0x1D89F8 이 매 프레임) [확실].
	 * <pre>
	 * +0x00/+0x10/+0x20 축 (지상이면 월드 축), +0x30 중심, +0x40 점유 비트 121, +0x50 벽 비트, +0x60 그림자 비트, +0x70 시각
	 * 칸 번호 = i × 11 + j, 칸 중심 = 중심 + X (i−5)×32 + Z (j−5)×32 (0x20AF30)
	 * </pre>
	 * <ul>
	 *   <li><b>격자는 32 단위 월드 격자에 붙는다</b>: 재구성 때 중심 = trunc(p/32)×32 − 16, 그 뒤 대상이 가운데 칸 (±16) 을 벗어날 때만 칸 단위로
	 *       옮긴다 (0x20B604: 중심 += (i−5, j−5) × 32, 남는 칸의 벽 비트는 복사, 새 칸만 다시 계산). 결과적으로 중심 = 대상이 든 32 칸의 중심 —
	 *       대상이 칸 안에서 움직이는 동안 포위 칸은 그대로다 (예전 엔진: 매 프레임 대상 위치 = 격자 중심 → 칸이 영웅과 함께 미끄러짐).</li>
	 *   <li>재구성: 처음, 대상 높이가 32 넘게 바뀜, 축 기울기 (up·up' &lt; 0.9 — 엔진은 지상만) (0x20B068–0x20B108).</li>
	 *   <li><b>점유 (+0x40) = 지금 몸이 있는 칸</b>: 매 프레임 비우고 (가운데 = 대상 칸만 남김, 0x20B838), 모든 생물의 공용 내비게이션 갱신
	 *       (0x1EBDE8 → 0x1E10E8) 이 활성 격자마다 자기 칸 + 주변 5×5 중 중심이 몸 반경 (vslot 0xC0 × 16) 안인 칸을 세운다 [확실].
	 *       예약이 아니다 — 예전 엔진은 칸을 고른 적이 칸을 예약했다.</li>
	 *   <li>벽 (+0x50, 0x20B870) / 그림자 (+0x60, 0x20C8E8: 벽 칸 뒤쪽 줄·사분면) — 엔진은 대상 눈높이 → 칸 중심 시선이 막히면 막힘 하나로 [근사].</li>
	 * </ul>
	 */
	public static final class Grid {
		public static final float CELL = 32f;
		public static final int HALF = 5, N = 11, CELLS = N * N, CENTER = HALF * N + HALF;
		float cx, cy, cz;
		boolean valid;
		final boolean[] wall = new boolean[CELLS];
		final boolean[] shadow = new boolean[CELLS];
		/** 디버그: 벽 이유 (1 바닥 없음, 2 장애물). */
		final byte[] why = new byte[CELLS];
		final boolean[] occ = new boolean[CELLS];

		public float centerX() {
			return cx;
		}

		public float centerZ() {
			return cz;
		}

		public float centerY() {
			return cy;
		}

		/** 칸 중심 (높이 = 격자 높이). */
		public Vector3f cellPos(int c, Vector3f out) {
			return out.set(cx + (c / N - HALF) * CELL, cy, cz + (c % N - HALF) * CELL);
		}

		/** 0x20AF30: 위치 → 칸 (격자 밖 −1). 모서리 = 중심 − 176 (X+Z), 칸 = trunc(로컬/32). */
		public int cellOf(Vector3f p) {
			float fx = p.x - cx + (HALF + 0.5f) * CELL, fz = p.z - cz + (HALF + 0.5f) * CELL;
			if (fx < 0 || fz < 0 || fx > N * CELL || fz > N * CELL) {
				return -1;
			}
			int i = Math.min(N - 1, (int) (fx / CELL)), j = Math.min(N - 1, (int) (fz / CELL));
			return i * N + j;
		}

		public boolean occupied(int c) {
			return c >= 0 && c < CELLS && occ[c];
		}

		public boolean wall(int c) {
			return c >= 0 && c < CELLS && wall[c];
		}

		/** 디버그 지도: 줄 = i (X), 칸 = j (Z). W 벽, s 그림자, o 점유, . 빈 칸, H 가운데. */
		public String map(java.util.function.IntPredicate chosen) {
			StringBuilder b = new StringBuilder();
			for (int i = 0; i < N; i++) {
				for (int j = 0; j < N; j++) {
					int c = i * N + j;
					char ch = c == CENTER ? 'H' : wall[c] ? (why[c] == 2 ? 'C' : 'W') : shadow[c] ? 's' : '.';
					if (occ[c] && c != CENTER) {
						ch = ch == '.' ? 'o' : Character.toUpperCase(ch) == 'W' ? 'X' : 'O';
					}
					if (chosen.test(c)) {
						ch = ch == 'o' ? '#' : '*';
					}
					b.append(ch);
				}
				b.append('\n');
			}
			return b.toString();
		}

		/** 벽 또는 그림자 (0x1CD9FC·0x1CD384 의 +0x50 | +0x60 검사). */
		public boolean blockedOrShadow(int c) {
			return c >= 0 && c < CELLS && (wall[c] || shadow[c]);
		}

		/** 대상 기준 ±176 상자 안 (0x1CE5E8: 격자 축 성분 ≤ 176 — 격자 중심 기준). */
		public boolean inBox(Vector3f p) {
			float box = (HALF + 0.5f) * CELL;
			return Math.abs(p.x - cx) <= box && Math.abs(p.z - cz) <= box;
		}

		/**
		 * 0x20B038 갱신. tp = 대상 위치, sight = 벽 판정 세계 (null 이면 벽 없음). 중심이 바뀌면 남는 칸 벽은 옮기고 새 칸만 계산, 그림자는 다시.
		 */
		void update(Vector3f tp, EnemyNav.World sight) {
			if (!valid || Math.abs(tp.y - cy) > CELL) {
				// 재구성 0x20B490: 축 = 대상 행렬 (지상 = 월드), 중심 = 32 격자 + 가운데 칸 (0x20B4C0–0x20B55C: trunc(p/32)×32 − 16) → 아래 이동 단계가 대상 칸으로 맞춘다
				valid = true;
				cx = (int) (tp.x / CELL) * CELL - CELL / 2;
				cz = (int) (tp.z / CELL) * CELL - CELL / 2;
				cy = tp.y;
				for (int c = 0; c < CELLS; c++) {
					wall[c] = wallTest(c, sight);
				}
			}
			int at = cellOf(tp);
			if (at >= 0 && at != CENTER && !(Math.abs(tp.x - cx) <= CELL / 2 && Math.abs(tp.z - cz) <= CELL / 2)) {
				// 0x20B604: 칸 단위 이동, 남는 칸 벽 비트 복사
				int di = at / N - HALF, dj = at % N - HALF;
				cx += di * CELL;
				cz += dj * CELL;
				boolean[] old = wall.clone();
				byte[] oldWhy = why.clone();
				for (int i = 0; i < N; i++) {
					for (int j = 0; j < N; j++) {
						int oi = i + di, oj = j + dj;
						boolean keep = oi >= 0 && oi < N && oj >= 0 && oj < N;
						if (keep) {
							why[i * N + j] = oldWhy[oi * N + oj];
						}
						wall[i * N + j] = keep ? old[oi * N + oj] : wallTest(i * N + j, sight);
					}
				}
			} else if (at < 0) {
				valid = false; // 격자 밖으로 순간이동 — 다음 갱신에서 재구성
				update(tp, sight);
				return;
			}
			shadows();
			java.util.Arrays.fill(occ, false);
			occ[CENTER] = true; // 0x20B838: +0x44 = 0x10000000 (60 = 가운데 칸)
		}

		/** 0x1E10E8: 생물 하나의 몸 → 점유 비트 (자기 칸 + 주변 5×5 중 중심이 반경 안인 칸, 3D 거리, 칸 높이 = 격자 높이). */
		void mark(Vector3f p, float radius) {
			int c = cellOf(p);
			if (c < 0) {
				return;
			}
			occ[c] = true;
			int ci = c / N, cj = c % N;
			float r2 = radius * radius;
			Vector3f cp = new Vector3f();
			for (int di = -2; di <= 2; di++) {
				for (int dj = -2; dj <= 2; dj++) {
					int i = ci + di, j = cj + dj;
					if ((di == 0 && dj == 0) || i < 0 || i >= N || j < 0 || j >= N) {
						continue;
					}
					if (cellPos(i * N + j, cp).distanceSquared(p) <= r2) {
						occ[i * N + j] = true;
					}
				}
			}
		}

		/**
		 * 0x20B870 벽 칸: 원작은 AI 내비 노드 목록 (0x337A60, 개수 0x29DC08) 중 칸 중심에 가장 가까운 노드가 가로·세로 노드 반경 (×16),
		 * 높이 64 안에 있으면 걸을 수 있는 칸, 하나도 없으면 벽. 엔진에는 그 노드가 없어 칸 중심 아래 바닥 (격자 높이 +16 ~ −64) 이 없거나
		 * 바닥 위 20 의 반경 8 구가 벽·장애물에 걸리면 벽으로 [근사].
		 */
		private boolean wallTest(int c, EnemyNav.World w) {
			if (w == null || c == CENTER) {
				return false;
			}
			Vector3f p = cellPos(c, new Vector3f());
			float g = w.ground(p.x, p.y + 16f, p.z, 80f);
			if (Float.isNaN(g)) {
				why[c] = 1;
				return true;
			}
			boolean hit = w.collide(new Vector3f(p.x, g + 20f, p.z), 8f);
			why[c] = (byte) (hit ? 2 : 0);
			return hit;
		}

		/**
		 * 0x20C8E8 그림자 칸: 벽 칸마다 대상 (가운데) 에서 바깥쪽으로 가려지는 칸 — 가운데 줄·열이면 그 줄의 바깥쪽 나머지, 사분면이면
		 * 그 칸에서 격자 모서리까지의 직사각형 (벽 칸 자신 포함) [확실].
		 */
		void shadows() {
			java.util.Arrays.fill(shadow, false);
			for (int c = 0; c < CELLS; c++) {
				if (!wall[c] || shadow[c]) {
					continue;
				}
				int i = c / N, j = c % N;
				if (i == HALF) {
					if (j < HALF) {
						fill(i, i, 0, j - 1);
					} else if (j > HALF) {
						fill(i, i, j + 1, N - 1);
					}
				} else if (j == HALF) {
					if (i < HALF) {
						fill(0, i - 1, j, j);
					} else {
						fill(i + 1, N - 1, j, j);
					}
				} else {
					fill(i < HALF ? 0 : i, i < HALF ? i : N - 1, j < HALF ? 0 : j, j < HALF ? j : N - 1);
				}
			}
		}

		private void fill(int i0, int i1, int j0, int j1) {
			for (int i = i0; i <= i1; i++) {
				for (int j = j0; j <= j1; j++) {
					shadow[i * N + j] = true;
				}
			}
		}

		/**
		 * 0x20C1B0: 칸 격자 A* (4방향 0x20AC78 — 점유·벽 칸은 못 감, 비용 32, 추정치 = 칸 중심 직선 거리). 경로의 첫 칸, 없으면 start.
		 * 원작 이웃 함수의 "j−1" 방향은 j &lt; 2 이면 막는다 (j = 0 열은 다른 방향으로만 들어감) — 그대로 둔다.
		 */
		int nextStep(int start, int goal, boolean ignoreWalls) {
			if (start == goal || start < 0 || goal < 0) {
				return start;
			}
			float[] g = new float[CELLS];
			java.util.Arrays.fill(g, Float.MAX_VALUE);
			int[] from = new int[CELLS];
			java.util.Arrays.fill(from, -1);
			boolean[] closed = new boolean[CELLS];
			java.util.PriorityQueue<float[]> open = new java.util.PriorityQueue<>((a, b) -> Float.compare(a[0], b[0]));
			g[start] = 0;
			open.add(new float[]{h(start, goal), start});
			while (!open.isEmpty()) {
				int c = (int) open.poll()[1];
				if (closed[c]) {
					continue;
				}
				if (c == goal) {
					int s = c;
					while (from[s] != start) {
						s = from[s];
					}
					return s;
				}
				closed[c] = true;
				for (int dir = 0; dir < 4; dir++) {
					int n = neighbor(c, dir, ignoreWalls);
					if (n < 0 || closed[n]) {
						continue;
					}
					float ng = g[c] + CELL;
					if (ng < g[n]) {
						g[n] = ng;
						from[n] = c;
						open.add(new float[]{ng + h(n, goal), n});
					}
				}
			}
			return start;
		}

		/** 0x20AC78: 방향 0 = j−1 (j &lt; 2 이면 없음), 1 = j+1, 2 = i−1, 3 = i+1. 점유·벽이면 −1. */
		int neighbor(int c, int dir, boolean ignoreWalls) {
			int j = c % N, n;
			switch (dir) {
				case 0 -> n = j < 2 ? -1 : c - 1;
				case 1 -> n = j >= N - 1 ? -1 : c + 1;
				case 2 -> n = c - N;
				default -> n = c + N;
			}
			if (n < 0 || n >= CELLS || occ[n] || wall[n] && !ignoreWalls) {
				return -1;
			}
			return n;
		}

		private static float h(int a, int b) {
			return (float) Math.hypot((a / N - b / N) * CELL, (a % N - b % N) * CELL);
		}
	}

	/** 대상 하나의 슬롯. */
	public static final class Slot {
		public final AiData.Soldier sold;
		int tokenBudget, usedA, usedB;
		/** 포위 격자 (+0x04). */
		public final Grid grid = new Grid();
		public final MoveHistory history = new MoveHistory();
		/** 기록 갱신 담당 (같은 프레임에 여러 적이 중복 갱신하지 않도록 한 명만). */
		private AiBrain ticker;
		/** 이 대상을 노리는 AI (경보 전파용). */
		final java.util.Set<AiBrain> brains = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

		Slot(AiData.Soldier sold) {
			this.sold = sold;
		}

		/**
		 * 프레임마다 한 번 (기록 갱신 담당이 부름): 격자 이동 (0x20B038) + 점유 다시 표시 (0x1E10E8 — 모든 생물의 몸).
		 * bodies 가 null 이면 이 대상을 노리는 AI 들의 몸 + 대상 (반경 = 영웅 Sold 9.6) 으로 [근사: 시험용].
		 */
		void refresh(Hittable target, EnemyNav.World sight,
					 Iterable<? extends BodyPush.Body> bodies) {
			// 0x1D0C08: 대상이 공중 (+0x174 & 0x4C) 이면 격자를 갱신하지 않는다 — 점프 꼭대기 높이로 재구성되지 않게 [확실]
			// (원작은 그동안 점유 비트도 비우지 않아 쌓인다 — 엔진은 매 프레임 비움 [근사])
			if (target.locomotionState() != 2 || !grid.valid) {
				grid.update(target.position(), sight);
			} else {
				java.util.Arrays.fill(grid.occ, false);
				grid.occ[Grid.CENTER] = true;
			}
			if (bodies != null) {
				for (BodyPush.Body b : bodies) {
					if (b instanceof EnemyFighter ef && ef.dead()) {
						continue;
					}
					grid.mark(b.feet(), b.bodyRadius());
				}
			} else {
				grid.mark(target.position(), 9.6f);
				for (AiBrain b : brains) {
					if (!b.dead()) {
						grid.mark(b.selfPos(), b.selfRadius());
					}
				}
			}
		}

		/** 이 적이 이번 프레임 기록 갱신 담당인지 (담당이 죽었으면 넘겨받음). */
		boolean claimTick(AiBrain b) {
			if (ticker == null || ticker.dead()) {
				ticker = b;
			}
			return ticker == b;
		}

		public int tokenBudget() {
			return tokenBudget;
		}

		public int usedA() {
			return usedA;
		}

		public int usedB() {
			return usedB;
		}

		/** 0x200130: 예산 + 비용 ≤ 용량. */
		boolean canTake(int cost) {
			return tokenBudget + cost <= sold.capacity();
		}

		void take(int cost) {
			tokenBudget += cost;
		}

		void give(int cost) {
			tokenBudget = Math.max(0, tokenBudget - cost);
		}

		int freeA() {
			return Math.max(0, sold.capA() - usedA);
		}

		int freeB() {
			return Math.max(0, sold.capB() - usedB);
		}
	}

	/**
	 * 영웅 기술 사용 기록 (0x3044D0 +0x38 ID[6], +0x44 횟수[6], +0x20 타이머[6]; 0x1DC780 / 0x1DC9A8).
	 * 기술 시작마다 같은 ID 횟수 +1 · 타이머 40000(ms) 갱신, 새 ID 는 타이머가 가장 작은 칸을 덮고 횟수 0 부터.
	 */
	public static final class MoveHistory {
		static final int SLOTS = 6, WINDOW_MS = 40000;
		final int[] ids = {-1, -1, -1, -1, -1, -1};
		final int[] counts = new int[SLOTS];
		final int[] timers = new int[SLOTS];
		private Object lastMove;
		private float lastProgress;

		public void onMoveStart(int id) {
			int slot = -1, oldest = Integer.MAX_VALUE;
			for (int i = 0; i < SLOTS; i++) {
				if (ids[i] == id) {
					slot = i;
					break;
				}
				if (timers[i] < oldest) {
					oldest = timers[i];
					slot = i;
				}
			}
			if (ids[slot] != id) {
				counts[slot] = 0;
			}
			ids[slot] = id;
			timers[slot] = WINDOW_MS;
			counts[slot]++;
		}

		public void tick(int ms) {
			for (int i = 0; i < SLOTS; i++) {
				if (timers[i] > 0 && (timers[i] -= ms) <= 0) {
					timers[i] = 0;
					counts[i] = 0;
					ids[i] = -1;
				}
			}
		}

		public int count(int id) {
			for (int i = 0; i < SLOTS; i++) {
				if (ids[i] == id) {
					return counts[i];
				}
			}
			return 0;
		}

		/**
		 * 영웅 Combatant 관찰로 기술 시작을 감지 (원작은 Combat_StartBranch 0x209740 에서 직접 기록) [근사].
		 * 같은 기술 객체라도 진행률이 되감기면 새 시작으로 본다.
		 */
		public void observe(Combatant c) {
			if (c == null) {
				return;
			}
			Object m = c.active() ? c.move() : null;
			float p = c.active() ? c.progress() : 0;
			if (m != null && (m != lastMove || p + 0.05f < lastProgress)) {
				onMoveStart(c.move().id());
			}
			lastMove = m;
			lastProgress = p;
		}
	}

	private static final Map<Object, Slot> SLOTS = new WeakHashMap<>();
	private static AiData.Soldier heroSold = AiData.Soldier.HERO;

	private AiTargets() {
	}

	/** 영웅 Sold 구조체 지정 (R_PERMK TWK_Sold_000). 이후 새로 만드는 슬롯에 적용. */
	public static synchronized void setHeroSoldier(AiData.Soldier s) {
		if (s != null) {
			heroSold = s;
		}
	}

	/** 대상의 공유 슬롯 (없으면 영웅 Sold 로 생성). */
	public static synchronized Slot slot(Object target) {
		return SLOTS.computeIfAbsent(target, k -> new Slot(heroSold));
	}

	/** 대상 슬롯 강제 지정 (테스트/특수 대상). */
	public static synchronized Slot slot(Object target, AiData.Soldier sold) {
		Slot s = new Slot(sold);
		SLOTS.put(target, s);
		return s;
	}

	/** 모든 대상 슬롯 버리기 (레벨을 떠날 때 — 슬롯 값이 키를 잡아 WeakHashMap 이 풀지 못한다, 48 문서 §4). */
	public static synchronized void clear() {
		SLOTS.clear();
	}
}
