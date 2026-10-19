package devple.gow.engine.nav;

import devple.gow.engine.input.Input;
import devple.gow.engine.input.PadButton;
import devple.gow.engine.level.LevelCollision;
import devple.gow.tools.level.SurfaceAttr;
import org.joml.Vector3f;

/**
 * 영웅 내비게이션 상태 기계 (38 문서 §2): 기어오르기·턱 매달리기·사다리·줄·짚라인·수영·균형 걷기·밀기·상황 동작.
 * 매 틱 {@link #update} 가 먼저 돌고, true 면 이번 틱 이동을 직접 처리했다 (기존 지상/공중 이동·중력은 건너뜀).
 * <pre>
 * 진입 조건 (표면 속성 = 충돌 시트 비트, 38 문서 §1)
 *   공중 + 정면 턱 질의 (0x1E3A80: 손 높이 바닥, NoWallHang·Slide 아님) → LEDGE_HANG (벽보다 먼저)
 *   공중 + 정면 벽 질의 (0x1E47E0) Climbable → WALL_CLIMB        지상 + 벽 쪽 스틱 + ×  → WALL_CLIMB
 *   공중 + 정면 벽 질의 Ladder / 지상 + 벽 쪽 스틱 → LADDER
 *   잡기 점 (발 + 키) 32 안의 줄 (공중, 또는 지상 + ×)  → ROPE (RopeSim) / ROPE_TRAVERSE / ZIPLINE (줄 종류 = SCR_RopeSkinned)
 *   발밑 바닥 Water → SWIM,  Narrow → 균형 걷기 (지상 유지, 애니·속도만),  Death → 사망 통지
 *   지상 + R1 + 가까운 오브젝트 → PUSH (PushPull) / INTERACT (CSM·상자·레버·크랭크·TAndF — 붙음 유지 규칙은 {@link #interact})
 * </pre>
 * 속도·가속·줄 손 높이·잡기 반경·점프·중력은 원작 Sold_000 / GlobGame 값 ({@link NavTuning}, 38 문서 §2.2), 벽·턱 판정 기하는 원작 선분 질의 (§2.4),
 * 밀기 상자 무게·차기는 §3.5, 매단 줄은 질점-스프링 {@link RopeSim} (§2.3).
 */
public final class HeroNavigator {

	/** 게임 쪽 통지 (스크립트 이벤트·보상·사망). */
	public interface Listener {
		/** CSM/상자/레버 진행률 변화 (원작 0x215928 / 0x2118E8 → 이벤트 1023: 인자 0 = %, 인자 1 = 과제 id). */
		default void taskProgress(Interactables.Item it, int percent) {
		}

		/** 같은 통지의 전체 인자 (인자 2 = 재생 속도 × 100). 기본은 {@link #taskProgress(Interactables.Item, int)}. */
		default void taskProgress(Interactables.Item it, TaskProgress.Event e) {
			taskProgress(it, e.percent());
		}

		/** 상자류 붙잡기 (이벤트 1048, 인자 0 = id). */
		default void pushAttach(Interactables.Item it) {
		}

		/** 놓기 (이벤트 1049). */
		default void pushDetach(Interactables.Item it) {
		}

		/** 상자가 delta 만큼 움직임 (Marker·스크립트 액터 갱신). */
		default void pushMoved(Interactables.Item it, Vector3f delta) {
		}

		/** 상자 차기 (원작 0x1C4980: 이벤트 0x41D = 1053, 인자 0). */
		default void boxKicked(Interactables.Item it) {
		}

		/** CSM/상자/레버 완료. */
		default void interactDone(Interactables.Item it) {
		}

		/** Death 면을 밟음. */
		default void deathSurface() {
		}
	}

	/** 원작 수치 (크라토스 TWK_Sold_000, {@link NavTuning} 표). 아래 상수는 전부 이 값 × 16 (1 m = 16 유닛). */
	public static final NavTuning K = NavTuning.KRATOS;
	/** 몸 상자 폭 = 깊이 = 2 × Sold+0x234 0.6 m = 19.2 (+0x120.x = +0x120.z, 0x1E6D58) [확실]. */
	public static final float BODY_W = 2 * K.units(NavTuning.BODY_HALF);
	/** 몸 반폭 9.6 (밀기 상자 간격 = 0x1C4638 붙잡기 관절에서 정면 반 깊이, 수영 구 충돌) [확실: 값]. */
	public static final float BODY = BODY_W / 2;
	/** 키 = 줄 잡기 점·줄 손 높이: Sold+0x238 2.2 m = 35.2 (0x1C5C5C 잡기 점 = 발 + 위축 × +0x120.y, 0x1C66E4 줄 위 몸 = 줄 점 − 같은 값) [확실]. */
	public static final float ROPE_HAND = K.units(NavTuning.BODY_HEIGHT);
	/** 내비 위치 = 발 + Sold+0x23C 0.4 m = 6.4 (+0x130.y: 0x1E47E0 좁은 턱 바닥 맞춤 y = 바닥 + 이 값) [확실: 값 / 추정: 발 구 중심]. 벽 선분 질의 높이. */
	public static final float FOOT = K.units(NavTuning.BODY_BASE);
	/** 손 높이 (내비 위치 기준) +0x140 = 키 − 바닥 = 28.8 (0x1E6D58) [확실]. */
	public static final float REACH = ROPE_HAND - FOOT;
	/** 매달렸을 때 발 → 턱 = FOOT + REACH = 35.2 (0x1E3A80: 내비 위치 y = 턱 − +0x140) [확실: 기하]. 애니 실측 wallHangI 손끝 35.9. */
	public static final float HANG = FOOT + REACH;
	/** 벽과 몸 중심 거리 = 상자 반 깊이 9.6 (0x1E47E0 / 0x1E3A80: 위치 = 두 벽 적중점 중점 + 법선 × 0.5 × +0x120.z) [확실]. */
	public static final float WALL_OFF = BODY_W / 2;
	/** 벽 선분 질의: 정면 0.75 × 상자 깊이 = 14.4, 좌우 ±0.5 m = 8 (0x1E48E4 – 0x1E4928) [확실]. */
	public static final float WALL_PROBE = 0.75f * BODY_W, WALL_SPREAD = 8f;
	/** 턱 탐색: 정면 0.2 m (3.2) + 반 깊이 앞에서 좌우 ±폭/4 수직 선분, 높이 창 = ±max(이번 프레임 이동, 3.2) (0x1E3AD8 – 0x1E3C50) [확실]. */
	public static final float LEDGE_AHEAD = 3.2f, LEDGE_WINDOW = 3.2f;
	/** 턱 바닥 법선 y &gt; 0.85, 벽 두 적중 법선 일치 &gt; 0.9 / 머리 높이 벽 일치 &gt; 0.85, 벽 기울기 |n·위| &lt; 0.5 (0x1E3DB0, 0x1E49C8, 0x1E4BA4, 0x1E4A54) [확실]. */
	static final float LEDGE_NY = 0.85f, WALL_AGREE = 0.9f, WALL_HEAD_AGREE = 0.85f, WALL_TILT = 0.5f;
	/** 줄 잡기 반경 32 (0x1C5CF0 {@code lui 0x4200}) / R1 오브젝트 잡기 반경 32 (0x1C3F18) [확실]. */
	public static final float GRAB = 32f, USE_RADIUS = 32f;
	/** 벽 오르기: 좌우 Sold+0x10C 1.5 → 24, 상하 +0x110 2 → 32, 최소 +0x108 0.5 → 8 (속도 벡터 길이를 [최소, 상하] 로 자름, 0x1C8D08) [확실]. */
	public static final float CLIMB_SIDE = K.units(NavTuning.WALL_SIDE), CLIMB_SPEED = K.units(NavTuning.WALL_UP), CLIMB_MIN = K.units(NavTuning.WALL_MIN);
	/** 사다리 상하 Sold+0x124 2 → 32 (0x1C8C24, 스틱 크기 무관) [확실]. */
	public static final float LADDER_SPEED = K.units(NavTuning.LADDER);
	/** 사다리 미끄럼 (LadderSlide, NoLadderSliding 면 제외): 스틱 아래 &gt; 0.9 (+0x128) 이고 내려가는 중이면 가속 +0x130 → 320, 최대 +0x12C → 160, 놓으면 감속 +0x134 → 640, −32 보다 느려지면 끝 (0x1C8A88) [확실]. */
	public static final float LADDER_SLIDE_STICK = K.get(NavTuning.LADDER_SLIDE_STICK), LADDER_SLIDE_MAX = K.units(NavTuning.LADDER_SLIDE_MAX),
			LADDER_SLIDE_ACC = K.units(NavTuning.LADDER_SLIDE_ACC), LADDER_SLIDE_DEC = K.units(NavTuning.LADDER_SLIDE_DEC);
	/** 매단 줄: 오르내리기 = 스틱 × Sold+0x138 1.5 → 24, 최소 +0x13C → 8 (0x1C6578 → 0x1E6080) [확실]. */
	public static final float ROPE_SPEED = K.units(NavTuning.ROPE_CLIMB), ROPE_MIN = K.units(NavTuning.ROPE_CLIMB_MIN);
	/** 줄 미끄럼 (RopeSlide): 스틱 아래 &gt; 0.8 → 가속 +0x150 → 320, 최대 +0x14C → 120, 놓으면 감속 +0x154 → 320 (0x1C6448) [확실]. */
	public static final float ROPE_SLIDE_MAX = K.units(NavTuning.ROPE_SLIDE_MAX), ROPE_SLIDE_ACC = K.units(NavTuning.ROPE_SLIDE_ACC),
			ROPE_SLIDE_DEC = K.units(NavTuning.ROPE_SLIDE_DEC);
	/** 줄 위 매단 점에서 최소 거리 0.25 m (0x1C6608) [확실]. */
	public static final float ROPE_TOP_GAP = 0.25f * NavTuning.UNITS_PER_METER;
	/** 턱 매달리기 좌우 Sold+0x104 1 → 16 (0x1C8A24) [확실]. */
	public static final float SHIMMY_SPEED = K.units(NavTuning.HANG_SIDE);
	/** 수평 줄: 스틱 × Sold+0x158 2.5 → 40, 최소 +0x15C 1.25 → 20 (0x1C6370) [확실]. */
	public static final float TRAVERSE_SPEED = K.units(NavTuning.TRAVERSE), TRAVERSE_MIN = K.units(NavTuning.TRAVERSE_MIN);
	/** 수영 (ANISwim 상태 0x100): 목표 Sold+0x1D4 7.5 → 120 × 스틱, 최소 +0x1DC → 16, 가속/감속 +0x1E0/+0x1E4 → 120, 회전 프레임당 +0x1E8 0.15 (0x1CA448) [확실]. */
	public static final float SWIM_SPEED = K.units(NavTuning.SWIM), SWIM_MIN = K.units(NavTuning.SWIM_MIN), SWIM_ACCEL = K.units(NavTuning.SWIM_ACCEL),
			SWIM_DECEL = K.units(NavTuning.SWIM_DECEL), SWIM_TURN = K.get(NavTuning.SWIM_TURN);
	/** 수면 → 발 깊이 = 키 × (1 − Sold+0x1EC 0.2) = 28.16 (0x1C9C00 물 판정 높이) [추정: 판정 높이 = 떠 있는 수면 높이]. */
	public static final float SWIM_DEPTH = ROPE_HAND * (1 - K.get(NavTuning.WATER_LINE));
	/** 상자 마찰 감속 = 계수 1 × 9.82 × 16 (0x211A8C → 0x1E0910: |v| &gt; 1.6 이면 v ×= 1 − μ·g·dt/|v|, 아니면 0) [확실]. */
	public static final float BOX_FRICTION = 9.82f * NavTuning.UNITS_PER_METER, BOX_REST = 1.6f;
	/** 상자 차기 충전 시간 Sold+0x1AC 2 초 (0x1C49A8: 속도 = lerp(+0x194[무게], +0x1A0[무게], 충전/2)) [확실]. */
	public static final float KICK_CHARGE = K.get(NavTuning.KICK_CHARGE);
	/** 상자 차기 이벤트 0x41D (0x1C4980 → sub_001A8C78, 인자 0) [확실]. */
	public static final int EVENT_KICK = 0x41D;
	/** 좁은 길 지상 속도 배율 Sold+0x230 = 1 (0x1EAFB0) [확실: 값 / 추정: +0x2D8 비트 4 = 좁은 길]. */
	public static final float BALANCE_SPEED = K.get(NavTuning.NARROW_SCALE);
	/** 짚라인: 목표 Sold+0x160 20 → 320 으로 가속 +0x164 10 → 160 (0x1C6134, 기울기 무관), 끝 0.25 m 앞에서 속도 0 으로 놓음 (0x1C6B1C) [확실: 속도 / 추정: 끝 처리]. */
	public static final float ZIP_SPEED = K.units(NavTuning.ZIP_SPEED), ZIP_ACCEL = K.units(NavTuning.ZIP_ACCEL);
	/** 중력 /GlobGame/ "Gravity" 50 → 800 (0x1EBC0C), 최대 낙하 Sold+0xDC 50 → 800 (0x1EBC4C) [확실]. */
	public static final float GRAVITY = NavTuning.GLOBAL_GRAVITY * NavTuning.UNITS_PER_METER, FALL_MAX = K.units(NavTuning.FALL_MAX);
	/**
	 * 붙은 오브젝트를 밀 때 영웅 속도 (0x1C44E0 – 0x1C4610): 무게 등급 w = Push 템플릿 +0x1A "Weight" (오브젝트 vtable +0xE0 = 0x244FB0) 로
	 * 최대 Sold+0x188[w] (1.9 / 1.5 / 1.2 m/s), 최소 +0x17C[w] (0.5) — 스틱 × 최대, 0 이 아니면 최소 이상 [확실].
	 */
	public static float pushSpeedMax(int weight) {
		return K.units(NavTuning.PUSH_MAX + 4 * clampWeight(weight));
	}

