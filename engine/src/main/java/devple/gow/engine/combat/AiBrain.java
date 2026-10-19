package devple.gow.engine.combat;

import devple.gow.tools.combat.CombatFile.Move;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;

/**
 * 원작 병사 AI (Enemy1 의 AI 컴포넌트, 17 문서) 재구현. 매 프레임 {@link #update} 가 이동 의도(방향·속도·바라볼 방향)와
 * 기술 요청(Combatant.request)을 낸다. 실제 이동·애니는 {@link EnemyFighter} 가 적용한다.
 * <ul>
 *   <li>상태: 대기/순찰(대상 미인지) → 전투 (AI_Update 0x1D0E08, ai+0x72 비트 0x4 / 0x2 / 0x10)</li>
 *   <li>인지: 시야 거리 × 16, 시야 반각 (0x1D22B0). 맞으면 즉시 인지</li>
 *   <li>공격 토큰: 대상 슬롯 예산 (0x1CDEC8 / 0x1D30D0) — {@link AiTargets}</li>
 *   <li>포위 이동: 대상 주변 11×11 칸(32 단위) 중 반경(토큰 보유 R_tok×16 / 대기 R_wait×16)에 가까운 빈 칸 (0x1CD788)</li>
 *   <li>공격 선택: 결정 트리 → 행동 노드 → 거리·비용·재사용 대기 조건 가중 선택 (0x1CC980, 0x229258)</li>
 *   <li>방어: BlockMod(영웅 기술 반복 횟수) → 결정 트리 방어 목록 × "Block" % (0x1CBFF8)</li>
 * </ul>
 */
public final class AiBrain {

	/** 포위 칸 크기 / 반 칸 수 (0x1CD834: (idx/11 − 5) × 32). */
	static final float CELL = 32f;
	static final int HALF = 5, GRID = 11;

	public enum State { IDLE, PATROL, COMBAT }

	private final EnemyFighter self;
	public final AiTemplate tmpl;
	public final DecisionTree tree;
	private final List<AiData.BlockEntry> blockMod;
	private final AiData.Soldier sold;
	private final Random rnd;
	private java.util.function.Supplier<Float> recovery = () -> 1f;
	/** 조건 25..27 (스크립트/전역 변수, 17 문서 §3.2) 공급자. 기본 0. 예: 히드라 DTre_250 의 26 = 플레이어 +0x88 (이벤트 1054 구역). */
	private java.util.function.IntUnaryOperator scriptSense = c -> 0;
	/** 시선 가림 판정 (눈 → 대상 사이에 충돌면이 있으면 true, 원작 sub_0011E3E8). null 이면 검사 안 함. */
	private java.util.function.BiPredicate<Vector3f, Vector3f> sightBlocked;
	/** 시선 높이 (발 위, 유닛) [추정]. */
	static final float EYE = 48f;

	private Hittable target;
	private Combatant targetCombat;
	private AiTargets.Slot slot;
	private State state = State.IDLE;
	private boolean hasToken;
	private int cell = -1;
	/** ai+0x76 칸 도착 후 / ai+0x7A 칸 선택 후 경과 ms, ai+0x78 공격 후 대기 ms. */
	private float atCellMs, sinceCellMs, pauseMs;
	private float clockMs;
	private final Map<DecisionTree.Leaf, Float> coolUntil = new HashMap<>();
	private DecisionTree.Attack current;
	private DecisionTree.Leaf lastLeaf;
	private final Vector3f lastTargetPos = new Vector3f();
	private float targetSpeed;
	private float targetMaxHp = 1;
	private List<Vector3f> patrol = List.of();
	private int patrolIdx;

	/** 이번 프레임 이동 의도 (월드, 단위 벡터 × 속도 배율 0..1). */
	public final Vector3f move = new Vector3f();
	/** 이번 프레임 바라볼 yaw (NaN = 유지). */
	public float face = Float.NaN;

	/** 통계·디버그. */
	public int attacksStarted, blocksStarted;
	public final List<Float> attackTimes = new ArrayList<>();
	private Consumer<String> log = s -> {
	};

