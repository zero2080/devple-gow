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
	/** 생물 몸 목록 (영웅 + 모든 적 — 점유 비트 0x1E10E8). null 이면 같은 대상 AI 들 + 대상. */
	private java.util.function.Supplier<? extends Iterable<? extends BodyPush.Body>> crowd;

	public void setCrowd(java.util.function.Supplier<? extends Iterable<? extends BodyPush.Body>> c) {
		crowd = c;
	}

	Vector3f selfPos() {
		return self.pos;
	}

	float selfRadius() {
		return self.bodyRadius();
	}

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
	/** 이번 프레임 이동 목표 점 (3D, 원작 0x1CF9D0 의 목표 — 벽·줄 위에서는 이 점을 벽 평면/줄 방향에 투영). {@link #hasGoal} 이 거짓이면 없음. */
	public final Vector3f goal = new Vector3f();
	public boolean hasGoal;

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

	/** 멀리 있어 달려야 하는지 (대기 반경 × 1.5 바깥). 예전 근사 — 이동은 이제 Sold 속도 × 스틱 ({@link EnemyNav}, 32 문서) 이라 쓰지 않는다. */
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
		hasGoal = false;
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
			slot.refresh(target, self.nav().world(), crowd != null ? crowd.get() : null); // 포위 격자 (대상 갱신 0x20B038 + 몸 점유 0x1E10E8)
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

	/**
	 * 0x1CDEC8: 토큰 획득(거리 &lt; R_wait×16×1.1) / 반납(&gt; ×1.5, 자신 경직, 대상 무적). 대상 쪽은 경직 (guard 2) 이 아니라
	 * 대상 combatant 의 무적 비트 (+0x1AC &amp; 2 = 기술 이벤트 9, 0x2441E0) 또는 전역 정지 0x29DB58 (0x1BF108) — 영웅이 처형
	 * (MAISKB02/05/06, MAIMIN*, 무적) 중이면 주변 적은 토큰을 내려놓고 대기 반경으로 물러나 도발, 붙잡기 (GRAB 200·MAISKB00/01/03,
	 * 무적 아님) 나 일반 피격 (guard 2) 중에는 계속 공격한다.
	 */
	void updateToken(float dist) {
		if (!tmpl.usesToken() || !tmpl.groupMode() || slot == null) {
			return;
		}
		float r = tmpl.waitRadius * 16f;
		boolean selfStagger = guardOf(self.combat()) == 2;
		boolean targetBusy = targetLocked();
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

	/** 대상 무적 (이벤트 9 창, 0x2441E0(+0x1AC, 2)) 또는 전역 정지 0x29DB58 (스크립트 Idle). */
	private boolean targetLocked() {
		return AiTargets.globalHold || targetCombat != null && targetCombat.invulnerable();
	}

	private static int guardOf(Combatant c) {
		Move m = c.active() ? c.move() : null;
		return m != null ? m.guard() : 0;
	}

	// ------------------------------------------------------------------ 포위 이동 (0x1CE2E0 / 0x1CD788 / 0x1CD300)

	/**
	 * AI_CombatMove 0x1CE2E0 (AI Mode 0/3/4): 토큰 갱신 → 반경 = 토큰 보유 0x94 / 없음 0x96 (×16) → 격자 상자 (격자 중심 ±176) 안이면
	 * 칸 판단 0x1CD788 + 칸으로 이동 0x1CD300, 밖이면 곧장 대상에게 (0x1CD2A0).
	 */
	private void surround(float dist, float ms) {
		updateToken(dist);
		if (self.nav().mode() == EnemyNav.Mode.ROPE) {
			// 0x1CE428 → 0x1CDBF0: 줄 위 (+0x174 & 0x400) 는 격자 없이 대상 쪽 (내비 노드) 으로 줄 따라 이동 + 매 프레임 공격 시도 [확실: 흐름]
			steer(target.position(), 1f);
			tryAttack(dist);
			return;
		}
		AiTargets.Grid g = slot.grid;
		if (!g.inBox(self.pos)) {
			steer(target.position(), 1f); // 0x1CD2A0 — 칸·타이머는 건드리지 않는다 (다시 들어오면 같은 칸 번호 = 격자를 따라 옮겨진 자리)
			return;
		}
		float radius = (hasToken ? tmpl.tokenRadius : tmpl.waitRadius) * 16f;
		cellThink(g, dist, radius, ms);
		moveToCell(g, radius);
	}

	/**
	 * 0x1CD788 칸 판단 [확실]:
	 * <ol>
	 *   <li>칸 없음 (0xFF) → 자기 칸에서 고르고 (0x20BA60) 타이머 0, 끝.</li>
	 *   <li>칸 안 (칸 중심 ±16, 칸 높이 ≤ 자기 높이) 이면 도착 시간 (+0x76) 증가. 아니면: 토큰 보유·가까움 (거리 ≤ 0x78×16)·도착 시간 ≠ 0
	 *       (= 칸에 있다가 밀려남) 이면 지금 선 칸을 새 칸으로, 아니면 칸이 벽·그림자일 때만 지금 칸으로. 도착 시간 0.</li>
	 *   <li>시도 = 도착 시간 &gt; 0x20 또는 선택 후 시간 (+0x7A) &gt; 0x22 또는 거리 ≤ 0x74×16.</li>
	 *   <li>토큰 없음: 거리 ≥ 0.75 × 대기 반경이면 공격 시도 (= 비용 0 도발) — 타이머를 되돌리지 않으므로 대기 반경에 선 적은 도발을 잇는다.
	 *       토큰 없는 적은 여기서 칸을 다시 고르지 않는다 (0x24 "토큰 없을 때 재배치 시간" 은 이 경로에서 읽기만 하고 쓰지 않는다).</li>
	 *   <li>토큰 보유: 공격 시도. 이어서 <b>멀 때만</b> (거리 &gt; 0x78×16): 성공이면 칸 해제·타이머 0, 실패이고 선택 후 ≥ 0x22 면 다시 고름.
	 *       가까우면 칸·타이머 유지 → 공격 후 대기 (pause × 난이도) 가 끝나면 바로 다시 공격.</li>
	 * </ol>
	 */
	private void cellThink(AiTargets.Grid g, float dist, float radius, float ms) {
		if (cell < 0) {
			pickCell(g, radius);
			return;
		}
		boolean farEnough = dist > tmpl.minRepickDist * 16f; // 0x1D3CC0
		boolean atCell = inCell(g, cell);
		sinceCellMs += ms;
		if (atCell) {
			atCellMs += ms;
		} else {
			if (hasToken && !farEnough && atCellMs != 0) {
				cell = g.cellOf(self.pos); // 칸에 있다가 벗어남 → 선 자리를 칸으로 (0x1CDA30)
			} else if (blockedOrShadow(g, cell)) {
				cell = g.cellOf(self.pos);
				log.accept("칸 벽/그림자 → 선 칸 " + cell);
			}
			atCellMs = 0;
		}
		boolean canTry = atCellMs > tmpl.dwellMs || sinceCellMs > tmpl.repickTokenMs
				|| (tmpl.anyAttackDist >= 0 && dist <= tmpl.anyAttackDist * 16f);
		if (!canTry) {
			return;
		}
		if (!hasToken) {
			if (tmpl.waitRadius * 16f * 0.75f <= dist) {
				tryAttack(dist);
			}
			return;
		}
		boolean ok = tryAttack(dist);
		if (!farEnough) {
			return;
		}
		if (ok) {
			cell = -1;
			atCellMs = sinceCellMs = 0;
		} else if (sinceCellMs >= tmpl.repickTokenMs) {
			pickCell(g, radius);
		}
	}

	/** 자신이 벽에 붙음 — 벽 칸 판정 (바닥 없음) 을 쓰지 않는다: 원작 벽 칸 = AI 내비 노드 없음인데 그물·벽면 노드 유무를 알 수 없어 [근사]. */
	private boolean onWall() {
		return self.nav().mode() == EnemyNav.Mode.WALL;
	}

	private boolean blockedOrShadow(AiTargets.Grid g, int c) {
		return !onWall() && !wallsOff && g.blockedOrShadow(c);
	}

	/** 칸 안 (0x1CD8B0: 칸 중심 − 자기 의 위 성분 ≤ 0, 가로·세로 ±16). 높이는 1 단위 여유 [근사: 바닥 붙이기 오차]. */
	private boolean inCell(AiTargets.Grid g, int c) {
		Vector3f cp = g.cellPos(c, new Vector3f());
		return cp.y - self.pos.y <= 1f && Math.abs(cp.x - self.pos.x) <= 16f && Math.abs(cp.z - self.pos.z) <= 16f;
	}

	/**
	 * 0x1CD300 칸으로 이동: 칸 없음 → 곧장 대상. 지금 칸이 벽·그림자면 칸 해제 + 곧장. 지금 칸 ≠ 목표 칸이면 A* (0x20C1B0) 다음 칸으로,
	 * 경로가 없으면 (목표 칸에 다른 몸) 다시 고른다. 다음 칸 중심으로 걷는다 (기술 중이면 이동 없음).
	 * [미상: 다음 칸이 AI+0x30 구 밖이고 자기가 그 칸에 없으면 서 있음 — 구의 정체를 몰라 생략]
	 */
	private void moveToCell(AiTargets.Grid g, float radius) {
		if (self.combat().active()) {
			return;
		}
		if (cell < 0) {
			steer(target.position(), 1f);
			return;
		}
		int mine = g.cellOf(self.pos);
		if (blockedOrShadow(g, mine)) {
			cell = -1;
			atCellMs = 0;
			steer(target.position(), 1f);
			return;
		}
		int step = cell;
		if (mine != cell && mine >= 0) {
			step = g.nextStep(mine, cell, onWall() || wallsOff);
			if (step == mine) {
				atCellMs = 0;
				pickCell(g, radius); // 경로 없음 → 다시 고름 (0x1CD3F0), 이번 프레임은 자기 칸 중심으로
			}
		}
		steer(g.cellPos(step, new Vector3f()), tmpl.speedPct / 100f);
	}

	/**
	 * 칸 선택 0x20BA60 → 0x20BFB0 [확실]:
	 * <ol>
	 *   <li>제외 = 점유 (+0x40, 지금 몸이 있는 칸) | 벽 | 그림자 | 자기 칸에서 4방향 flood fill 로 못 가는 칸 (자기 칸이 격자 밖이면 flood 생략)</li>
	 *   <li>최대 5번: 방향 = 정규화 (X (2r−1) + Z (2r−1)), 점 = 격자 중심 + 방향 × 반경 → <b>반경 원 위</b>의 칸 (예전 엔진: ±반경 정사각형 안 균등 → 가까이·멀리 섞임)</li>
	 *   <li>실패하면 121 칸 무작위 최대 5번 (점유·벽만 봄, 0x243B68), 그래도 없으면 칸 없음 (다음 프레임 다시)</li>
	 * </ol>
	 */
	private void pickCell(AiTargets.Grid g, float radius) {
		pickCell0(g, radius);
		if (cell < 0 && !wallsOff && !onWall()) {
			// 엔진의 벽 칸은 근사 (내비 노드 대신 바닥·장애물 질의) — 그것 때문에 고를 칸이 하나도 없으면 원작은 0xFF 로 매 프레임 다시 골라
			// 타이머가 0 에 묶여 공격하지 않는다. 근사가 틀렸을 가능성이 커서 이 적은 다음 성공까지 벽 칸을 무시한다 [근사]
			wallsOff = true;
			pickCell0(g, radius);
		} else if (cell >= 0 && wallsOff && !g.blockedOrShadow(cell)) {
			wallsOff = false;
		}
	}

	/** 벽 칸 근사가 모든 칸을 막아 무시 중 ({@link #pickCell}). */
	private boolean wallsOff;

	private void pickCell0(AiTargets.Grid g, float radius) {
		int n = AiTargets.Grid.CELLS, N = AiTargets.Grid.N;
		boolean[] blocked = new boolean[n];
		for (int c = 0; c < n; c++) {
			blocked[c] = g.occupied(c) || blockedOrShadow(g, c);
		}
		boolean[] excluded = blocked.clone();
		int start = g.cellOf(self.pos);
		if (start >= 0) {
			boolean[] reach = new boolean[n];
			java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
			reach[start] = true;
			q.add(start);
			while (!q.isEmpty()) {
				int c = q.poll(), j = c % N;
				int[] nb = {j < 2 ? -1 : c - 1, j >= N - 1 ? -1 : c + 1, c - N, c + N}; // 0x20BB90: j−1 방향은 j < 2 면 없음 (원작 그대로)
				for (int d : nb) {
					if (d >= 0 && d < n && !reach[d] && !blocked[d]) {
						reach[d] = true;
						q.add(d);
					}
				}
			}
			for (int c = 0; c < n; c++) {
				excluded[c] |= !reach[c];
			}
		}
		int chosen = -1;
		for (int tries = 0; tries < 5 && chosen < 0; tries++) {
			float dx = 2 * rnd.nextFloat() - 1, dz = 2 * rnd.nextFloat() - 1;
			float l = (float) Math.hypot(dx, dz);
			if (l < 1e-6f) {
				continue;
			}
			Vector3f pnt = new Vector3f(g.centerX() + dx / l * radius, g.centerY(), g.centerZ() + dz / l * radius);
			int c = g.cellOf(pnt);
			if (c >= 0 && !excluded[c]) {
				chosen = c;
			}
		}
		for (int tries = 0; tries < 5 && chosen < 0; tries++) {
			int c = rnd.nextInt(n);
			if (!blocked[c]) {
				chosen = c;
			}
		}
		cell = chosen;
		atCellMs = 0;
		sinceCellMs = 0;
	}

	private void freeCell() {
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
		// 대상 무적이면 공격 용량 0 (0x206954 가 대상 슬롯 +0x16 bit0 을 세우고 0x1CCB60 이 용량 A·B 를 0 으로) — 비용 0 행동 (도발) 만
		boolean lock = targetLocked();
		int freeA = !lock && (hasToken || tmpl.mode == 1) ? slot.freeA() : 0;
		int freeB = lock ? 0 : slot.freeB();
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

	public int cell() {
		return cell;
	}

	public DecisionTree.Attack currentAttack() {
		return current;
	}

	/** 디버그 요약: 상태, 토큰, 칸(대상 기준 오프셋), 타이머. */
	public String debugString() {
		String c = cell < 0 ? "-" : (cell / GRID - HALF) + "," + (cell % GRID - HALF);
		if (slot != null && cell >= 0) {
			Vector3f cp = slot.grid.cellPos(cell, new Vector3f());
			c += String.format("@%.0f dy=%.0f", (float) Math.hypot(cp.x - target.position().x, cp.z - target.position().z), cp.y - self.pos.y);
		}
		return String.format("%s tok=%b d=%.0f cell=%s atCell=%.0f since=%.0f pause=%.0f", state, hasToken,
				target != null ? target.position().distance(self.pos) : -1f, c, atCellMs, sinceCellMs, pauseMs);
	}

	// ------------------------------------------------------------------ 방어 (0x1CBFF8)

	/**
	 * 접촉 직전 방어 판단 (Combat_OnContact 0x204124 → owner 콜백). 방어 기술을 요청했으면 true.
	 *
	 * @param attackerMove 공격자 현재 기술 ID
	 * @param fromTarget   공격자가 이 적의 대상(영웅)인지 (BlockMod 는 영웅 공격에만)
	 */
	public boolean tryBlock(int attackerMove, boolean fromTarget) {
		// 원작 0x1CBFF8 은 자기 기술 중인지 보지 않는다 — 막지 못하는 구간은 기술 데이터의 NO_BLOCK (이벤트 12) 창이고 그 검사는 호출 쪽
		// (OnContact 0x2040FC, EnemyFighter.receive). 예전 근사 ("기술 중이면 안 막음") 는 가벼운 피격 반응 중에 막지 않아
		// 미노타우로스·사티로스가 연속기 도중 방어로 끊는 일이 없었다 [확실: 0x1CBFF8 / 데이터]
		if (tree == null || target == null) {
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
			case DecisionTree.C_SELF_STATE -> self.nav().conditionState(); // 자신 owner+0x174: 0 지상 1 벽 2 공중 3 로프 (EnemyNav)
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
		goal.set(p);
		hasGoal = true;
		float dx = p.x - self.pos.x, dz = p.z - self.pos.z;
		float l = (float) Math.hypot(dx, dz);
		if (l > 4f) {
			move.set(dx / l * speed, 0, dz / l * speed);
		}
	}
}