	public static float pushSpeedMin(int weight) {
		return K.units(NavTuning.PUSH_MIN + 4 * clampWeight(weight));
	}

	/** 상자 차기 속도 (0x1C49E8): lerp(Sold+0x194[w], Sold+0x1A0[w], clamp(충전 / +0x1AC, 0, 1)) × 16 [확실]. */
	public static float kickSpeed(int weight, float charge) {
		int w = clampWeight(weight);
		float lo = K.get(NavTuning.KICK_MIN + 4 * w), hi = K.get(NavTuning.KICK_MAX + 4 * w);
		float k = Math.max(0, Math.min(1, charge / KICK_CHARGE));
		return (lo + (hi - lo) * k) * NavTuning.UNITS_PER_METER;
	}

	private static int clampWeight(int w) {
		return Math.max(0, Math.min(2, w));
	}

	/**
	 * 매단 줄 흔들기 힘 = Sold+0x148 500 × 붙은 거리 (m) (0x1C6BE8: f21 = +0x148 × 영웅 +0x294) 를 0.25 초 창 (0x29DBF4) 동안
	 * 2 × 남은 시간 배로 매달림 힘 (0x218470) 에 준다 [확실: 크기·창 / 추정: 방향 = 스틱 수평 방향, 창은 스틱을 새로 밀 때 다시 열림].
	 */
	public static final float SWING_FORCE = K.get(NavTuning.ROPE_SWING_FORCE), SWING_WINDOW = 0.25f;
	/** 점프 수직 초속 Sold+0xC4 15.25 → 244 (0x1E9E88, 정점 = 244² / 1600 ≈ 37 유닛) [확실]. */
	public static final float JUMP = K.units(NavTuning.JUMP);
	/** 2단 점프: 수직 속도 = +0xC8 → 244, 수직 속도가 (+0xD0 −1600, +0xCC 320) 안일 때만 (0x1EA130) [확실]. */
	public static final float DOUBLE_JUMP = K.units(NavTuning.DOUBLE_JUMP), DOUBLE_MAX_VY = K.units(NavTuning.DOUBLE_MAX_VY),
			DOUBLE_MIN_VY = K.units(NavTuning.DOUBLE_MIN_VY);
	/** 공중 수평: 목표 Sold+0xEC 7.5 → 120 × 스틱, 가속 +0xD8 → 120 /s², 회전 프레임당 +0xE4 0.05 (0x1EB650) [확실: 값 / 추정: 회전]. */
	public static final float AIR_SPEED = K.units(NavTuning.AIR_SPEED), AIR_ACCEL = K.units(NavTuning.AIR_ACCEL), AIR_TURN = K.get(NavTuning.AIR_TURN);
	/** 벽에서 등 뒤 점프: 벽 반대 Sold+0x11C 10 → 160, 위 +0x118 17 → 272 (0x1E9D9C) [확실]. */
	public static final float BACK_JUMP_OUT = K.units(NavTuning.WALL_AWAY), BACK_JUMP_UP = K.units(NavTuning.BACK_JUMP_UP);

	private final NavBody body;
	private final NavWorld world;
	private final NavAnims anims;
	private Interactables items = new Interactables();
	private Listener listener = new Listener() {
	};
	private NavState state = NavState.GROUND;
	private boolean balancing;
	private float stateTime, cooldown;
	private final Vector3f wallN = new Vector3f();
	private float ledgeY;
	private NavRope rope;
	private float ropeT, zipSpeed, zipDir;
	/** 매단 줄 시뮬레이션 (줄마다 하나, 매 틱 진행) 과 흔들기 힘 창. */
	private final java.util.Map<NavRope, RopeSim> sims = new java.util.IdentityHashMap<>();
	private float pumpTimer;
	private boolean pumpHeld;
	private final Vector3f pumpDir = new Vector3f();
	private float waterY;
	/** 사다리 수직 속도·미끄럼 중 (0x1C8A88), 줄 속도 (+ = 아래)·미끄럼 중 (0x1C6448), 수영 속도 (0x1CA868). */
	private float ladderVel, ropeVel, swimSpeed;
	private boolean ladderSlide, ropeSlide;
	/** 붙은 벽 면의 NoBackJumps / NoLadderSliding (bitfield1 7 / 6). */
	private boolean noBackJumps, noLadderSliding;
	private Interactables.Item item;
	/** 영웅이 놓은 뒤 되감기는 중인 CSM/레버 (원작: 오브젝트가 스스로 −4 로 되감음). */
	private final java.util.List<Interactables.Item> released = new java.util.ArrayList<>();
	private final Vector3f pushAxis = new Vector3f();
	/** 상황 동작 영웅 애니 단계 ({@link #PH_GRAB} → {@link #PH_MAIN} → {@link #PH_FINISH}) 와 그 단계 시간, TAndF 마지막 누름 뒤 시간. */
	private int phase;
	private float phaseTime, sincePress;
	private boolean doneNotified;
	/** 매달리기 타이머 (+0x2A4) 와 도는 중 (+0x2D8 0x400). */
	private float hangTimer = Float.MAX_VALUE;
	private boolean hangTiming;
	/** 지난 틱 발 위치 (턱 판정의 이번 프레임 이동·하강 판정 = 원작 0x1E3A80 a2 이전 위치). */
	private final Vector3f lastFeet = new Vector3f();
	private Vector3f prevFeet;
	private boolean hasLast;
	/** 상자 차기 충전 (+0x2B8) / 충전 중 (+0x2D8 0x20000), 밀기 애니 단계 시간. */
	private float kickCharge, kickAnimTime = -1;
	private boolean charging;
	/** 놓였거나 차여서 미끄러지는 상자 (원작 상자 물리 0x211A10 이 스스로 돈다). */
	private final java.util.List<Interactables.Item> sliding = new java.util.ArrayList<>();