	public AiBrain(EnemyFighter self, AiTemplate tmpl, DecisionTree tree, List<AiData.BlockEntry> blockMod, AiData.Soldier sold,
				   long seed) {
		this.self = self;
		this.tmpl = tmpl;
		this.tree = tree;
		this.blockMod = blockMod != null ? blockMod : List.of();
		this.sold = sold != null ? sold : AiData.Soldier.HERO;
		this.rnd = new Random(seed);
		// 0x1CEF04: 토큰 사용 적은 "토큰 없음" 상태로 시작, 아니면 항상 보유로 취급
		this.hasToken = !tmpl.usesToken();
	}

	/** 난이도 "AI Recovery Time Mult" (/GlobGame/&lt;난이도&gt;/). 공격 후 대기·재사용 대기에 곱한다. */
	public void setRecoveryScale(java.util.function.Supplier<Float> s) {
		recovery = s;
	}

	public void onLog(Consumer<String> l) {
		log = l;
	}

	/** 조건 25(스크립트 변수)/26(전역 +0x88)/27(전역 +0x64) 값 공급자 (보스 구역 등). */
	public void setSightBlocked(java.util.function.BiPredicate<Vector3f, Vector3f> p) {
		sightBlocked = p;
	}

	public void setScriptSense(java.util.function.IntUnaryOperator s) {
		scriptSense = s != null ? s : c -> 0;
	}

	/** 스크립트가 깨운 보스: 시야 판정 없이 바로 전투 상태로. */
	public void engage() {
		if (target != null) {
			state = State.COMBAT;
		}
	}

	public void setPatrol(List<Vector3f> waypoints) {
		patrol = waypoints != null ? waypoints : List.of();
		patrolIdx = 0;
	}

	/** 공격 대상 지정 (아직 인지 전 — 시야에 들어오거나 맞으면 전투). */
	public void setTarget(Hittable t) {
		if (t == target) {
			return;
		}
		releaseAll();
		if (slot != null) {
			slot.brains.remove(this);
		}
		target = t;
		slot = t != null ? AiTargets.slot(t) : null;
		if (slot != null) {
			slot.brains.add(this);
		}
		state = State.IDLE;
		if (t != null) {
			if (t.combatant() != null) {
				targetCombat = t.combatant();
			}
			lastTargetPos.set(t.position());
			targetMaxHp = Math.max(1, t.hp());
		}
	}

	public Hittable target() {
		return target;
	}

	public State state() {
		return state;
	}

	public boolean hasToken() {
		return hasToken;
	}

	public AiTargets.Slot slot() {
		return slot;
	}

	public DecisionTree.Leaf lastLeaf() {
		return lastLeaf;
	}

	public float pauseMs() {
		return pauseMs;
	}

	/** 멀리 있어 달려야 하는지 (대기 반경 × 1.5 바깥). [근사: 원작 걷기/달리기 선택 0x1CF9D0 미분석] */
	public boolean wantsRun() {
		return target != null && state == State.COMBAT && target.position().distance(self.pos) > tmpl.waitRadius * 16f * 1.5f;
	}

	boolean dead() {
		return self.dead();
	}

	/** 대상(영웅)의 Combatant. 지정하거나 첫 피격 때 알게 되면 조건(방어 중·기술 ID)과 기술 기록에 쓴다. */
	public void observeTargetCombat(Combatant c) {
		if (c != null) {
			targetCombat = c;
		}
	}

	// ------------------------------------------------------------------ 갱신