	public HeroNavigator(NavBody body, NavWorld world, NavAnims anims) {
		this.body = body;
		this.world = world;
		this.anims = anims != null ? anims : new NavAnims();
	}

	public void setListener(Listener l) {
		listener = l != null ? l : new Listener() {
		};
	}

	public void setItems(Interactables it) {
		items = it != null ? it : new Interactables();
	}

	public Interactables items() {
		return items;
	}

	public NavWorld world() {
		return world;
	}

	public NavState state() {
		return state;
	}

	/** 내비게이터가 위치를 직접 정하는 상태인지 (지상/공중이 아님). */
	public boolean active() {
		return state != NavState.GROUND && state != NavState.AIR;
	}

	public boolean balancing() {
		return balancing;
	}

	public NavRope rope() {
		return rope;
	}

	public Interactables.Item item() {
		return item;
	}

	/** 강제로 지상 상태로 (리스폰·Goto). */
	public void reset() {
		if (item != null && (state == NavState.PUSH || state == NavState.INTERACT)) {
			listener.pushDetach(item);
			if (state == NavState.INTERACT && !item.done) {
				released.add(item);
			}
		}
		releaseRope();
		state = body.grounded() ? NavState.GROUND : NavState.AIR;
		rope = null;
		item = null;
		charging = false;
		hasLast = false;
		cooldown = 0.3f;
	}

	// ---- 매 틱 ----

	/** @return true = 이번 틱 이동을 처리함 (기본 이동·중력 생략) */
	public boolean update(Input in, float camYaw, float dt) {
		stateTime += dt;
		cooldown -= dt;
		prevFeet = hasLast ? new Vector3f(lastFeet) : null;
		lastFeet.set(body.position());
		hasLast = true;
		if (state == NavState.WALL_CLIMB || state == NavState.LADDER || state == NavState.SWIM || body.grounded() && !active()) {
			resetHangTimer();
		}
		rewindReleased(dt);
		slideBoxes(dt);
		for (RopeSim sim : sims.values()) {
			sim.update(dt);
		}
		switch (state) {
			case WALL_CLIMB, LADDER -> {
				climb(in, dt);
				return true;
			}
			case LEDGE_HANG -> {
				hang(in, dt);
				return true;
			}
			case ROPE -> {
				hangRope(in, camYaw, dt);
				return true;
			}
			case ROPE_TRAVERSE -> {
				traverse(in, camYaw, dt);
				return true;
			}
			case ZIPLINE -> {
				zip(in, dt);
				return true;
			}
			case SWIM -> {
				swim(in, camYaw, dt);
				return true;
			}
			case PUSH -> {
				push(in, camYaw, dt);
				return true;
			}
			case INTERACT -> {
				interact(in, camYaw, dt);
				return true;
			}
			default -> {
			}
		}
		state = body.grounded() ? NavState.GROUND : NavState.AIR;
		Vector3f p = body.position();
		Vector3f fwd = forward(body.yaw());
		Vector3f stick = stickDir(in, camYaw);
		float mag = stickMag(in);
		if (body.grounded()) {
			NavWorld.Floor f = world.floor(p.x, p.y + 4, p.z, 8);
			long a = f != null ? f.attrs() : 0;
			boolean bal = (a & SurfaceAttr.NARROW.bit()) != 0;
			if (bal != balancing) {
				balancing = bal;
				body.locomotion(bal ? anims.get("ANIBalanceWalk") : null, bal ? anims.get("ANIBalanceWalk") : null, bal ? BALANCE_SPEED : 1f);
			}
			if ((a & SurfaceAttr.DEATH.bit()) != 0) {
				listener.deathSurface();
			}
			if ((a & SurfaceAttr.WATER.bit()) != 0 && deepWater(p, f.y())) {
				enterSwim(f.y());
				return true;
			}
			if (in.pressed(PadButton.USE)) {
				Interactables.Item it = items.nearest(p, USE_RADIUS);
				// 놓은 뒤 되감기는 중인 오브젝트는 0 으로 돌아갈 때까지 잡히지 않는다 (원작: 잡기 클래스 비트 0x400000 / 0x30000 을 끄고 0 에서 다시 켬,
				// CSM 0x2160B4 → 0x216254, 레버 0x21287C → 0x21290C) [확실]
				if (it != null && !released.contains(it)) {
					startItem(it);
					return true;
				}
			}
		}
		if (cooldown > 0) {
			return false;
		}
		// 줄 잡기: 원작 0x1C5C20 = 공중일 때 잡기 점 (발 + 키 35.2) 반경 32 안의 줄 [확실]. 지상 + × 는 "점프해서 잡기" 의 엔진 단축 [근사]
		NavRope r = world.nearestRope(new Vector3f(p).add(0, ROPE_HAND, 0), GRAB);
		if (r != null && (!body.grounded() || in.pressed(PadButton.CROSS))) {
			grabRope(r, new Vector3f(p).add(0, ROPE_HAND, 0));
			return true;
		}
		// 턱: 공중 (+0x174 0x4C) 이고 매달리기 타이머 +0x2A4 &gt; 0 일 때 0x1E3A80 — 원작은 벽 오르기 판정보다 먼저 (0x1C7C80) [확실]
		if (!body.grounded() && hangTimer > 0) {
			Ledge l = probeLedge(p, prevFeet, fwd, null);
			if (l != null) {
				enterHang(l);
				return true;
			}
		}
		// 벽: 정면 (공중, 0x1C7D40) 또는 스틱 방향 (지상 [근사: 원작 지상 진입 0x1C7E1C 조건 +0x2D8 0x80 / 국소 속도 미해석])
		Vector3f dir = body.grounded() && mag > 0.5f ? stick : fwd;
		WallProbe w = probeWall(p, dir, null);
		if (w != null) {
			boolean into = w.n().dot(dir) < -0.6f;
			if (into && w.tri().has(SurfaceAttr.LADDER) && (!body.grounded() || mag > 0.5f)) {
				enterClimb(w, true);
				return true;
			}
			if (into && w.tri().has(SurfaceAttr.CLIMBABLE) && (!body.grounded() || in.pressed(PadButton.CROSS))) {
				enterClimb(w, false);
				return true;
			}
		}
		return false;
	}

	// ---- 벽·턱 판정 (원작 0x1E47E0 / 0x1E3A80) ----

	/** 벽 선분 질의 결과: 붙을 발 위치, 수평 법선 (영웅 쪽), 첫 적중 면 (+0x238 표면 → 사다리/벽). */
	record WallProbe(Vector3f feet, Vector3f n, LevelCollision.Tri tri) {
	}

	/** 턱 판정 결과: 턱 높이·턱 점, 매달릴 발 위치, 벽 수평 법선, 턱 면 (+0x234 → WallHangTimer). */
	record Ledge(float y, Vector3f point, Vector3f feet, Vector3f n, LevelCollision.Tri top) {
	}

	/** 붙는 벽 마스크: 원작 bitfield0 & 0x1008001C (Narrow·Climbable·Ladder·BackPress·NarrowNoBB) 중 엔진이 쓰는 Climbable·Ladder. */
	private static boolean climbSurface(LevelCollision.Tri t) {
		return t.has(SurfaceAttr.CLIMBABLE) || t.has(SurfaceAttr.LADDER);
	}

	/** 면 법선을 선분 시작점 쪽으로 (엔진 충돌은 양면). */
	private static Vector3f facing(LevelCollision.Hit h, Vector3f from) {
		Vector3f n = new Vector3f(h.tri().n());
		if (n.dot(from.x - h.point().x, from.y - h.point().y, from.z - h.point().z) < 0) {
			n.negate();
		}
		return n;
	}

	/** 오른쪽 = 정면 × 위 (수평). */
	private static Vector3f rightOf(Vector3f fwd) {
		return new Vector3f(fwd.x, 0, fwd.z).cross(0, 1, 0).normalize();
	}

	/**
	 * 원작 0x1E47E0 (벽 오르기·사다리 판정, a3 = 0 경로) [확실: 디스어셈블].
	 * <pre>
	 * o = 내비 위치 (발 + 6.4). o → o ∓ 오른쪽 × 8 + 정면 × 14.4 두 선분 (0x1E46B0) 이 모두 붙는 면 (마스크) 에 맞고,
	 * 두 법선 내적 &gt; 0.9, 벽 기울기 |n·위| &lt; 0.5 (붙어 있으면 저장된 법선 +0x220 을 씀).
	 * 머리: u = 벽 면 위 방향, s = o + u × (+0x140 − 1) 에서 −n × 14.4 선분이 붙는 면에 맞고 법선 내적 &gt; 0.85 (0x20).
	 * 위치 = 두 적중점 중점 + (세 적중점 평면 법선) × 9.6.
	 * </pre>
	 *
	 * @param feet  후보 발 위치
	 * @param fwd   정면 (벽 쪽)
	 * @param keepN 붙어 있는 동안의 벽 법선 (null = 두 적중 법선 평균)
	 */
	WallProbe probeWall(Vector3f feet, Vector3f fwd, Vector3f keepN) {
		Vector3f f = new Vector3f(fwd.x, 0, fwd.z).normalize();
		Vector3f o = new Vector3f(feet).add(0, FOOT, 0);
		Vector3f right = rightOf(f);
		LevelCollision.Hit h1 = world.segment(o, new Vector3f(o).fma(-WALL_SPREAD, right).fma(WALL_PROBE, f));
		LevelCollision.Hit h2 = world.segment(o, new Vector3f(o).fma(WALL_SPREAD, right).fma(WALL_PROBE, f));
		if (h1 == null || h2 == null || !climbSurface(h1.tri()) || !climbSurface(h2.tri())) {
			return null;
		}
		Vector3f n1 = facing(h1, o), n2 = facing(h2, o);
		if (n1.dot(n2) <= WALL_AGREE) {
			return null;
		}
		Vector3f n = keepN != null ? new Vector3f(keepN) : new Vector3f(n1).add(n2).normalize();
		if (Math.abs(n.y) >= WALL_TILT) {
			return null;
		}
		Vector3f up = new Vector3f(0, 1, 0).fma(-n.y, n).normalize();
		Vector3f s = new Vector3f(o).fma(REACH - 1, up);
		LevelCollision.Hit h3 = world.segment(s, new Vector3f(s).fma(-WALL_PROBE, n));
		if (h3 == null || !climbSurface(h3.tri()) || facing(h3, s).dot(n) <= WALL_HEAD_AGREE) {
			return null;
		}
		Vector3f np = plane(h1.point(), h2.point(), h3.point(), o, n);
		Vector3f pos = new Vector3f(h1.point()).add(h2.point()).mul(0.5f).fma(WALL_OFF, np);
		Vector3f nh = new Vector3f(np.x, 0, np.z);
		if (nh.lengthSquared() < 1e-6f) {
			return null;
		}
		return new WallProbe(pos.sub(0, FOOT, 0), nh.normalize(), h1.tri());
	}

	/** 세 점 평면의 법선 (from 쪽), 퇴화하면 def. */
	private static Vector3f plane(Vector3f a, Vector3f b, Vector3f c, Vector3f from, Vector3f def) {
		Vector3f n = new Vector3f(a).sub(c).cross(new Vector3f(b).sub(c));
		if (n.lengthSquared() < 1e-6f) {
			return new Vector3f(def);
		}
		n.normalize();
		if (n.dot(from.x - a.x, from.y - a.y, from.z - a.z) < 0) {
			n.negate();
		}
		return n;
	}

	/**
	 * 원작 0x1E3A80 (턱 매달리기 판정, 일반 턱 경로) [확실: 디스어셈블].
	 * <pre>
	 * d = max(이번 프레임 이동, 3.2). 정면 3.2 + 9.6 앞, 좌우 ±폭/4 (4.8) 에서 손 높이 (o + 28.8) ± d 수직 선분 둘이 모두 바닥
	 *   (법선 y &gt; 0.85, Slide·NoWallHang 아님) 에 맞음 → 턱 = 두 점 중점.
	 * 내려가는 중이면 o.y ≤ 턱 − 28.8 (손이 턱을 지나야). 머리 (o + 28.8 + d) → 턱 + 1 선분이 막히지 않음.
	 * 벽: b = 턱 − 위 × (28.8 + d) − 정면 × 9.6 에서 정면 × 19.2 ± 오른쪽 × 9.6 두 선분 (없으면 b + 위 × 26.8 에서 다시) 이 맞고,
	 *   b + u × 27.8 에서 정면 × 19.2 선분이 맞음. 위치 = 두 벽 적중 중점 + 세 점 평면 수평 법선 × 9.6, 발 = 턱 − 35.2.
	 * 좁은 턱 (Narrow·NarrowNoBB, 0x1E3EE8) 경로와 한쪽만 맞는 모서리 경로 (반환 8, 0x1E4528) 는 [미구현].
	 * </pre>
	 */
	Ledge probeLedge(Vector3f feet, Vector3f prev, Vector3f fwd, Vector3f keepN) {
		Vector3f f = new Vector3f(fwd.x, 0, fwd.z).normalize();
		Vector3f right = rightOf(f);
		Vector3f o = new Vector3f(feet).add(0, FOOT, 0);
		float d = Math.max(prev != null ? feet.distance(prev) : 0, LEDGE_WINDOW);
		Vector3f c = new Vector3f(o).fma(LEDGE_AHEAD + BODY_W / 2, f);
		Vector3f[] top = new Vector3f[2];
		LevelCollision.Tri topTri = null;
		for (int i = 0; i < 2; i++) {
			Vector3f s = new Vector3f(c).fma(i == 0 ? -BODY_W / 4 : BODY_W / 4, right).add(0, REACH + d, 0);
			LevelCollision.Hit h = world.segment(s, new Vector3f(s).sub(0, 2 * d, 0));
			if (h == null || Math.abs(h.tri().n().y) <= LEDGE_NY || h.tri().has(SurfaceAttr.SLIDE)
					|| h.tri().has(SurfaceAttr.NO_WALL_HANG)) {
				return null;
			}
			top[i] = h.point();
			if (i == 0) {
				topTri = h.tri();
			}
		}
		Vector3f ledge = new Vector3f(top[0]).add(top[1]).mul(0.5f);
		boolean descending = prev != null ? feet.y < prev.y : body.vy() < 0;
		if (descending && ledge.y - REACH < o.y) {
			return null;
		}
		if (world.segment(new Vector3f(o).add(0, REACH + d, 0), new Vector3f(ledge).add(0, 1, 0)) != null) {
			return null;
		}
		Vector3f base = new Vector3f(ledge).sub(0, REACH + d, 0).fma(-BODY_W / 2, f);
		LevelCollision.Hit[] wall = wallPair(base, f, right);
		if (wall == null) {
			wall = wallPair(new Vector3f(base).add(0, REACH - 2, 0), f, right);
			if (wall == null) {
				return null;
			}
		}
		Vector3f n = keepN != null ? new Vector3f(keepN) : facing(wall[0], base).add(facing(wall[1], base)).normalize();
		Vector3f up = new Vector3f(0, 1, 0).fma(-n.y, n);
		if (up.lengthSquared() < 1e-6f) {
			return null;
		}
		up.normalize();
		Vector3f s3 = new Vector3f(base).fma(REACH - 1, up);
		LevelCollision.Hit h3 = world.segment(s3, new Vector3f(s3).fma(BODY_W, f));
		if (h3 == null) {
			return null;
		}
		Vector3f np = plane(wall[0].point(), wall[1].point(), h3.point(), o, n);
		np.y = 0;
		if (np.lengthSquared() < 1e-6f) {
			np.set(n.x, 0, n.z);
		}
		np.normalize();
		Vector3f pos = new Vector3f(wall[0].point()).add(wall[1].point()).mul(0.5f).fma(WALL_OFF, np);
		pos.y = ledge.y - HANG;
		return new Ledge(ledge.y, ledge, pos, np, topTri);
	}

	/** 턱 아래 벽 두 선분 (b → b + 정면 × 19.2 ± 오른쪽 × 9.6). 둘 다 맞아야 한다. */
	private LevelCollision.Hit[] wallPair(Vector3f b, Vector3f f, Vector3f right) {
		Vector3f e = new Vector3f(b).fma(BODY_W, f);
		LevelCollision.Hit a = world.segment(b, new Vector3f(e).fma(BODY_W / 2, right));
		if (a == null) {
			return null;
		}
		LevelCollision.Hit c = world.segment(b, new Vector3f(e).fma(-BODY_W / 2, right));
		return c == null ? null : new LevelCollision.Hit[] {a, c};
	}

	// ---- 벽 오르기 / 사다리 ----

	private void enterClimb(WallProbe w, boolean ladder) {
		state = ladder ? NavState.LADDER : NavState.WALL_CLIMB;
		stateTime = 0;
		wallN.set(w.n());
		wallFlags(w.tri());
		ladderVel = 0;
		ladderSlide = false;
		Vector3f p = body.position();
		p.x = w.feet().x;
		p.z = w.feet().z;
		faceWall();
		body.setVy(0);
		body.setGrounded(false);
		body.launch().zero();
		body.play(ladder ? anims.get("ANILadder") : anims.get("ANIClimbIdle"), true);
	}

	private void wallFlags(LevelCollision.Tri t) {
		noBackJumps = t.has(SurfaceAttr.NO_BACK_JUMPS);
		noLadderSliding = t.has(SurfaceAttr.NO_LADDER_SLIDING);
	}

	private void climb(Input in, float dt) {
		boolean ladder = state == NavState.LADDER;
		Vector3f p = body.position();
		float sx = ladder ? 0 : in.leftX(), sy = in.leftY();
		if (Math.abs(sx) < 0.2f) {
			sx = 0;
		}
		if (Math.abs(sy) < 0.2f) {
			sy = 0;
		}
		if (in.pressed(PadButton.CROSS)) {
			// 원작 0x1E9D9C: 벽 점프는 등 뒤로만 — 스틱이 벽 반대 (벽 쪽 성분 < 0 → 0x11C 이탈 속도 음수), 좌우 속도 ≤ 좌우 최대의 절반,
			// 면이 NoBackJumps 가 아닐 때. 돌아서서 (0x1E9A88) 벽 반대로 Sold+0x11C, 위로 +0x118 [확실]. 그 밖의 × 는 무시.
			if (sy < 0 && Math.abs(sx * CLIMB_SIDE) <= CLIMB_SIDE * 0.5f && !noBackJumps) {
				body.setYaw(yawOf(wallN));
				leave(new Vector3f(wallN).mul(BACK_JUMP_OUT), BACK_JUMP_UP, "ANIWJump");
				return;
			}
		}
		if (in.pressed(PadButton.CIRCLE)) {
			leave(new Vector3f(), 0, "ANIFall");
			return;
		}
		Vector3f right = new Vector3f(wallN).negate().cross(0, 1, 0).normalize();
		float vx, vy;
		if (ladder) {
			vy = ladderVelocity(sy, dt);
			vx = 0;
		} else {
			// 0x1C8D08: (스틱 좌우 × 24, 스틱 상하 × 32), 길이 > 0.1 이면 [8, 32] 로 자름
			vx = sx * CLIMB_SIDE;
			vy = sy * CLIMB_SPEED;
			float len = (float) Math.hypot(vx, vy);
			if (len > 0.1f) {
				float k = Math.max(CLIMB_MIN, Math.min(CLIMB_SPEED, len)) / len;
				vx *= k;
				vy *= k;
			}
		}
		Vector3f cand = new Vector3f(p).fma(vx * dt, right).add(0, vy * dt, 0);
		Vector3f into = new Vector3f(wallN).negate();
		// 올라가는 중 (0x1C78FC: 수직 속도 &gt; 0) 턱 판정이 먼저: 사다리는 내려섬 (ANILDis1, 지상 상태), 벽은 턱 매달리기 (0x1C75F8) [확실]
		if (vy > 0) {
			Ledge l = probeLedge(cand, p, into, wallN);
			if (l != null) {
				if (ladder) {
					p.set(l.point());
					land("ANILDis1");
				} else {
					enterHang(l);
				}
				return;
			}
		}
		WallProbe w = probeWall(cand, into, wallN);
		if (w != null) {
			wallN.set(w.n());
			wallFlags(w.tri());
			p.set(w.feet());
			faceWall();
		} else if (vy > 0) {
			// 턱 판정이 안 되는 꼭대기 (엔진 보조 [근사]): 벽 너머 위 바닥이면 올라서고, 벽 위에 걸친 발판 (돛대 꼭대기처럼 몸 바로 위 바닥) 이면 그 위로
			Vector3f over = new Vector3f(p).fma(-(WALL_OFF + 16), wallN);
			NavWorld.Floor top = world.floor(over.x, p.y + FOOT + HANG + 16, over.z, HANG + 40);
			if (top != null && top.y() > p.y - 4) {
				p.set(over.x, top.y(), over.z);
				land(ladder ? "ANILDis1" : "ANIWallPullup");
				return;
			}
			NavWorld.Floor above = world.floor(p.x, p.y + FOOT + HANG + 16, p.z, FOOT + HANG + 8);
			if (above != null && above.y() > p.y + 8) {
				p.y = above.y();
				land(ladder ? "ANILDis1" : "ANIWallPullup");
				return;
			}
		}
		if (vy < 0) {
			NavWorld.Floor f = world.floor(p.x, p.y + 2, p.z, Math.max(6, -vy * dt + 2));
			if (f != null) {
				p.y = f.y();
				land(null);
				return;
			}
		}
		String a;
		if (ladder) {
			a = ladderSlide ? anims.get("ANILSlide") : vy > 0 ? anims.get("ANILadderU") : vy < 0 ? anims.get("ANILadderD") : anims.get("ANILadder");
		} else if (sy == 0 && sx == 0) {
			a = anims.get("ANIClimbIdle");
		} else if (Math.abs(sy) >= Math.abs(sx)) {
			a = NavAnims.CLIMB[sy > 0 ? 0 : 2];
		} else {
			a = NavAnims.CLIMB[sx > 0 ? 1 : 3];
		}
		body.play(a, false);
	}