	public void update(float dt) {
		move.zero();
		face = Float.NaN;
		float ms = dt * 1000f;
		clockMs += ms;
		pauseMs = Math.max(0, pauseMs - ms); // 0x1D0F18
		if (self.dead()) {
			releaseAll();
			return;
		}
		if (target == null) {
			wander();
			return;
		}
		Vector3f tp = target.position();
		targetSpeed = dt > 0 ? tp.distance(lastTargetPos) / dt : 0;
		lastTargetPos.set(tp);
		targetMaxHp = Math.max(targetMaxHp, target.hp());
		if (slot.claimTick(this)) {
			slot.history.observe(targetCombat);
			slot.history.tick((int) ms);
		}
		float dist = dist(tp);
		if (state != State.COMBAT) {
			if (tmpl.mode == 5 || !perceives(tp, dist)) {
				wander();
				return;
			}
			state = State.COMBAT;
			log.accept("인지 거리 " + (int) dist);
			alertNearby();
		}
		if (target.hp() <= 0) {
			releaseAll();
			return;
		}
		Combatant c = self.combat();
		face = yawTo(tp);
		if (c.active()) {
			// 기술 중: 토큰만 갱신 (0x1D1504)
			updateToken(dist);
			return;
		}
		if (current != null) {
			endAttack();
		}
		if (tmpl.groupMode()) {
			surround(dist, ms);
		} else {
			// 모드 1/2: 포위 없이 직접 (0x1D1450)
			tryAttack(dist);
			if (!c.active() && dist > tmpl.tokenRadius * 16f) {
				steer(tp, 1f);
			}
		}
	}

	private void wander() {
		state = patrol.isEmpty() ? State.IDLE : State.PATROL;
		if (!patrol.isEmpty()) {
			Vector3f wp = patrol.get(patrolIdx % patrol.size());
			if (flat(self.pos, wp) < 16f) {
				patrolIdx++;
			} else {
				steer(wp, tmpl.speedPctAlt / 100f);
				face = yawTo(wp);
			}
		}
	}

	/** 인지 (0x1D22B0): 시야 거리 ×16 안 + 정면 기준 반각 sightFov 안 + 시선이 막히지 않음 (템플릿 0x84&amp;0x800 이면 생략). */
	private boolean perceives(Vector3f tp, float dist) {
		if (dist > tmpl.sightRange * 16f || !inSightCone(tp)) {
			return false;
		}
		// 시선 가림 (0x1D257C sub_0011E3E8 충돌 월드 레이 — 원뿔 안일 때만 의미)
		return sightBlocked == null || (tmpl.flags & AiTemplate.FLAG_IGNORE_LOS) != 0
				|| !sightBlocked.test(new Vector3f(self.pos).add(0, EYE, 0), new Vector3f(tp).add(0, EYE, 0));
	}

	/** 경보 (0x1D1AD8): 경보 반경 ×16 안의, 같은 대상을 노리는 아직 인지 못한 동료를 전투로 (메시지 1000) [추정: 수신 처리]. */
	private void alertNearby() {
		if (slot == null) {
			return;
		}
		float r = tmpl.alertRadius * 16f;
		for (AiBrain o : List.copyOf(slot.brains)) {
			if (o != this && o.state != State.COMBAT && !o.dead() && o.tmpl.mode != 5 && o.self.pos.distance(self.pos) <= r) {
				o.state = State.COMBAT;
				o.log.accept("경보 받음");
			}
		}
	}

	private boolean inSightCone(Vector3f tp) {
		if (tmpl.sightFov >= 180f) {
			return true;
		}
		Vector3f d = new Vector3f(tp).sub(self.pos).setComponent(1, 0);
		if (d.lengthSquared() < 1e-6f) {
			return true;
		}
		d.normalize();
		float cos = -(float) Math.sin(self.yaw) * d.x - (float) Math.cos(self.yaw) * d.z;
		return cos >= (float) Math.cos(Math.toRadians(tmpl.sightFov));
	}

	// ------------------------------------------------------------------ 토큰

	/** 0x1CDEC8: 토큰 획득(거리 &lt; R_wait×16×1.1) / 반납(&gt; ×1.5, 자신 경직, 대상 경직). */
	void updateToken(float dist) {
		if (!tmpl.usesToken() || !tmpl.groupMode() || slot == null) {
			return;
		}
		float r = tmpl.waitRadius * 16f;
		boolean selfStagger = guardOf(self.combat()) == 2;
		boolean targetBusy = targetCombat != null && guardOf(targetCombat) == 2;
		if (!hasToken) {
			if (dist < r * 1.1f && !selfStagger && !targetBusy && slot.canTake(sold.cost())) {
				slot.take(sold.cost());
				hasToken = true;
				log.accept("토큰 획득 (예산 " + slot.tokenBudget() + "/" + slot.sold.capacity() + ")");
			}
		} else if (dist > r * 1.5f || selfStagger || targetBusy) {
			giveToken();
		}
	}

	private void giveToken() {
		if (hasToken && tmpl.usesToken() && slot != null) {
			slot.give(sold.cost());
			hasToken = false;
			freeCell();
			log.accept("토큰 반납");
		}
	}

	private void releaseAll() {
		if (current != null) {
			endAttack();
		}
		giveToken();
		freeCell();
		if (tmpl.usesToken()) {
			hasToken = false;
		}
	}

	private static int guardOf(Combatant c) {
		Move m = c.active() ? c.move() : null;
		return m != null ? m.guard() : 0;
	}

	// ------------------------------------------------------------------ 포위 이동 (0x1CE2E0 / 0x1CD788)

	private void surround(float dist, float ms) {
		updateToken(dist);
		Vector3f tp = target.position();
		float box = (HALF + 0.5f) * CELL; // 0x1CE5E8: 칸 격자(11×11×32) 상자 ±176 밖이면 곧장 접근 (0x1CD2A0)
		if (Math.abs(self.pos.x - tp.x) > box || Math.abs(self.pos.z - tp.z) > box) {
			freeCell();
			steer(tp, 1f);
			return;
		}
		float radius = (hasToken ? tmpl.tokenRadius : tmpl.waitRadius) * 16f;
		if (cell < 0 || !cellFree(cell)) {
			pickCell(radius);
		}
		Vector3f cp = cellPos(cell);
		boolean atCell = Math.abs(self.pos.x - cp.x) <= 16f && Math.abs(self.pos.z - cp.z) <= 16f;
		boolean farEnough = dist > tmpl.minRepickDist * 16f; // 0x1D3CC0
		sinceCellMs += ms;
		if (atCell) {
			atCellMs += ms;
		} else {
			atCellMs = 0; // 0x1CDA3C: 칸 밖이면 도착 타이머 0
		}
		boolean canTry = atCellMs > tmpl.dwellMs || sinceCellMs > tmpl.repickTokenMs
				|| (tmpl.anyAttackDist >= 0 && dist <= tmpl.anyAttackDist * 16f);
		if (canTry) {
			if (!hasToken) {
				if (tmpl.waitRadius * 16f * 0.75f <= dist && tryAttack(dist)) {
					// 비용 0 행동(도발 등)만 가능. 원작은 여기서 칸 타이머를 되돌리지 않아 도발이 연달아 나올 수 있는데,
					// 같은 자리에서 멈추지 않도록 칸 선택 후 경과만 0 으로 [근사]
					sinceCellMs = 0;
				}
			} else if (tryAttack(dist)) {
				freeCell();
				return;
			} else if (farEnough && sinceCellMs >= tmpl.repickTokenMs) {
				pickCell(radius);
			}
		}
		if (!hasToken && sinceCellMs >= tmpl.repickWaitMs && farEnough) {
			pickCell(radius);
		}
		if (!self.combat().active()) {
			steer(cellPos(cell), tmpl.speedPct / 100f); // 칸 중심까지 (4 단위 안이면 정지)
		}
	}

	private Vector3f cellPos(int c) {
		Vector3f tp = target.position();
		int i = c / GRID - HALF, j = c % GRID - HALF;
		return new Vector3f(tp.x + i * CELL, self.pos.y, tp.z + j * CELL);
	}

	private boolean cellFree(int c) {
		Object o = slot.cells.get(c);
		return o == null || o == this;
	}