	/**
	 * 사다리 수직 속도 (원작 0x1C8A88, 상태 +0x174 0x400000): 평소 스틱 부호 × Sold+0x124 (크기 무관).
	 * 미끄럼 (LadderSlide 능력 + 면이 NoLadderSliding 아님): 내려가는 중 (−16 보다 빠름) 에 스틱 아래 &gt; 0.9 면 시작,
	 * 스틱 유지 = 가속 +0x130 (최대 +0x12C), 놓음 = 감속 +0x134, −+0x124 보다 느려지면 끝 [확실]. 영웅은 LadderSlide 능력 있음 [추정].
	 */
	private float ladderVelocity(float sy, float dt) {
		boolean hard = sy < -LADDER_SLIDE_STICK;
		if (!ladderSlide && !noLadderSliding && hard && ladderVel < -16) {
			ladderSlide = true;
		}
		if (ladderSlide) {
			if (hard) {
				ladderVel = Math.max(-LADDER_SLIDE_MAX, Math.min(0, ladderVel - LADDER_SLIDE_ACC * dt));
			} else {
				ladderVel = Math.max(-LADDER_SLIDE_MAX, Math.min(0, ladderVel + LADDER_SLIDE_DEC * dt));
				if (ladderVel > -LADDER_SPEED) {
					ladderSlide = false;
				}
			}
			if (ladderSlide) {
				return ladderVel;
			}
		}
		ladderVel = sy > 0 ? LADDER_SPEED : sy < 0 ? -LADDER_SPEED : 0;
		return ladderVel;
	}

	// ---- 턱 매달리기 ----

	/**
	 * 턱 매달리기 진입 (원작 0x1C75F8, +0x174 0x10): 애니 0xD ANIWallHangCatch, 타이머 = 턱 면 WallHangTimer (+0x234 면, 0 이면 무한).
	 * 타이머가 이미 돌고 있으면 (+0x2D8 0x400, 남은 시간 &gt; 0) 이어 간다 — 지상·벽 오르기·물에 닿아야 초기화 (0x1C78A0) [확실].
	 */
	private void enterHang(Ledge l) {
		float limit = l.top() != null && l.top().info() != null ? l.top().info().wallHangTimer() : 0;
		if (limit == 0 || !hangTiming || hangTimer == 0) {
			hangTiming = true;
			hangTimer = limit == 0 ? Float.MAX_VALUE : limit;
		}
		state = NavState.LEDGE_HANG;
		stateTime = 0;
		wallN.set(l.n());
		ledgeY = l.y();
		body.position().set(l.feet());
		faceWall();
		body.setVy(0);
		body.setGrounded(false);
		body.launch().zero();
		body.play(anims.get("ANIWallHangCatch"), true);
	}

	/** 매달리기 타이머 초기화 (+0x2A4 = FLT_MAX, +0x2D8 0x400 끔): 지상·+0x174 2·물 위 (0x80)·벽 오르기 (0x20) (0x1C78A0) [확실]. */
	private void resetHangTimer() {
		hangTiming = false;
		hangTimer = Float.MAX_VALUE;
	}

	/** 남은 매달리기 시간 (초, 무한 = {@link Float#MAX_VALUE}, 0 = 다 써서 공중에서 턱을 못 잡음). */
	public float hangTimer() {
		return hangTimer;
	}

	private void hang(Input in, float dt) {
		Vector3f p = body.position();
		// WallHangTimer: 매 프레임 −dt (원작 0x1C7B04: dt × (vtable +0xC8 × 0.25 + 0.75) × +0x16C [추정: 배율 1]), 0 이면 떨어짐 (0x1E9030 모드 2)
		if (hangTimer != Float.MAX_VALUE) {
			hangTimer = Math.max(0, hangTimer - dt);
			if (hangTimer == 0) {
				leave(new Vector3f(), 0, "ANIFall");
				return;
			}
		}
		float sx = in.leftX(), sy = in.leftY();
		if (in.pressed(PadButton.CROSS) || sy > 0.6f) {
			Vector3f over = new Vector3f(p).fma(-(WALL_OFF + 14), wallN);
			NavWorld.Floor top = world.floor(over.x, ledgeY + 8, over.z, 24);
			if (top != null) {
				p.set(over.x, top.y(), over.z);
				land("ANIWallPullup");
				return;
			}
		}
		if (in.pressed(PadButton.CIRCLE) || sy < -0.6f) {
			leave(new Vector3f(), 0, "ANIFall");
			return;
		}
		String a = anims.get("ANIWallHang");
		if (Math.abs(sx) > 0.3f) {
			// 좌우 Sold+0x104 (스틱 크기 무관), 매 프레임 0x1E3A80 으로 턱을 다시 찾는다 (붙은 법선 유지). 못 찾으면 그 자리 [근사: 원작은 떨어짐]
			Vector3f right = new Vector3f(wallN).negate().cross(0, 1, 0).normalize();
			Vector3f cand = new Vector3f(p).fma(Math.signum(sx) * SHIMMY_SPEED * dt, right);
			Ledge l = probeLedge(cand, p, new Vector3f(wallN).negate(), wallN);
			if (l != null) {
				wallN.set(l.n());
				ledgeY = l.y();
				p.set(l.feet());
				faceWall();
				a = anims.get(sx > 0 ? "ANIWallHangR" : "ANIWallHangL");
			}
		} else if (stateTime < 0.4f) {
			a = anims.get("ANIWallHangCatch");
		}
		body.play(a, false);
	}

	// ---- 줄 ----

	private void grabRope(NavRope r, Vector3f hand) {
		rope = r;
		ropeT = r.closest(hand);
		stateTime = 0;
		ropeVel = 0;
		ropeSlide = false;
		body.setVy(0);
		body.setGrounded(false);
		body.launch().zero();
		Vector3f d = new Vector3f(r.end()).sub(r.top());
		switch (r.type()) {
			case ZIPLINE -> {
				state = NavState.ZIPLINE;
				// 낮은 쪽으로 미끄러진다
				zipDir = r.end().y <= r.top().y ? 1 : -1;
				zipSpeed = 0; // [추정: 잡을 때 줄 방향 속도 0 에서 가속]
				body.setYaw(yawOf(new Vector3f(d).mul(zipDir)));
				body.play(anims.get("ANIZipLine"), true);
			}
			case TRAVERSE -> {
				state = NavState.ROPE_TRAVERSE;
				body.play(anims.get("ANIBRope"), true);
			}
			default -> {
				state = NavState.ROPE;
				RopeSim sim = sim(r);
				sim.attach(ropeT * sim.length());
				ropeT = sim.dist() / sim.length();
				pumpTimer = 0;
				pumpHeld = false;
				body.play(anims.get("ANIRope"), true);
			}
		}
		placeOnRope();
	}

	/** 매단 줄 시뮬레이션 (없으면 만든다). */
	RopeSim sim(NavRope r) {
		return sims.computeIfAbsent(r, k -> new RopeSim(k.top(), k.length(), k.joints()));
	}

	/** 줄 위 손 위치 → 몸 위치 (0x1C66E4: 몸 = 줄 점 − 키). 매단 줄은 시뮬레이션 줄 위 붙은 거리의 점. */
	private void placeOnRope() {
		Vector3f hand = state == NavState.ROPE ? sim(rope).pointAt(sim(rope).dist(), new Vector3f()) : rope.point(ropeT, new Vector3f());
		body.position().set(hand.x, hand.y - ROPE_HAND, hand.z);
	}

	/** 매단 줄에서 떨어짐: 매달림 해제 (0x218270, 무게는 줄어들며 줄은 계속 흔들림). */
	private void releaseRope() {
		if (rope != null && state == NavState.ROPE) {
			sim(rope).detach();
		}
	}