	/**
	 * 칸 선택 (0x20BA60 → 0x20BFB0):
	 * <ol>
	 *   <li>막힌 칸 = 다른 적이 점유한 칸 + 대상 칸 + 벽 칸 (대상에서 시선이 막힌 칸 — 원작 +0x40 근사)</li>
	 *   <li>자기 칸에서 4방향 flood fill → 도달 불가 칸도 제외 (자기 칸이 격자 밖이면 생략)</li>
	 *   <li>대상 중심 ±반경 정사각형 안 균등 무작위 점 → 칸, 제외 칸이 아니면 채택. 최대 5번</li>
	 *   <li>실패하면 121 칸 중 무작위, 막혔으면 대체 (원작 sub_00243B68 — 미상 → 자신에 가장 가까운 빈 칸 [근사])</li>
	 * </ol>
	 */
	private void pickCell(float radius) {
		freeCell();
		int n = GRID * GRID;
		boolean[] blocked = new boolean[n];
		for (int c = 0; c < n; c++) {
			blocked[c] = !cellFree(c);
		}
		blocked[HALF * GRID + HALF] = true;
		// 벽 칸 (원작 격자 +0x40, 0x20B038 이 대상→칸 선분 sub_0020B870 + 충돌 질의 sub_001853E8 로 채움): 대상 눈높이 → 칸 중심 시선이 막히면 막힘 [근사]
		if (sightBlocked != null) {
			Vector3f te = new Vector3f(target.position()).add(0, EYE, 0);
			for (int c = 0; c < n; c++) {
				if (!blocked[c] && sightBlocked.test(te, cellPos(c).setComponent(1, te.y))) {
					blocked[c] = true;
				}
			}
		}
		boolean[] excluded = blocked.clone();
		int start = cellOf(self.pos);
		if (start >= 0) {
			boolean[] reach = new boolean[n];
			java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
			reach[start] = true;
			q.add(start);
			while (!q.isEmpty()) {
				int c = q.poll(), i = c / GRID, j = c % GRID;
				int[][] nb = {{i - 1, j}, {i + 1, j}, {i, j - 1}, {i, j + 1}};
				for (int[] e : nb) {
					if (e[0] < 0 || e[0] >= GRID || e[1] < 0 || e[1] >= GRID) {
						continue;
					}
					int d = e[0] * GRID + e[1];
					if (!reach[d] && !blocked[d]) {
						reach[d] = true;
						q.add(d);
					}
				}
			}
			for (int c = 0; c < n; c++) {
				excluded[c] |= !reach[c];
			}
		}
		Vector3f tp = target.position();
		int chosen = -1;
		for (int tries = 0; tries < 5 && chosen < 0; tries++) {
			Vector3f pnt = new Vector3f(tp.x + (2 * rnd.nextFloat() - 1) * radius, tp.y, tp.z + (2 * rnd.nextFloat() - 1) * radius);
			int c = cellOf(pnt);
			if (c >= 0 && !excluded[c]) {
				chosen = c;
			}
		}
		if (chosen < 0) {
			int c = rnd.nextInt(n);
			if (!blocked[c]) {
				chosen = c;
			} else {
				float best = Float.MAX_VALUE;
				for (int k = 0; k < n; k++) {
					if (!excluded[k]) {
						float d = cellPos(k).distance(self.pos);
						if (d < best) {
							best = d;
							chosen = k;
						}
					}
				}
			}
		}
		cell = chosen < 0 ? HALF * GRID + HALF : chosen;
		slot.cells.put(cell, this);
		atCellMs = 0;
		sinceCellMs = 0;
	}

	/** 0x20AF30: 위치 → 칸 번호 (대상 중심 11×11, 32 단위, 밖이면 −1). */
	private int cellOf(Vector3f p) {
		Vector3f tp = target.position();
		float fx = (p.x - tp.x) / CELL + HALF + 0.5f, fz = (p.z - tp.z) / CELL + HALF + 0.5f;
		if (fx < 0 || fz < 0 || fx >= GRID || fz >= GRID) {
			return -1;
		}
		return (int) fx * GRID + (int) fz;
	}

	private void freeCell() {
		if (cell >= 0 && slot != null && slot.cells.get(cell) == this) {
			slot.cells.remove(cell);
		}
		cell = -1;
	}

	// ------------------------------------------------------------------ 공격 (0x1CC980)