	private void hangRope(Input in, float camYaw, float dt) {
		RopeSim sim = sim(rope);
		if (in.pressed(PadButton.CROSS)) {
			// 줄 점 속도 + 정면 40 + 점프 초속으로 뛰어내림 [근사: 원작 줄 이탈 0x1C5848 미해석]
			Vector3f v = sim.velocityAt(sim.dist(), new Vector3f());
			leave(new Vector3f(v.x, 0, v.z).add(new Vector3f(forward(body.yaw())).mul(40)), JUMP + Math.max(0, v.y), "ANIFall");
			return;
		}
		float sy = in.leftY(), sx = in.leftX();
		String a = anims.get("ANIRope");
		ropeVel = ropeVelocity(sy, dt);
		if (ropeVel != 0) {
			// 줄 위치 (m) += 속도 / 16 · dt, [0.25, 길이 − 0.25] (0x1C6578 → 0x2183B0)
			float want = sim.dist() + ropeVel / NavTuning.UNITS_PER_METER * dt;
			float got = sim.moveAttach(want);
			ropeT = got / sim.length();
			a = ropeSlide ? anims.get("ANIRSlide") : anims.get(ropeVel < 0 ? "ANIRopeU" : "ANIRopeD");
			// 아래 끝에서 바닥이 있으면 내림
			if (ropeVel > 0 && want > got) {
				Vector3f p = body.position();
				NavWorld.Floor f = world.floor(p.x, p.y + 4, p.z, 24);
				if (f != null) {
					p.y = f.y();
					land(null);
					return;
				}
			}
		}
		// 흔들기: 스틱을 새로 밀면 0.25 초 창 동안 힘 = 스틱 방향 × 500 × 거리 × 2 × 남은 시간 (0x1C6BE8)
		if (Math.abs(sx) > 0.3f || Math.abs(sy) > 0.3f && ropeVel == 0) {
			Vector3f d = stickDir(in, camYaw);
			if (!pumpHeld || d.dot(pumpDir) < 0.5f) {
				pumpDir.set(d);
				pumpTimer = SWING_WINDOW;
				body.setYaw(yawOf(d));
			}
			pumpHeld = true;
			a = anims.get("ANIRopeIdle");
		} else {
			pumpHeld = false;
		}
		// 매 프레임 매달림 힘을 준다 (0x1C71A8 → 0x218470, 줄이 잠들지 않음)
		sim.setForce(new Vector3f(pumpDir).mul(pumpTimer > 0 ? SWING_FORCE * sim.dist() * 2 * pumpTimer : 0));
		pumpTimer = Math.max(0, pumpTimer - dt);
		placeOnRope();
		body.play(a, false);
	}

	/**
	 * 매단 줄 속도 (+ = 아래, 원작 0x1C63D0): 스틱 아래 &gt; 0.8 이면 미끄럼 시작 (속도 0 에서 +0x150 가속, 최대 +0x14C),
	 * 미끄럼 중 스틱을 놓으면 +0x154 감속, 0 이면 끝. 그 밖 = 스틱 × Sold+0x138 (최소 +0x13C) [확실]. 영웅은 RopeSlide 능력 있음 [추정].
	 */
	private float ropeVelocity(float sy, float dt) {
		boolean hard = sy < -0.8f;
		if (!ropeSlide && hard) {
			ropeSlide = true;
			ropeVel = 0;
		}
		if (ropeSlide) {
			if (hard) {
				ropeVel = Math.max(0, Math.min(ROPE_SLIDE_MAX, ropeVel + ROPE_SLIDE_ACC * dt));
			} else {
				ropeVel = Math.max(0, Math.min(ROPE_SLIDE_MAX, ropeVel - ROPE_SLIDE_DEC * dt));
				if (ropeVel == 0) {
					ropeSlide = false;
				}
			}
			return ropeVel;
		}
		return Math.abs(sy) < 0.2f ? 0 : -Math.signum(sy) * Math.max(ROPE_MIN, Math.abs(sy) * ROPE_SPEED);
	}

	private void traverse(Input in, float camYaw, float dt) {
		if (in.pressed(PadButton.CROSS) || in.pressed(PadButton.CIRCLE)) {
			leave(new Vector3f(), 0, "ANIFall");
			return;
		}
		Vector3f d = new Vector3f(rope.end()).sub(rope.top()).normalize();
		float along = stickMag(in) > 0.3f ? stickDir(in, camYaw).dot(new Vector3f(d.x, 0, d.z).normalize()) : 0;
		String a = anims.get("ANIBRope");
		if (Math.abs(along) > 0.3f) {
			float v = Math.max(TRAVERSE_MIN, Math.abs(along) * TRAVERSE_SPEED); // 0x1C6370 → 0x1E6080
			ropeT = Math.max(0, Math.min(1, ropeT + Math.signum(along) * v * dt / Math.max(1, rope.length())));
			body.setYaw(yawOf(new Vector3f(d).mul(Math.signum(along))));
			a = anims.get("ANIBRopeF");
			if (ropeT <= 0 || ropeT >= 1) {
				placeOnRope();
				Vector3f p = body.position();
				NavWorld.Floor f = world.floor(p.x, p.y + ROPE_HAND, p.z, ROPE_HAND + 24);
				if (f != null) {
					p.y = f.y();
					land(null);
					return;
				}
			}
		}
		placeOnRope();
		body.play(a, false);
	}

	private void zip(Input in, float dt) {
		Vector3f d = new Vector3f(rope.end()).sub(rope.top());
		float len = Math.max(1, d.length());
		d.div(len).mul(zipDir);
		if (in.pressed(PadButton.CROSS)) {
			leave(new Vector3f(d.x, 0, d.z).mul(zipSpeed * 0.5f), JUMP * 0.4f, "ANIFall"); // [근사]
			return;
		}
		// 원작 0x1C60F8: 목표 Sold+0x160 으로 가속 +0x164 (줄 기울기 무관), 줄 위치 (m) += 속도 / 16 · dt
		zipSpeed = Math.min(ZIP_SPEED, zipSpeed + ZIP_ACCEL * dt);
		float endGap = ROPE_TOP_GAP / len;
		ropeT += zipDir * zipSpeed * dt / len;
		if (zipDir > 0 ? ropeT >= 1 - endGap : ropeT <= endGap) {
			ropeT = Math.max(0, Math.min(1, ropeT));
			placeOnRope();
			// 끝 0.25 m: 원작 0x1C6B1C 는 월드 속도를 0 으로 하고 놓는다 (0x1E9030 모드 2) [추정: 짚라인에도 같은 경로]
			leave(new Vector3f(), 0, "ANIFall");
			cooldown = 0.6f;
			return;
		}
		placeOnRope();
		body.play(anims.get("ANIZipLine"), false);
	}

	// ---- 수영 ----

	/**
	 * 수면 아래에 헤엄 깊이(16) + 4 안쪽으로 단단한 바닥이 있으면 얕은 물 — 헤엄치지 않고 수면 면 위를 걷는다 [추정].
	 * 헤엄 중 물가 판정(수면 − 20 이상 마른 바닥이면 올라섬)과 같은 기준. 예: ATHN08A/08C 아레스 만은 Water 면(y 1) 아래
	 * 7.7 에 바다 바닥(y −6.7, TWK /GlobGame/BAR "bay floor" −0.4125·16)이 있어 거인 크레토스가 물을 헤치고 싸운다 —
	 * 전엔 헤엄 상태로 바닥 아래(y −15)에 갇혔다.
	 */
	private boolean deepWater(Vector3f p, float surface) {
		NavWorld.Floor bed = world.floor(p.x, surface - 0.5f, p.z, SWIM_DEPTH + 4);
		return bed == null || bed.has(SurfaceAttr.WATER);
	}

	private void enterSwim(float surface) {
		state = NavState.SWIM;
		stateTime = 0;
		waterY = surface;
		swimSpeed = 0;
		body.position().y = surface - SWIM_DEPTH;
		body.setVy(0);
		body.setGrounded(false);
		body.launch().zero();
		body.play(anims.get("ANISwimIdle"), true);
	}

	private void swim(Input in, float camYaw, float dt) {
		Vector3f p = body.position();
		float mag = stickMag(in);
		// 원작 0x1CA5F4: 정면을 스틱 방향으로 프레임당 Sold+0x1E8 비율로 돌리고, 정면 속도를 목표 (+0x1D4 × 스틱, 최소 +0x1DC) 로 가감속 (+0x1E0/+0x1E4)
		float target = 0;
		if (mag > 0.2f) {
			float want = yawOf(stickDir(in, camYaw));
			float d = (float) Math.IEEEremainder(want - body.yaw(), 2 * Math.PI);
			body.setYaw(body.yaw() + d * Math.min(1f, SWIM_TURN * 60f * dt));
			target = Math.max(SWIM_MIN, SWIM_SPEED * mag);
		}
		swimSpeed = target > swimSpeed ? Math.min(target, swimSpeed + SWIM_ACCEL * dt) : Math.max(target, swimSpeed - SWIM_DECEL * dt);
		String a = anims.get(swimSpeed > 1.6f ? "ANISwim" : "ANISwimIdle");
		if (swimSpeed > 0) {
			Vector3f fwd = forward(body.yaw());
			Vector3f cand = new Vector3f(p).fma(swimSpeed * dt, fwd);
			NavWorld.Floor f = world.floor(cand.x, waterY + 24, cand.z, 200);
			if (f != null && !f.has(SurfaceAttr.WATER) && f.y() >= waterY - SWIM_DEPTH - 4) {
				// 물가: 올라선다
				p.set(cand.x, f.y(), cand.z);
				land(null);
				return;
			}
			Vector3f c = new Vector3f(cand).add(0, SWIM_DEPTH, 0);
			world.collideSphere(c, BODY);
			p.set(c.x, waterY - SWIM_DEPTH, c.z);
		}
		body.play(a, false);
	}