	/** 공격 시도. 성공하면 기술 요청 후 true. */
	boolean tryAttack(float dist) {
		Combatant c = self.combat();
		if (pauseMs > 0 || tree == null || c.active() || self.hp <= 0) { // 0x1CB930
			return false;
		}
		DecisionTree.Leaf leaf = tree.evaluate(this::sense);
		lastLeaf = leaf;
		if (leaf == null) {
			return false;
		}
		int freeA = hasToken || tmpl.mode == 1 ? slot.freeA() : 0;
		int freeB = slot.freeB();
		float rec = recovery.get();
		boolean coolReady = clockMs >= coolUntil.getOrDefault(leaf, 0f);
		DecisionTree.Attack a = DecisionTree.pickAttack(leaf, dist, freeA, freeB, coolReady, rnd);
		if (a == null) {
			return false;
		}
		if (a.cooldownMs() > 0) {
			coolUntil.put(leaf, clockMs + a.cooldownMs() * rec);
		}
		slot.usedA += a.costA();
		slot.usedB += a.costB();
		current = a;
		if (a.move() >= 0) {
			c.request(a.move());
		}
		pauseMs = Math.max(0, a.pauseMs() * rec);
		attacksStarted++;
		attackTimes.add(clockMs / 1000f);
		log.accept("행동 " + leaf.name() + " 기술 " + a.move() + " (거리 " + (int) dist + ")");
		return true;
	}

	/** 0x1D2FB0: 공격 슬롯 반납 + 공격 후 대기 = pause × 난이도 배율. */
	private void endAttack() {
		if (slot != null) {
			slot.usedA = Math.max(0, slot.usedA - current.costA());
			slot.usedB = Math.max(0, slot.usedB - current.costB());
		}
		pauseMs = Math.max(0, current.pauseMs() * recovery.get());
		current = null;
	}

	public DecisionTree.Attack currentAttack() {
		return current;
	}

	/** 디버그 요약: 상태, 토큰, 칸(대상 기준 오프셋), 타이머. */
	public String debugString() {
		String c = cell < 0 ? "-" : (cell / GRID - HALF) + "," + (cell % GRID - HALF);
		return String.format("%s tok=%b cell=%s atCell=%.0f since=%.0f pause=%.0f", state, hasToken, c, atCellMs, sinceCellMs, pauseMs);
	}

	// ------------------------------------------------------------------ 방어 (0x1CBFF8)

	/**
	 * 접촉 직전 방어 판단 (Combat_OnContact 0x204124 → owner 콜백). 방어 기술을 요청했으면 true.
	 *
	 * @param attackerMove 공격자 현재 기술 ID
	 * @param fromTarget   공격자가 이 적의 대상(영웅)인지 (BlockMod 는 영웅 공격에만)
	 */
	public boolean tryBlock(int attackerMove, boolean fromTarget) {
		if (tree == null || target == null || self.combat().active()) {
			return false;
		}
		if (fromTarget && attackerMove >= 0) {
			for (AiData.BlockEntry e : blockMod) {
				if (e.move() != attackerMove) {
					continue;
				}
				int n = slot.history.count(attackerMove);
				if (n >= e.limit()) {
					if (requestBlock("BlockMod 한계")) {
						return true;
					}
				} else if (n > e.min()) {
					float chance = e.minBlock() + (100f - e.minBlock()) * (n - e.min()) / Math.max(1, e.limit() - e.min());
					if (rnd.nextFloat() * 100f <= chance && requestBlock("BlockMod " + (int) chance + "%")) {
						return true;
					}
				}
				break;
			}
		}
		if (rnd.nextFloat() * 100f <= tmpl.block) {
			return requestBlock("Block " + tmpl.block + "%");
		}
		return false;
	}

	private boolean requestBlock(String why) {
		DecisionTree.Leaf leaf = tree.evaluate(this::sense);
		lastLeaf = leaf;
		if (leaf == null) {
			return false;
		}
		DecisionTree.Weighted w = DecisionTree.pick(leaf.blocks(), rnd);
		if (w == null || !self.combat().request(w.move())) {
			return false;
		}
		blocksStarted++;
		log.accept("방어 " + w.move() + " (" + why + ")");
		return true;
	}

	/** 피격 통보: 즉시 전투 상태로. */
	public void onHit() {
		if (target != null && state != State.COMBAT && tmpl.mode != 5) {
			state = State.COMBAT;
		}
	}

	// ------------------------------------------------------------------ 조건 (0x2FFC68 표)