	// ---- 밀기 / 상황 동작 ----

	/** 상황 동작 영웅 애니 단계: TAndF 잡기 (ANITandF1) / 본 동작 (ANICSM1·leverpull·ANITandF2) / 끝 (ANICSM2·ANITandF3). */
	static final int PH_GRAB = 0, PH_MAIN = 1, PH_FINISH = 2;
	/** 단계 끝 판정 여유 (영웅 애니와 오브젝트 애니 길이가 같으므로 같은 틱에 끝나게). */
	private static final float EPS = 1e-4f;

	private void startItem(Interactables.Item it) {
		item = it;
		released.remove(it);
		stateTime = 0;
		phaseTime = 0;
		sincePress = 0;
		doneNotified = it.done;
		Vector3f p = body.position();
		Vector3f to = new Vector3f(it.pos.x - p.x, 0, it.pos.z - p.z);
		if (to.lengthSquared() < 1e-4f) {
			to.set(forward(body.yaw()));
		}
		switch (it.kind) {
			case PUSH -> {
				state = NavState.PUSH;
				// 상자 축 = 영웅→상자 방향의 주축 (x 또는 z) [근사: 상자 로컬 축 미사용]
				if (Math.abs(to.x) > Math.abs(to.z)) {
					pushAxis.set(Math.signum(to.x), 0, 0);
				} else {
					pushAxis.set(0, 0, Math.signum(to.z));
				}
				body.setYaw(yawOf(pushAxis));
				charging = false;
				kickAnimTime = -1;
				// 영웅 = 붙잡기 관절에서 정면 반 깊이 뒤 (0x1C4638) [근사: 관절 대신 상자 경계구 반지름]
				float gap = it.radius + BODY;
				p.x = it.pos.x - pushAxis.x * gap;
				p.z = it.pos.z - pushAxis.z * gap;
				listener.pushAttach(it);
				body.play(anims.get("ANIPushI"), true);
			}
			default -> {
				state = NavState.INTERACT;
				body.setYaw(yawOf(to.normalize()));
				if ((it.kind == Interactables.Kind.CSM || it.kind == Interactables.Kind.CHEST) && !it.lengthKnown && it.task().time == 0) {
					// 상자 (Creator) 는 오브젝트 ANM 길이를 모른다 → 영웅 ANICSM1 길이 [추정: IO_CSM 은 모두 영웅 ANICSM1 = 오브젝트 애니 길이]
					float l = body.clipLength(it.heroAnim);
					if (l > 0) {
						it.relength(l);
					}
				}
				// 붙잡기 = 원작 0x1E17F0: 모든 종류가 1048 (인자 0 = CSM/상자 과제 id +0x1CC, TAndF +0x1D0, PushPull +0x284) [확실]
				listener.pushAttach(it);
				phase = PH_MAIN;
				if (it.kind == Interactables.Kind.LIFT && clip(it.startAnim, 0) > 0) {
					phase = PH_GRAB;
					body.play(it.startAnim, true);
				} else {
					body.play(it.heroAnim, true);
				}
			}
		}
	}

	/** 영웅 클립 길이 (초), 모르면 def. */
	private float clip(String anim, float def) {
		if (anim == null) {
			return def;
		}
		float l = body.clipLength(anim);
		return l >= 0 ? l : def;
	}

	private void rewindReleased(float dt) {
		for (java.util.Iterator<Interactables.Item> i = released.iterator(); i.hasNext(); ) {
			Interactables.Item it = i.next();
			if (it == item) {
				i.remove();
				continue;
			}
			idle(it, dt);
			if (it.pushType == 4 || it.pushType == 3 ? it.task().speed == 0 : it.task().time <= 0 || it.task().done) {
				i.remove();
			}
		}
	}

	/** 영웅이 떨어진 오브젝트 한 프레임: CSM/레버 −4 로 되감기, 크랭크 #b6ba95a3 가속으로 되감기, TAndF 내려옴. */
	private void idle(Interactables.Item it, float dt) {
		TaskProgress t = it.task();
		if (it.kind == Interactables.Kind.LIFT) {
			send(it, t.lift(false, false, 0, it.liftParams(), dt));
		} else if (it.pushType == 4) {
			send(it, t.ring(false, 0, it.ringParams(), dt)); // 링 크랭크: 가장 가까운 멈춤 자리로 (0x2131FC)
		} else if (it.pushType == 3) {
			send(it, t.crane(false, 0, it.ringParams(), dt)); // 기중기형: Dec 로 멈춤 (0x212DAC)
		} else if (it.crank) {
			// 원작 0x212B08: t > 0 이면 속도 −= 되감기 가속 · dt, 최대 −최대 되감기
			send(it, t.advance(t.time > 0 ? Math.max(t.speed - it.rewindAcc * dt, -it.maxBack) : 0, dt));
		} else {
			send(it, t.update(false, dt));
		}
	}

	/** 1023 송신 (원작 0x215928 / 0x2118E8 / 0x213F90) + 완료 통지. */
	private void send(Interactables.Item it, TaskProgress.Event e) {
		it.progress = it.task().fraction();
		if (e != null) {
			it.lastPercent = e.percent();
			listener.taskProgress(it, e);
		}
		if (it.task().done && !it.done) {
			it.done = true;
		}
		if (it.done && it == item && !doneNotified) {
			doneNotified = true;
			listener.interactDone(it);
		}
	}

	/**
	 * 붙어 있는 동안 (원작 영웅 0x1C4258, 오브젝트 0x215DF0 / 0x2127B0 / 0x212998 / 0x214400) [확실: 디스어셈블, 38 문서 §3.4].
	 * <pre>
	 * CSM/상자 (붙잡기 모드 0x20/0x40): 영웅 애니 ANICSM1 (템플릿 #b50a547c) 한 번 → 다음 슬롯 ANICSM2 (#b50a547d) → 끝나면 놓음.
	 *   ANICSM1 동안 템플릿 비트 #fb908478 (상자) 이면 R1 을 누르고 있어야 하고, 떼면 바로 놓는다. 문·책은 한 번 누르면 끝까지.
	 *   오브젝트 애니는 붙어 있는 동안 속도 1 → 데이터상 ANICSM1 길이 = 오브젝트 애니 길이라 끝까지 붙어 있으면 100 %.
	 * 레버 (모드 4/8): leverpull 한 번 (3.8 초, 물속 7.67 초) → 끝나면 놓음. 레버 오브젝트 (3.67 초) 는 속도 1, 끝에서 멈춤.
	 * 크랭크 (모드 2): R1 을 떼면 놓음. 누른 채 스틱을 밀면 속도 += Acc·dt (최대 #fe467fe6), 안 밀면 −Dec·dt (0 까지). 끝에 닿으면 놓음.
	 * TAndF (모드 0x10): ANITandF1 (잡기) → ANITandF2 동안 R1 을 누를 때마다 속도 +Acc, TF Time 동안 안 누르면 −Dec·dt.
	 *   R1 을 떼고 스틱을 문 반대로 밀면 놓음. 다 들면 ANITandF3 → 끝나면 놓음.
	 * </pre>
	 */
	private void interact(Input in, float camYaw, float dt) {
		Interactables.Item it = item;
		phaseTime += dt;
		boolean held = in.held(PadButton.USE);
		TaskProgress t = it.task();
		switch (it.kind) {
			case LEVER -> {
				if (it.crank) {
					if (!held || it.done) {
						release(it);
						return;
					}
					int input = crankInput(in, camYaw);
					if (it.pushType == 4 || it.pushType == 3) {
						body.play(input != 0 ? anims.get(input > 0 ? "ANICrankPush" : "ANICrankPull") : it.heroAnim, false);
						send(it, it.pushType == 4 ? t.ring(true, input, it.ringParams(), dt) : t.crane(true, input, it.ringParams(), dt));
						return;
					}
					boolean turning = input > 0;
					float spd = turning ? Math.min(t.speed + it.acc * dt, it.maxSpeed)
							: t.time > 0 ? Math.min(Math.max(t.speed - it.dec * dt, 0), it.maxSpeed) : 0;
					body.play(turning ? anims.get("ANICrankPush") : it.heroAnim, false);
					send(it, t.advance(spd, dt));
					if (it.done) {
						release(it);
					}
					return;
				}
				send(it, t.update(true, dt));
				if (phaseTime >= clip(it.heroAnim, it.length) - EPS) {
					release(it);
				}
			}
			case LIFT -> {
				boolean press = in.pressed(PadButton.USE);
				if (phase == PH_GRAB && phaseTime >= clip(it.startAnim, 0) - EPS) {
					phase = PH_MAIN;
					phaseTime = 0;
					body.play(it.heroAnim, true);
				}
				// 붙잡을 때 마지막 누름 시간 0 (0x1C3590) → 첫 틱에 한 번 가속. 누름 시간은 ANITandF2 동안만 0 으로 (0x1C4A9C)
				boolean impulse = stateTime <= dt + EPS || phase == PH_MAIN && press;
				sincePress = impulse ? 0 : sincePress + dt;
				send(it, t.lift(true, impulse, sincePress, it.liftParams(), dt));
				if (phase != PH_FINISH && it.done) {
					phase = PH_FINISH;
					phaseTime = 0;
					if (it.finishAnim != null) {
						body.play(it.finishAnim, true);
					}
				}
				if (phase == PH_FINISH) {
					if (phaseTime >= clip(it.finishAnim, 0) - EPS) {
						release(it);
					}
					return;
				}
				// R1 을 뗀 채 스틱을 문 반대쪽으로 (관절 축과의 내적 > 0.5, 0x1C4B48) [근사: 영웅→문 방향의 반대]
				if (!held && stickMag(in) > 0.5f) {
					Vector3f away = new Vector3f(body.position().x - it.pos.x, 0, body.position().z - it.pos.z);
					if (away.lengthSquared() > 1e-4f && stickDir(in, camYaw).dot(away.normalize()) > 0.5f) {
						release(it);
					}
				}
			}
			default -> {
				// CSM / 상자
				send(it, t.update(true, dt));
				if (phase == PH_MAIN) {
					if (it.hold && !held) {
						release(it);
						return;
					}
					if (phaseTime >= clip(it.heroAnim, it.length) - EPS) {
						phase = PH_FINISH;
						phaseTime = 0;
						if (it.finishAnim != null) {
							body.play(it.finishAnim, true);
						}
					}
				}
				if (phase == PH_FINISH && phaseTime >= clip(it.finishAnim, 0) - EPS) {
					release(it);
				}
			}
		}
	}