	float sense(int cond, int arg) {
		Vector3f tp = target.position();
		Vector3f d = new Vector3f(tp).sub(self.pos);
		float s = (float) Math.sin(self.yaw), co = (float) Math.cos(self.yaw);
		// 원작 행렬 행: row0 = 로컬 X, row1 = 위, row2 = 로컬 Z (정면 = −row2)
		float dRow0 = d.x * co - d.z * s;
		float dFwd = -d.x * s - d.z * co;
		float len = d.length();
		return switch (cond) {
			case DecisionTree.C_ELEVATION -> len < 1e-4f ? 0 : (float) Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, d.y / len))));
			case DecisionTree.C_YAW -> {
				float hl = (float) Math.hypot(d.x, d.z);
				float a = hl < 1e-4f ? 0 : (float) Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dFwd / Math.max(len, 1e-4f)))));
				yield dRow0 < 0 ? -a : a;
			}
			case DecisionTree.C_DIST -> len / 16f;
			case DecisionTree.C_SELF_STATE -> self.onGround() ? 0 : 2; // 적은 벽/로프 이동 없음 → 지상/공중
			case DecisionTree.C_TARGET_STATE -> target.locomotionState(); // 0 지상 1 벽 2 공중 3 로프
			case DecisionTree.C_IN_FRONT -> dFwd > 0 ? 1 : 0;
			case DecisionTree.C_ON_SIDE -> dRow0 < 0 ? 1 : 0;
			case DecisionTree.C_SELF_HP -> self.hp / Math.max(1, self.maxHp());
			case DecisionTree.C_TARGET_HP -> target.hp() / targetMaxHp;
			case DecisionTree.C_TARGET_GUARD -> targetCombat != null && guardOf(targetCombat) == 1 ? 1 : 0;
			case DecisionTree.C_ABOVE -> d.y > 0 ? 1 : 0;
			case DecisionTree.C_LATERAL -> -dRow0 / 16f;
			case DecisionTree.C_FORWARD -> dFwd / 16f;
			case DecisionTree.C_HEIGHT -> d.y / 16f;
			case DecisionTree.C_DIRECTLY_ABOVE -> d.y > 0 && Math.abs(dRow0 / 16f) < 8 && Math.abs(dFwd / 16f) < 8 ? 1 : 0;
			case DecisionTree.C_TARGET_STILL -> targetSpeed < 0.01f ? 1 : 0;
			case DecisionTree.C_TARGET_IN_MOVE -> targetCombat != null && targetCombat.active() ? 1 : 0;
			case DecisionTree.C_FLAT_DIST -> (float) Math.hypot(d.x, d.z) / 16f;
			case DecisionTree.C_YAW_FROM_TARGET -> yawFromTarget(d);
			case DecisionTree.C_TARGET_MOVE -> targetCombat != null && targetCombat.active() ? targetCombat.move().id() : -1;
			case DecisionTree.C_HAS_TOKEN -> hasToken ? 1 : 0;
			case DecisionTree.C_SCRIPT, 26, 27 -> scriptSense.applyAsInt(cond);
			default -> 0;
		};
	}

	private float yawFromTarget(Vector3f d) {
		if (targetCombat == null || targetCombat.owner() == null) {
			return 0;
		}
		float ty = targetCombat.owner().yaw();
		float s = (float) Math.sin(ty), co = (float) Math.cos(ty);
		float ex = -d.x, ez = -d.z; // 대상 → 자신
		float len = (float) Math.hypot(ex, ez);
		if (len < 1e-4f) {
			return 0;
		}
		float fwd = (-ex * s - ez * co) / len;
		float a = (float) Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, fwd))));
		return (ex * co - ez * s) < 0 ? -a : a;
	}

	// ------------------------------------------------------------------ 보조

	private float dist(Vector3f p) {
		return p.distance(self.pos);
	}

	private static float flat(Vector3f a, Vector3f b) {
		return (float) Math.hypot(a.x - b.x, a.z - b.z);
	}

	private float yawTo(Vector3f p) {
		return (float) Math.atan2(-(p.x - self.pos.x), -(p.z - self.pos.z));
	}

	private void steer(Vector3f p, float speed) {
		float dx = p.x - self.pos.x, dz = p.z - self.pos.z;
		float l = (float) Math.hypot(dx, dz);
		if (l > 4f) {
			move.set(dx / l * speed, 0, dz / l * speed);
		}
	}
}