	/** 놓기 = 원작 0x1E18F8 (1049). 끝나지 않은 오브젝트는 스스로 되감긴다 ({@link #rewindReleased}). */
	private void release(Interactables.Item it) {
		listener.pushDetach(it);
		if (!it.done) {
			released.add(it);
		}
		end();
	}

	/**
	 * 밀기 상자 (붙잡기 모드 1, Push Type 0) [확실: 0x1C4258 / 0x212398 → 0x211A10, 38 문서 §3.4].
	 * <pre>
	 * 사용 버튼을 떼면 놓음 (1049). 스틱 = 영웅 속도 Sold+0x188[무게] × 스틱 (최소 +0x17C[무게]) — 영웅이 밀어 상자가 같이 움직이고
	 *   상자 속도는 템플릿 #95b8bfb6 (+0x28, 5 m/s) 이하 (0x211BD8) [추정: 접촉 전달 = 영웅 속도]. 안 밀면 상자 마찰 감속 9.82 × 16 (0x1E0910).
	 * × 누르고 있음 (+0x310 0x400) = 차기 충전 (+0x2B8 += dt, ANIStartKickBox → ANIKickBoxIdle), 떼면 차기 (0x1C497C): 이벤트 0x41D,
	 *   상자 속도 += 정면 × lerp(Sold+0x194[무게], +0x1A0[무게], 충전 / 2 초), ANIKickBox. 차인 상자는 마찰로 멈출 때까지 미끄러진다.
	 * 상자 축 = 영웅→상자 방향의 주축 (x 또는 z) [근사: 상자 로컬 축·좌우 PullL/PullR 미사용].
	 * </pre>
	 */
	private void push(Input in, float camYaw, float dt) {
		Interactables.Item it = item;
		if (!in.held(PadButton.USE)) {
			detachPush(it);
			return;
		}
		String kickStart = anims.get("ANIStartKickBox"), kick = anims.get("ANIKickBox");
		if (in.held(PadButton.CROSS)) {
			if (!charging) {
				charging = true;
				kickCharge = 0;
				kickAnimTime = 0;
				body.play(kickStart, true);
			} else {
				kickCharge += dt;
				kickAnimTime += dt;
				if (kickAnimTime >= clip(kickStart, 0)) {
					body.play(anims.get("ANIKickBoxIdle"), false);
				}
			}
			boxFriction(it, dt);
			follow(moveBox(it, dt));
			return;
		}
		if (charging) {
			charging = false;
			it.vel.fma(kickSpeed(it.weight, kickCharge), pushAxis);
			clampBox(it);
			listener.boxKicked(it);
			kickAnimTime = 0;
			body.play(kick, true);
		}
		// 상자 축 성분 × 스틱 크기 (원작은 영웅 국소 x/z 성분 그대로 [근사: 축 방향만])
		float along = stickMag(in) > 0.2f ? stickDir(in, camYaw).dot(pushAxis) * stickMag(in) : 0;
		float target = 0;
		if (Math.abs(along) > 0.2f) {
			target = Math.signum(along) * Math.max(pushSpeedMin(it.weight), Math.min(pushSpeedMax(it.weight), Math.abs(along) * pushSpeedMax(it.weight)));
		}
		boolean kicking = kickAnimTime >= 0 && (kickAnimTime += dt) < clip(kick, 0);
		if (target != 0 && !kicking) {
			it.vel.set(pushAxis).mul(target);
			clampBox(it);
		} else {
			boxFriction(it, dt);
		}
		Vector3f moved = moveBox(it, dt);
		follow(moved);
		if (!kicking) {
			kickAnimTime = -1;
			String a = anims.get("ANIPushI");
			if (target != 0 && moved.lengthSquared() > 0) {
				a = anims.get(target > 0 ? "ANIPushF" : "ANIPushB");
			}
			body.play(a, false);
		}
	}

	private void follow(Vector3f moved) {
		body.position().add(moved.x, 0, moved.z);
	}

	private void detachPush(Interactables.Item it) {
		listener.pushDetach(it);
		charging = false;
		kickAnimTime = -1;
		if (it.vel.lengthSquared() > 0 && !sliding.contains(it)) {
			sliding.add(it);
		}
		end();
	}

	/** 상자 최대 속도 = 템플릿 #95b8bfb6 × 16 (0x211BD8: 넘으면 그 크기로 자름). */
	private static void clampBox(Interactables.Item it) {
		float max = it.slideMax * NavTuning.UNITS_PER_METER, v = it.vel.length();
		if (v > max) {
			it.vel.mul(max / v);
		}
	}

	/** 0x1E0910 (계수 1): |v| &gt; 1.6 이면 v ×= clamp(1 − 9.82·16·dt / |v|, 0, 1), 아니면 0. */
	private static void boxFriction(Interactables.Item it, float dt) {
		float v = it.vel.length();
		if (v > BOX_REST) {
			it.vel.mul(Math.max(0, Math.min(1, 1 - BOX_FRICTION * dt / v)));
		} else {
			it.vel.zero();
		}
	}

	/** 상자를 속도만큼 옮긴다 (밑에 바닥, 앞쪽 벽 = 상자 반지름 구가 밀리면 막힘 [근사: 원작 상자 모서리 질의 0x210DC8]). @return 옮긴 양 */
	private Vector3f moveBox(Interactables.Item it, float dt) {
		Vector3f delta = new Vector3f(it.vel.x, 0, it.vel.z).mul(dt);
		if (delta.lengthSquared() < 1e-10f) {
			return delta.zero();
		}
		Vector3f cand = new Vector3f(it.pos).add(delta);
		NavWorld.Floor f = world.floor(cand.x, it.pos.y + 16, cand.z, 32);
		Vector3f c = new Vector3f(cand).add(0, it.radius * 0.5f, 0);
		Vector3f probe = new Vector3f(c);
		boolean blocked = world.collideSphere(probe, it.radius * 0.8f) && probe.distance(c) > 0.5f;
		if (f == null || blocked) {
			it.vel.zero();
			return delta.zero();
		}
		delta.y = f.y() - it.pos.y;
		it.pos.add(delta);
		listener.pushMoved(it, delta);
		return delta;
	}

	/** 놓였거나 차인 상자: 마찰로 멈출 때까지 (원작 상자 갱신 0x212398 은 붙잡기와 무관하게 매 프레임). */
	private void slideBoxes(float dt) {
		for (java.util.Iterator<Interactables.Item> i = sliding.iterator(); i.hasNext(); ) {
			Interactables.Item it = i.next();
			if (it == item) {
				i.remove();
				continue;
			}
			boxFriction(it, dt);
			moveBox(it, dt);
			if (it.vel.lengthSquared() == 0) {
				i.remove();
			}
		}
	}

	private void end() {
		item = null;
		state = NavState.GROUND;
		body.setGrounded(true);
		cooldown = 0.25f;
	}

	// ---- 공통 ----

	/** 줄/벽을 놓고 공중으로. */
	private void leave(Vector3f launch, float vy, String anim) {
		releaseRope();
		state = NavState.AIR;
		rope = null;
		body.launch().set(launch.x, 0, launch.z);
		body.setVy(vy);
		body.setGrounded(false);
		cooldown = 0.4f;
		body.play(anims.get(anim), true);
	}

	private void land(String anim) {
		releaseRope();
		state = NavState.GROUND;
		rope = null;
		body.setVy(0);
		body.setGrounded(true);
		body.launch().zero();
		cooldown = 0.3f;
		if (anim != null) {
			body.play(anims.get(anim), true);
		}
	}

	private void faceWall() {
		body.setYaw(yawOf(new Vector3f(wallN).negate()));
	}

	/**
	 * 크랭크 입력 (원작 0x2117E0): 영웅 이동 방향(수평, 정규화) · 붙잡은 손잡이 관절의 −Z (비트 31 이면 +Z).
	 * 붙잡기 조건 (0x1C3918) 이 "영웅이 관절 +Z 쪽에 서서 −Z 를 바라봄" 이므로 −Z = 영웅 정면 → 스틱 방향 · 영웅 정면 [근사: 관절 행렬 대신 영웅 방향].
	 *
	 * @return +1 (미는 쪽 &gt; 0.3) / −1 (당기는 쪽 &lt; −0.3) / 0
	 */
	private int crankInput(Input in, float camYaw) {
		if (stickMag(in) < 0.3f) {
			return 0;
		}
		float d = stickDir(in, camYaw).dot(forward(body.yaw()));
		return d > 0.3f ? 1 : d < -0.3f ? -1 : 0;
	}

	public static Vector3f forward(float yaw) {
		return new Vector3f((float) -Math.sin(yaw), 0, (float) -Math.cos(yaw));
	}

	/** 방향 → yaw (정면 = (-sin, -cos)). */
	public static float yawOf(Vector3f d) {
		return (float) Math.atan2(-d.x, -d.z);
	}

	private static float stickMag(Input in) {
		return Math.min(1f, (float) Math.hypot(in.leftX(), in.leftY()));
	}

	/** 스틱 → 월드 수평 방향 (카메라 정면 = 스틱 위, HeroController 와 같은 규칙). */
	private static Vector3f stickDir(Input in, float camYaw) {
		float dirYaw = camYaw + (float) Math.atan2(-in.leftX(), in.leftY());
		return forward(dirYaw);
	}
}
