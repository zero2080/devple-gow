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
 *   공중 정면 / 지상 앞으로 걸음 (0x1C7DA8) + 벽 질의 (0x1E47E0, 마스크 0x1008001C) → 면으로 고름 (0x1C7280):
 *     Climbable → WALL_CLIMB,  Ladder → LADDER,  Narrow·NarrowNoBB → WALL_WALK 벽 보고,  BackPress → WALL_WALK 등 대고 (착지 프레임 등 뒤 질의도)
 *   잡기 점 (발 + 키) 32 안의 줄 (공중, 또는 지상 + ×)  → ROPE (RopeSim) / ROPE_TRAVERSE / ZIPLINE (줄 종류 = SCR_RopeSkinned)
 *   깊은 Water (공중에서 떨어짐 / 걸어 들어감) → SWIM (TREAD 떠 있기 / SINK 가라앉음 / DIVE 잠수, 38 §11),  Narrow 바닥 → 균형 걷기,  Death → 사망 통지
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

		/** 영웅이 줄을 잡음 (원작 0x1C4EF8: 0x218200 · 0x2183B0 뒤 이벤트 0x41B = 1051, 인자 0, 보낸 이 = 영웅) [확실]. */
		default void ropeGrabbed(NavRope r) {
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
	/** 줄 잡기 (0x1C4FC0: 0x41B, 인자 0) — ATHN03C RopeAttachGroup attachRope 가 받아 메시지 591 "줄을 앞뒤로 흔들려면 ◎…" 한 번. */
	public static final int EVENT_ROPE_GRAB = 0x41B;
	/** 좁은 길 지상 속도 배율 Sold+0x230 = 1 (0x1EAFB0) [확실: 값 / 추정: +0x2D8 비트 4 = 좁은 길]. */
	public static final float BALANCE_SPEED = K.get(NavTuning.NARROW_SCALE);
	/** 좁은 바닥 질의 (0x1E5A70) 선분 거름 0x30042 = Water · Death · AIBlock · ClimbGuide [확실: 0x1E5BEC]. NoPlayerCollision 은 거르지 않는다. */
	public static final long NARROW_PROBE_EXCLUDE = SurfaceAttr.fromRecord(0x30042, 0, 0);
	/** 좁은 바닥 질의 바닥 조건 법선 · 위 &gt; 0.1 (0x1E5C84) [확실]. */
	static final float NARROW_FLOOR_NY = 0.1f;
	/** 좁은 바닥 질의 최소 깊이 3.2 (d = max(이동, 3.2), 0x1E5AE0) [확실]. */
	public static final float NARROW_REACH = 3.2f;
	/** 평균대 붙잡기 선분: 옛 발 − 1.6 (내비 − (바닥 6.4 + 1.6), 0x1BFF60 – 0x1BFF74) 에서 수평 16 (0x1BFF94) [확실]. */
	public static final float CATCH_DROP = 1.6f, CATCH_REACH = 16f;
	/** 평균대 매달림 시간 Sold+0x22C 1 초 (0x1C7678 – 0x1C76F4) [확실]. */
	public static final float BALANCE_HANG_TIME = K.get(NavTuning.BALANCE_HANG);
	/**
	 * 평균대 매달림 올라서기 끝 자리 (매달린 자리 → 벽 쪽): R_HERO0 balanceBeamPullUp/PullUp2 zeroJoint z 9.3 → −3.8 = 13.1,
	 * wallPullup 9.1 → −11.5 = 20.6 [확실: 애니 데이터] (원작은 애니 구동 +0x2D8 0x800000 으로 루트 이동).
	 */
	public static final float BALANCE_PULLUP_FORWARD = 13.1f, WALL_PULLUP_FORWARD = 20.6f;
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
	 * 2 × 남은 시간 배로 매달림 힘 (0x218470) 에 준다 — 창은 잡을 때 한 번 (0x1C5D80), 그 뒤는 흔들기 버튼을 누르는 동안 F (방향·부호 = {@link #swingForce}) [확실].
	 */
	public static final float SWING_FORCE = K.get(NavTuning.ROPE_SWING_FORCE), SWING_WINDOW = RopeRide.SWING_WINDOW;
	/** 점프 수직 초속 Sold+0xC4 15.25 → 244 (0x1E9E88, 정점 = 244² / 1600 ≈ 37 유닛) [확실]. */
	public static final float JUMP = K.units(NavTuning.JUMP);
	/** 2단 점프: 수직 속도 = +0xC8 → 244, 수직 속도가 (+0xD0 −1600, +0xCC 320) 안일 때만 (0x1EA130) [확실]. */
	public static final float DOUBLE_JUMP = K.units(NavTuning.DOUBLE_JUMP), DOUBLE_MAX_VY = K.units(NavTuning.DOUBLE_MAX_VY),
			DOUBLE_MIN_VY = K.units(NavTuning.DOUBLE_MIN_VY);
	/** 공중 수평: 목표 Sold+0xEC 7.5 → 120 × 스틱, 가속 +0xD8 → 120 /s², 회전 프레임당 +0xE4 0.05 (0x1EB650) [확실: 값 / 추정: 회전]. */
	public static final float AIR_SPEED = K.units(NavTuning.AIR_SPEED), AIR_ACCEL = K.units(NavTuning.AIR_ACCEL), AIR_TURN = K.get(NavTuning.AIR_TURN);
	/** 좁은 턱 벽 따라 걷기 Sold+0x168 1.25 → 20 (0x1C8CBC, 스틱 크기 무관) [확실]. */
	public static final float LEDGE_WALK_SPEED = K.units(NavTuning.LEDGE_WALK);
	/** 벽 옆 끝 점프: 앞 Sold+0x114 10 → 160, 위 +0x120 12 → 192 (0x1E9CD0) [확실]. */
	public static final float EDGE_JUMP_OUT = K.units(NavTuning.EDGE_JUMP_OUT), EDGE_JUMP_UP = K.units(NavTuning.EDGE_JUMP_UP);
	/** 벽에서 등 뒤 점프: 벽 반대 Sold+0x11C 10 → 160, 위 +0x118 17 → 272 (0x1E9D9C) [확실]. */
	public static final float BACK_JUMP_OUT = K.units(NavTuning.WALL_AWAY), BACK_JUMP_UP = K.units(NavTuning.BACK_JUMP_UP);

	private final NavBody body;
	private final NavWorld world;
	private final NavAnims anims;
	private Interactables items = new Interactables();
	/** 다른 슬롯 레벨 R1/R2 오브젝트 — 원작 잡기 질의 0x1C3F18 은 슬롯 구분 없는 충돌 세계 질의 (SceneNav 가 채움, 43 §18). */
	private Interactables neighbourItems = new Interactables();
	private Listener listener = new Listener() {
	};
	private NavState state = NavState.GROUND;
	private boolean balancing;
	private float stateTime, cooldown;
	private final Vector3f wallN = new Vector3f();
	/** 지난 틱 공중이었음 (착지 프레임 = 원작 +0x2D8 0x80), 등 대고 걷기 (+0x174 0x200000), 벽 옆 끝 (+0x2D8 0x100 왼쪽 −1 / 0x200 오른쪽 +1). */
	private boolean airborne, wallBack;
	private int wallEdge;
	/**
	 * 벽 옆 끝 점프 뒤 공중 (+0x2D8 0x100/0x200 이 남은 채 공중, 0x1E9C9C): 뛴 쪽 (−1/+1, 0 = 아님), 공중 시간 (+0x2B8, 0x1C8180),
	 * 옆 벽 잡기 질의 0x1E53F0 의 마지막 결과 (시험·디버그용).
	 */
	private int edgeJumpSide;
	private float edgeAirTime;
	private final Vector3f edgeJumpDir = new Vector3f(), edgeWallN = new Vector3f();
	/** 매단 줄 위 몸 방향 번호 ({@link NavRope#backDirs()}) 와 몸 방향 보간 가중치 (+0x150, 0.1 → ×1.1111/프레임 → 1, 0x1E0DC0). */
	private int ropeDir;
	private float turnBlend = 1;
	private float ledgeY;
	private NavRope rope;
	private float ropeT, zipSpeed, zipDir;
	/** 매단 줄 시뮬레이션 (줄마다 하나, 매 틱 진행) 과 흔들기 힘 창. */
	/** 잡은 뒤 흔들기 창 (0x29DBF4), 저역 통과한 흔들기 힘 크기 (+0x298), 잡을 때 몸 오른쪽·뒤 (0x303FC0 / 0x303FB0). */
	/** 매단 줄 흔들기 상태 (영웅·적 공용 0x1C6BC8, {@link RopeRide}). */
	private final RopeRide ride = new RopeRide();
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
	/** 밀기: 잡은 점 (손잡이·옆면) 의 높이 − 상자 위치 높이 (없으면 NaN) — 붙은 동안 영웅 높이 차 판정 (0x1C4674). */
	private float grabHeight = Float.NaN;
	/** 상황 동작 영웅 애니 단계 ({@link #PH_GRAB} → {@link #PH_MAIN} → {@link #PH_FINISH}) 와 그 단계 시간, TAndF 마지막 누름 뒤 시간. */
	private int phase;
	private float phaseTime, sincePress;
	private boolean doneNotified;
	/** 매달리기 타이머 (+0x2A4) 와 도는 중 (+0x2D8 0x400). */
	private float hangTimer = Float.MAX_VALUE;
	private boolean hangTiming;
	/**
	 * 평균대: 좁은 바닥 상태 (+0x174 0x80000 | 0x2000, 0x1BFA70), 마지막 좁은 바닥 질의 비트 (0x2C8BD8) · 바닥 면 (+0x234),
	 * 평균대 매달림 (+0x2D8 0x40) · 매달린 턱 윗면, 붙잡을 때 면 법선 · 오른쪽 &gt; 0 (+0x2D8 0x8000000 → 올라서기 R).
	 */
	private boolean narrowGround, narrowHang, catchRight;
	private int narrowFlags;
	private long narrowSurface;
	private LevelCollision.Tri hangTop;
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

	public void setNeighbourItems(Interactables it) {
		neighbourItems = it != null ? it : new Interactables();
	}

	public Interactables items() {
		return items;
	}

	public NavWorld world() {
		return world;
	}

	/** AIR 상태가 뛰어서(벽 등 점프·줄 점프·물 점프) 생겼는지 (원작 +0x174 0x4), 아니면 놓아서 떨어짐 (0x1E9030 → 0x8). */
	public boolean airJump() {
		return state == NavState.AIR && airJump;
	}

	private boolean airJump;

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

	private Interactables.Item promptItem;

	/** R2 로 잡힐 가까운 오브젝트 (지상·붙어 있지 않을 때만, 아니면 null) — HUD 안내 슬롯 0. */
	public Interactables.Item promptItem() {
		return state == NavState.GROUND ? promptItem : null;
	}

	/**
	 * 이번 틱은 사용 질의를 하지 않는다 (전투 기술 중 — HeroRig 가 내비게이터를 돌리지 않을 때). 원작 0x1DC3F8 은 영웅이 자유 상태가 아니면
	 * (0x1DC42C–0x1DC44C: 대기 0x29DC70 = 2) 질의 0x1C3F18 을 건너뛰고 끝에서 슬롯 0 을 −1 로 지운다 (0x1DC648–0x1DC654) [확실] — 예전 엔진은
	 * 마지막 질의 결과가 남아 싸우는 내내 "R2" 가 떠 있었다 (DEST03 세이렌 싸움, 뿔피리 옆에서 시작, 43 §32).
	 */
	public void clearPrompt() {
		promptItem = null;
	}

	/** 강제로 지상 상태로 (리스폰·Goto). */
	public void reset() {
		if (item != null && (state == NavState.PUSH || state == NavState.INTERACT)) {
			listener.pushDetach(item);
			if (state == NavState.INTERACT && (!item.done || item.kind == Interactables.Kind.LIFT && item.slide && !item.slideSettled())) {
				released.add(item);
			}
		}
		releaseRope();
		state = body.grounded() ? NavState.GROUND : NavState.AIR;
		rope = null;
		item = null;
		charging = false;
		hasLast = false;
		narrowHang = false;
		narrowGround = false;
		cooldown = 0.3f;
	}

	/**
	 * Warp 도착 (Warp_Jump 0x1B2E08 → 영웅 SetMatrix 0x1E79D0): 줄 위였으면 놓기 0x1C4FF8(1) = 떨어짐 (+0x174 0x8), 상태는 그대로 이어진다.
	 * 놓은 줄 제외 (+0x2A8 → 0x3067E8) 도 +0x290 = 0 이라 없음 → 다음 프레임 공중 줄 잡기 (0x1C5C20) 가 도착점 줄을 바로 잡는다 [확실].
	 * 리스폰 대기 (0.3 초, 엔진 [근사]) 를 없앤다.
	 */
	public void warpArrived() {
		cooldown = 0;
	}

	/**
	 * 세이브·체크포인트 되읽기 (Load_PlayerBlock 0x1DC130: 행렬을 놓고 이동 상태 +0x174 = 스냅숏 +0x40 — 벽 0x20 이면 벽에 붙은 채) [확실]: 스냅숏 때
	 * 벽·덩굴·사다리에 붙어 있었으면 지금 자리·방향에서 벽 질의 (0x1E47E0, {@link #probeWall}) 로 곧바로 다시 붙인다 [근사: 붙은 상태의 세부 (애니 시각 ·
	 * 벽 끝 플래그) 는 담지 않는다]. 질의가 빗나가면 그대로 (공중 → 다음 틱의 공중 잡기).
	 *
	 * @return 붙었으면 true
	 */
	public boolean reattachWall() {
		Vector3f p = body.position();
		WallProbe w = probeWall(p, forward(body.yaw()), null);
		if (w == null) {
			return false;
		}
		p.y = w.feet().y;
		enterWall(w, false);
		cooldown = 0;
		return true;
	}

	// ---- 매 틱 ----

	/** @return true = 이번 틱 이동을 처리함 (기본 이동·중력 생략) */
	public boolean update(Input in, float camYaw, float dt) {
		if (DEBUG_PUSH && state != dbgState) {
			System.out.printf("[내비 상태] %s → %s 위치 %s 땅 %s%n", dbgState, state, body.position(), body.grounded());
			dbgState = state;
		}
		stateTime += dt;
		cooldown -= dt;
		if (state != NavState.AIR) {
			edgeJumpSide = 0;
		}
		boolean justLanded = body.grounded() && airborne;
		airborne = !body.grounded() && !active();
		prevFeet = hasLast ? new Vector3f(lastFeet) : null;
		lastFeet.set(body.position());
		hasLast = true;
		if (state == NavState.WALL_CLIMB || state == NavState.LADDER || state == NavState.SWIM || body.grounded() && !active()) {
			resetHangTimer();
		}
		rewindReleased(dt);
		slideBoxes(dt);
		world.updateRopes(dt);
		if (state == NavState.ROPE || state == NavState.ROPE_TRAVERSE || state == NavState.ZIPLINE) {
			world.tickRopeClock(dt); // 0x1C54B4: 플레이어 (+0x174 비트 31) 의 줄 갱신만 0x29DBF8 += dt [확실]
		}
		switch (state) {
			case WALL_CLIMB, LADDER -> {
				climb(in, dt);
				return true;
			}
			case WALL_WALK -> {
				wallWalk(in, camYaw, dt);
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
			divingIn = noDiveFall = false;
			if ((a & SurfaceAttr.WATER.bit()) != 0 && deepWater(p, f.y())) {
				enterWater(f, 0);
				return true;
			}
			// 근접 사용 안내 (원작 슬롯 0, 매 프레임 0x1DC3F8 — 붙어 있지 않을 때, R2 로 잡힐 대상과 같은 질의)
			Object[] g = items.grabbable(p, fwd, USE_RADIUS); // 손잡이 관절 판정 (0x1C3918) — 손잡이 쪽을 보고 손잡이 앞에 있어야
			if (g == null) {
				g = neighbourItems.grabbable(p, fwd, USE_RADIUS); // 다른 슬롯 레벨 것 (ATHN04C 에서 04A 창살 LiftDoor13)
			}
			Interactables.Item near = g != null ? (Interactables.Item) g[0] : null;
			promptItem = near != null && !released.contains(near) && near.usable() ? near : null;
			if (in.pressed(PadButton.USE)) {
				Interactables.Item it = near;
				handle = g != null ? (float[]) g[1] : null;
				// 놓은 뒤 되감기는 중인 오브젝트는 0 으로 돌아갈 때까지 잡히지 않는다 (원작: 잡기 클래스 비트 0x400000 / 0x30000 을 끄고 0 에서 다시 켬,
				// CSM 0x2160B4 → 0x216254, 레버 0x21287C → 0x21290C) [확실]
				if (it != null && !released.contains(it)) {
					startItem(it);
					return true;
				}
			}
		}
		if (!body.grounded()) {
			// 물 들어가기 (0x1C9BB8 공중 경로, 수직 속도 ≤ 0): 이번 틱에 발이 깊은 물 수면에 닿으면 (엔진 충돌은 수면도 바닥) 물 상태로
			if (body.vy() <= 0) {
				NavWorld.Floor wf = world.floor(p.x, p.y + 1, p.z, -body.vy() * dt + 2);
				if (wf != null && wf.has(SurfaceAttr.WATER) && deepWater(p, wf.y())) {
					enterWater(wf, body.vy());
					return true;
				}
			}
			airDive(p);
		}
		if (body.grounded()) {
			edgeJumpSide = 0;
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
			// 턱 한쪽만 맞음 (반환 8, 0x1E4528) → 공중 갱신 0x1C83F0: 벽 수평 법선 · 스틱 (없으면 정면) &lt; 0 이면 몸을 벽 쪽으로 돌림 [확실]
			Vector3f cn = probeLedgeCorner(p, prevFeet, fwd);
			if (cn != null && cn.dot(mag > 0.2f ? stick : fwd) < 0) {
				body.setYaw(yawOf(new Vector3f(cn).negate()));
			}
		}
		// 벽 옆 끝 점프 뒤 공중 (+0x2D8 0x300 이 남음): 일반 벽 잡기 없음 (0x1C7D20), 공중 0.2 초 (+0x2B8, 0x1C8180) 가 지나면 매 프레임
		// 옆 벽 질의 0x1E53F0 → 맞으면 몸을 새 벽 법선으로 돌려 벽 질의 → 벽 오르기 (ANIClimbCL/CR) [확실]
		if (!body.grounded() && edgeJumpSide != 0) {
			edgeAirTime += dt;
			if (edgeAirTime > EDGE_CATCH_TIME) {
				WallProbe w = sideCatch(p);
				if (w != null) {
					int side = edgeJumpSide;
					edgeJumpSide = 0;
					body.position().set(w.feet());
					enterWall(w, false);
					if (state == NavState.WALL_CLIMB) {
						body.play(anims.get(side < 0 ? "ANIClimbCL" : "ANIClimbCR"), true);
					}
					return true;
				}
			}
			return false;
		}
		// 벽: 정면 (공중, 0x1C7D40) 또는 스틱 방향 (지상 [근사: 원작 지상 진입 0x1C7E1C 조건 +0x2D8 0x80 / 국소 속도 미해석])
		// 벽: 공중 = 정면 (0x1C7D40), 지상 = 앞으로 움직이면 (지역 앞 속도 &gt; 0) 그 방향 (0x1C7DE8 → 0x1E47E0 a3 = 0, 마스크 0x1008001C) [확실].
		// 붙는 상태는 첫 적중 면이 고른다 (0x1C7280): BackPress → 등 대고 걷기, Narrow·NarrowNoBB → 벽 보고 걷기, Ladder → 사다리, 그 밖 → 벽 오르기
		boolean moving = body.grounded() && mag > 0.5f;
		Vector3f dir = moving ? stick : fwd;
		if (!body.grounded() || moving) {
			WallProbe w = probeWall(p, dir, null);
			if (w != null && w.n().dot(dir) < -0.6f) {
				enterWall(w, w.tri().has(SurfaceAttr.BACK_PRESS));
				return true;
			}
		}
		// 착지한 프레임 (+0x2D8 0x80, 0x1CB380): 앞이 비면 등 뒤로 다시 질의 (0x1C7ED0 t0 = 1) [확실] — 엔진은 BackPress 벽만 [근사: 원작은 마스크 전체]
		if (justLanded) {
			WallProbe w = probeWall(p, new Vector3f(fwd).negate(), null);
			if (w != null && w.tri().has(SurfaceAttr.BACK_PRESS)) {
				enterWall(w, true);
				return true;
			}
		} else if (moving) {
			// 위에서 아래 벽/사다리로 내려가 붙기 (0x1C7F70 → 0x1E5638, 지상·앞으로 움직임·앞 벽 질의 실패·착지 프레임 아님) [확실]
			WallProbe w = probeClimbDown(p, dir);
			if (w != null) {
				body.position().set(w.feet());
				enterClimb(w, w.tri().has(SurfaceAttr.LADDER));
				return true;
			}
		}
		return false;
	}

	/** 벽 옆 끝 점프 뒤 옆 벽 질의까지의 공중 시간 0.2 초 (0x1C8164) [확실]. */
	public static final float EDGE_CATCH_TIME = 0.2f;
	/** 옆 벽 질의 법선 조건 |n · 새 뒤| &lt; 0.25 (0x1E55BC) [확실]. */
	public static final float SIDE_CATCH_PERP = 0.25f;

	/**
	 * 옆 벽 질의 (원작 0x1E53F0, 벽 옆 끝 점프 뒤 공중) [확실: 디스어셈블].
	 * <pre>
	 * 점프로 몸 축이 90° 돈 뒤: 새 뒤 = −뛴 방향 s, 새 +0x50 = −옛 벽 법선 n.
	 * o (내비 위치) → o + 새 뒤 × 9.6 (+0x234 × 16) ± 새 +0x50 × 14.4 (폭 × 0.75) = o − s × 9.6 − n × 14.4 (0x100·0x200 모두 같은 식),
	 * 그리고 같은 선분을 위 × 27.8 (+0x140 − 1) 에서. 처음 맞은 면이 Climbable·Ladder (0x18) 이고 |법선 · 새 뒤| &lt; 0.25 면 반환 비트 8,
	 * 법선 → +0x220. 부르는 쪽 (0x1C81B4): 몸 뒤 = 그 법선 (수평), 벽 질의 0x1E47E0 (a3 = 1) 이 맞으면 0x300 끄고 0x1C7280 (+0x2D8 0x800 / 0x1000
	 * → ANIClimbCL / CR), 아니면 몸 축을 되돌림.
	 * </pre>
	 * 즉 벽 옆 끝에서 옆으로 뛰어 <b>같은 쪽을 향한 다음 벽 (틈 건너편)</b> 을 잡는다 — 바깥 모서리 돌기가 아니다 (전 문서 §13.3 정정).
	 */
	WallProbe sideCatch(Vector3f feet) {
		Vector3f o = new Vector3f(feet).add(0, FOOT, 0);
		Vector3f s = new Vector3f(edgeJumpDir.x, 0, edgeJumpDir.z).normalize();
		for (int k = 0; k < 2; k++) {
			Vector3f a = new Vector3f(o).add(0, k == 0 ? 0 : REACH - 1, 0);
			Vector3f b = new Vector3f(a).fma(-WALL_OFF, s).fma(-WALL_PROBE, edgeWallN);
			LevelCollision.Hit h = wseg(a, b);
			if (h == null || !(h.tri().has(SurfaceAttr.CLIMBABLE) || h.tri().has(SurfaceAttr.LADDER))) {
				continue;
			}
			Vector3f n = facing(h, a);
			if (Math.abs(n.dot(s)) >= SIDE_CATCH_PERP) {
				continue;
			}
			Vector3f nh = new Vector3f(n.x, 0, n.z);
			if (nh.lengthSquared() < 1e-6f) {
				return null;
			}
			nh.normalize();
			return probeWall(feet, nh.negate(), null);
		}
		return null;
	}

	/**
	 * 위에서 아래 벽/사다리로 내려가 붙기 (원작 0x1E5638, 지상) [확실: 디스어셈블].
	 * <pre>
	 * o = 내비 위치. a = o − 위 × 35.2 (+0x120.y) + 정면 × 35.2 → b = a + 뒤 × (0.5 × 폭 + 키 = 44.8) = o − 위 × 35.2 − 정면 × 9.6.
	 * 처음 맞은 면이 Climbable·Ladder (0x18) 면 P = 적중점 + 법선 × 9.6 (0.5 × 깊이), o → P 선분이 막히지 않으면 위치 = P, +0x220 = 법선, 반환 9 → 0x1C7280.
	 * </pre>
	 * 엔진 충돌은 양면이라 앞면 (법선 · (b − a) &lt; 0) 만 받는다 [근사].
	 */
	WallProbe probeClimbDown(Vector3f feet, Vector3f fwd) {
		Vector3f f = new Vector3f(fwd.x, 0, fwd.z);
		if (f.lengthSquared() < 1e-6f) {
			return null;
		}
		f.normalize();
		Vector3f o = new Vector3f(feet).add(0, FOOT, 0);
		Vector3f a = new Vector3f(o).sub(0, HANG, 0).fma(HANG, f);
		Vector3f b = new Vector3f(o).sub(0, HANG, 0).fma(-WALL_OFF, f);
		LevelCollision.Hit h = wseg(a, b);
		if (h == null || !(h.tri().has(SurfaceAttr.CLIMBABLE) || h.tri().has(SurfaceAttr.LADDER))) {
			return null;
		}
		Vector3f n = new Vector3f(h.tri().n());
		if (n.dot(f) <= 0) {
			return null; // 뒷면 (선분은 앞에서 뒤로 간다: 앞면 법선은 정면 쪽)
		}
		Vector3f pos = new Vector3f(h.point()).fma(WALL_OFF, n);
		if (wseg(o, pos) != null) {
			return null;
		}
		Vector3f nh = new Vector3f(n.x, 0, n.z);
		if (nh.lengthSquared() < 1e-6f) {
			return null;
		}
		return new WallProbe(pos.sub(0, FOOT, 0), nh.normalize(), h.tri());
	}

	// ---- 벽·턱 판정 (원작 0x1E47E0 / 0x1E3A80) ----

	/** 벽 질의 선분 (0x1E47E0 · 0x1E53F0 · 0x1E5638 가 둔 제외 마스크 0x9030042 | +0x258, {@link NavWorld#WALL_PROBE_EXCLUDE}) — GeneralGuide·ClimbGuide 막이를 지난다. */
	private LevelCollision.Hit wseg(Vector3f a, Vector3f b) {
		return world.segment(a, b, NavWorld.WALL_PROBE_EXCLUDE);
	}

	/** 턱 질의 선분 (0x1E3A80 제외 마스크 0x9030042 | (+0x258 &amp; 0x4300000), {@link NavWorld#LEDGE_PROBE_EXCLUDE}). */
	private LevelCollision.Hit lseg(Vector3f a, Vector3f b) {
		return world.segment(a, b, NavWorld.LEDGE_PROBE_EXCLUDE);
	}

	/**
	 * 턱 윗면 수직 선분 (0x1E3A80 의 손 높이 ± d): 한쪽 면 ({@link NavWorld#frontSegment}, 원작 시트 면 검사 0x16BDE0) — 위에서 내려오는 선분은
	 * 아래를 보는 천장 면을 지나간다. 양면이던 때는 2단 점프한 손이 ATHN01A 갑판 밑면 (−64, 법선 −1) 을 턱으로 잡았다 (38 §22) [확실].
	 */
	private LevelCollision.Hit ledgeTop(Vector3f a, Vector3f b) {
		return world.frontSegment(a, b, NavWorld.LEDGE_PROBE_EXCLUDE);
	}

	/** 벽 선분 질의 결과: 붙을 발 위치, 수평 법선 (영웅 쪽), 첫 적중 면 (+0x238 표면 → 사다리/벽). */
	record WallProbe(Vector3f feet, Vector3f n, LevelCollision.Tri tri) {
	}

	/** 턱 판정 결과: 턱 높이·턱 점, 매달릴 발 위치, 벽 수평 법선, 턱 면 (+0x234 → WallHangTimer), 벽 면 (+0x238 → 매달림 애니 0x1C75F8). */
	record Ledge(float y, Vector3f point, Vector3f feet, Vector3f n, LevelCollision.Tri top, LevelCollision.Tri wall) {
	}

	/** 붙는 벽 마스크: 원작 bitfield0 &amp; 0x1008001C (Narrow·Climbable·Ladder·BackPress·NarrowNoBB, 0x1E4988) [확실]. */
	static boolean climbSurface(LevelCollision.Tri t) {
		return t.has(SurfaceAttr.CLIMBABLE) || t.has(SurfaceAttr.LADDER) || narrowSurface(t);
	}

	/** 좁은 턱 벽 마스크 0x10080004 (Narrow·BackPress·NarrowNoBB, 0x1E4CF4 바닥 맞춤 / 0x1C7398 상태 고르기) [확실]. */
	static boolean narrowSurface(LevelCollision.Tri t) {
		return t.has(SurfaceAttr.NARROW) || t.has(SurfaceAttr.NARROW_NO_BB) || t.has(SurfaceAttr.BACK_PRESS);
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
		LevelCollision.Hit h1 = wseg(o, new Vector3f(o).fma(-WALL_SPREAD, right).fma(WALL_PROBE, f));
		LevelCollision.Hit h2 = wseg(o, new Vector3f(o).fma(WALL_SPREAD, right).fma(WALL_PROBE, f));
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
		LevelCollision.Hit h3 = wseg(s, new Vector3f(s).fma(-WALL_PROBE, n));
		if (h3 == null || !climbSurface(h3.tri()) || facing(h3, s).dot(n) <= WALL_HEAD_AGREE) {
			return null;
		}
		Vector3f np = plane(h1.point(), h2.point(), h3.point(), o, n);
		Vector3f pos = new Vector3f(h1.point()).add(h2.point()).mul(0.5f).fma(WALL_OFF, np);
		Vector3f nh = new Vector3f(np.x, 0, np.z);
		if (nh.lengthSquared() < 1e-6f) {
			return null;
		}
		nh.normalize();
		if (narrowSurface(h1.tri())) {
			// 좁은 턱 (0x1E4D08): 새 위치에서 아래로 (+0x130.y 6.4 + max(이동, 3.2)) · 벽 쪽으로 반 깊이 선분이 바닥 (법선 y &gt; 0.1) 에 맞아야 하고,
			// 발 = 그 바닥 (내비 위치 = 바닥 + 6.4) [확실]. 못 맞으면 실패 (턱 끝 → 떨어짐). 엔진 이동량은 틱당 3.2 미만이라 창 = 3.2
			LevelCollision.Hit fl = wseg(pos, new Vector3f(pos).sub(0, FOOT + LEDGE_WINDOW, 0).fma(-WALL_OFF, nh));
			if (fl == null || fl.tri().n().y <= 0.1f) {
				return null;
			}
			pos.y = fl.point().y + FOOT;
		}
		return new WallProbe(pos.sub(0, FOOT, 0), nh, h1.tri());
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
	 * 첫 적중 윗면이 Narrow·NarrowNoBB 면 좁은 턱 경로 0x1E3EE8 ({@link #narrowLedge(Vector3f, Vector3f, float, Vector3f, boolean, LevelCollision.Tri)}), 한쪽만 맞으면 {@link #probeLedgeCorner}.
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
			LevelCollision.Hit h = ledgeTop(s, new Vector3f(s).sub(0, 2 * d, 0));
			if (h == null || h.tri().n().y <= LEDGE_NY || h.tri().has(SurfaceAttr.SLIDE)
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
		if (lseg(new Vector3f(o).add(0, REACH + d, 0), new Vector3f(ledge).add(0, 1, 0)) != null) {
			return null;
		}
		if (topTri != null && narrowLedge(topTri)) {
			return narrowLedge(feet, ledge, d, f, keepN != null, topTri);
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
		LevelCollision.Hit h3 = lseg(s3, new Vector3f(s3).fma(BODY_W, f));
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
		return new Ledge(ledge.y, ledge, pos, np, topTri, wall[0].tri());
	}

	/** 좁은 턱 윗면: 첫 수직 선분 적중 면 bitfield0 &amp; 0x10000004 (Narrow·NarrowNoBB, 0x1E3ED4) [확실]. BackPress 는 들지 않는다. */
	static boolean narrowLedge(LevelCollision.Tri t) {
		return t.has(SurfaceAttr.NARROW) || t.has(SurfaceAttr.NARROW_NO_BB);
	}

	/**
	 * 좁은 턱 경로 (원작 0x1E3A80 의 0x1E3EE8 – 0x1E4088) [확실: 디스어셈블]. 일반 턱의 벽 두 선분 + 머리 선분 (턱 아래 28.8 + d) 대신,
	 * <pre>
	 * a = 턱 − 위 × (d + 2) − 정면 × 0.5 깊이 (9.6) → a + 정면 × 깊이 (19.2) 선분 하나가 (아무 면이든) 맞아야 한다 — 못 맞으면 실패 (일반 경로로 넘어가지 않음).
	 * 맞으면 턱 면 → +0x234, 법선 = 그 면 법선의 수평 성분 정규화 → +0x220, 벽 면 → +0x238.
	 * 붙어 있지 않으면 (+0x174 &amp; 0x30 = 0: 공중) 위치 = 적중점 + 법선 × 0.5 깊이 (9.6), 높이 = 턱 − (키 − 바닥) (발 = 턱 − 35.2).
	 * 이미 매달림·벽 (0x10 / 0x20) 이면 위치를 고치지 않는다 (후보 위치 그대로 — 좁은 턱을 따라 옆으로 갈 때 높이·벽 거리 맞춤 없음).
	 * </pre>
	 * 즉 평균대·좁은 기둥 윗면 (Narrow / NarrowNoBB) 은 아래 벽이 손 높이 근처 (턱 − d − 2) 에만 있어도 매달린다 —
	 * 벽이 몸 높이까지 내려오지 않는 얇은 판 (ATHN06B·PAND03E 평균대, ARENA08 NarrowNoBB 기둥) 에서 일반 경로는 벽 선분이 빗나간다.
	 * 좁은 턱 벽 따라 걷기 (§12, 벽 질의 0x1E4D08) 는 벽 면이 Narrow 인 경우이고, 이 경로는 <b>윗면</b> 이 Narrow 인 경우다.
	 */
	private Ledge narrowLedge(Vector3f feet, Vector3f ledge, float d, Vector3f f, boolean attached, LevelCollision.Tri top) {
		Vector3f a = new Vector3f(ledge).sub(0, d + 2, 0).fma(-BODY_W / 2, f);
		LevelCollision.Hit w = lseg(a, new Vector3f(a).fma(BODY_W, f));
		if (w == null) {
			return null;
		}
		Vector3f n = facing(w, a);
		n.y = 0;
		if (n.lengthSquared() < 1e-6f) {
			return null;
		}
		n.normalize();
		Vector3f pos;
		if (attached) {
			pos = new Vector3f(feet);
		} else {
			pos = new Vector3f(w.point()).fma(WALL_OFF, n);
			pos.y = ledge.y - HANG;
		}
		return new Ledge(ledge.y, ledge, pos, n, top, w.tri());
	}

	/**
	 * 턱 한쪽만 맞음 (원작 0x1E3A80 의 0x1E4528 경로, 반환 8) [확실: 디스어셈블]: 두 수직 선분 중 하나만 바닥 (조건은 일반 턱과 같음) 에 맞으면
	 * 그 적중점 + (0, 1, 0) ← 머리 (o + 28.8 + d) 선분이 막히지 않고, o 에서 그 선분 아래 끝의 (x, z) 높이 o.y 점까지 수평 선분이 맞아야 한다.
	 * 맞은 면 법선 (y = 0, 정규화) → +0x220, 표면 → +0x238. 매달리지는 않고, 공중 갱신이 몸을 벽 쪽으로 돌린다 (0x1C83F0).
	 *
	 * @return 벽 수평 법선 (영웅 쪽), 없으면 null
	 */
	Vector3f probeLedgeCorner(Vector3f feet, Vector3f prev, Vector3f fwd) {
		Vector3f f = new Vector3f(fwd.x, 0, fwd.z).normalize();
		Vector3f right = rightOf(f);
		Vector3f o = new Vector3f(feet).add(0, FOOT, 0);
		float d = Math.max(prev != null ? feet.distance(prev) : 0, LEDGE_WINDOW);
		Vector3f c = new Vector3f(o).fma(LEDGE_AHEAD + BODY_W / 2, f);
		Vector3f hit = null, bottom = null;
		int hits = 0;
		for (int i = 0; i < 2; i++) {
			Vector3f s = new Vector3f(c).fma(i == 0 ? -BODY_W / 4 : BODY_W / 4, right).add(0, REACH + d, 0);
			Vector3f e = new Vector3f(s).sub(0, 2 * d, 0);
			LevelCollision.Hit h = ledgeTop(s, e);
			if (h != null && h.tri().n().y > LEDGE_NY && !h.tri().has(SurfaceAttr.SLIDE) && !h.tri().has(SurfaceAttr.NO_WALL_HANG)) {
				hits++;
				hit = h.point();
				bottom = e;
			}
		}
		if (hits != 1) {
			return null;
		}
		if (lseg(new Vector3f(o).add(0, REACH + d, 0), new Vector3f(hit).add(0, 1, 0)) != null) {
			return null;
		}
		LevelCollision.Hit w = lseg(o, new Vector3f(bottom.x, o.y, bottom.z));
		if (w == null) {
			return null;
		}
		Vector3f n = facing(w, o);
		n.y = 0;
		return n.lengthSquared() < 1e-6f ? null : n.normalize();
	}

	/** 턱 아래 벽 두 선분 (b → b + 정면 × 19.2 ± 오른쪽 × 9.6). 둘 다 맞아야 한다. */
	private LevelCollision.Hit[] wallPair(Vector3f b, Vector3f f, Vector3f right) {
		Vector3f e = new Vector3f(b).fma(BODY_W, f);
		LevelCollision.Hit a = lseg(b, new Vector3f(e).fma(BODY_W / 2, right));
		if (a == null) {
			return null;
		}
		LevelCollision.Hit c = lseg(b, new Vector3f(e).fma(-BODY_W / 2, right));
		return c == null ? null : new LevelCollision.Hit[] {a, c};
	}

	// ---- 벽 오르기 / 사다리 ----

	/** 벽 상태 고르기 (원작 0x1C7280: 면 bitfield0 0x80000 → 0x200000, 0x10000004 → 0x100000, 0x10 → 사다리 0x400000, 그 밖 벽 오르기) [확실]. */
	private void enterWall(WallProbe w, boolean back) {
		LevelCollision.Tri t = w.tri();
		wallEdge = 0;
		if (t.has(SurfaceAttr.BACK_PRESS) || narrowSurface(t)) {
			enterWallWalk(w, t.has(SurfaceAttr.BACK_PRESS) || back);
		} else {
			enterClimb(w, t.has(SurfaceAttr.LADDER));
		}
	}

	private void enterClimb(WallProbe w, boolean ladder) {
		wallEdge = 0;
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
		if (in.pressed(PadButton.CROSS) && wallEdge != 0 && !ladder) {
			edgeJump();
			return;
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
		// 벽 옆 끝 (+0x2D8 0x100 / 0x200, 0x1C7A24): 막힌 쪽으로 미는 동안은 질의 없이 그 자리 [확실]
		if (!ladder && (wallEdge < 0 && vx < 0 || wallEdge > 0 && vx > 0)) {
			body.play(anims.get(wallEdge < 0 ? "ANIClimbAL" : "ANIClimbAR"), false);
			return;
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
		float candY = cand.y;
		wallBody(cand, right); // 벽 위 몸 질의 0x1E2100 → 0x1E25E0: ClimbGuide 막이·천장·바닥에 막힘
		if (vy > 0 && cand.y < candY - 1e-3f) {
			// 머리가 위 면에 막힘: 그 면이 몸 바로 위에 걸친 발판 (돛대 꼭대기) 이면 올라선다 — 아래 꼭대기 보조와 같은 조건 (엔진 보조 [근사])
			NavWorld.Floor above = world.floor(p.x, p.y + FOOT + HANG + 16, p.z, FOOT + HANG + 8);
			if (above != null && above.y() > p.y + 8) {
				p.y = above.y();
				land(ladder ? "ANILDis1" : "ANIWallPullup");
				return;
			}
		}
		WallProbe w = probeWall(cand, into, wallN);
		if (w != null) {
			wallN.set(w.n());
			wallFlags(w.tri());
			p.set(w.feet());
			faceWall();
			wallEdge = 0;
		} else if (!ladder && vx != 0 && (wallEdge = edgeSide(cand, into)) != 0) {
			// 한쪽 선분만 붙는 면에 맞음 (0x1E52D4, t1 = 1): 옛 위치로 되돌리고 3 (왼쪽 0x100) / 5 (오른쪽 0x200) → 0x1C7280 [확실]
			body.play(anims.get(wallEdge < 0 ? "ANIClimbAL" : "ANIClimbAR"), false);
			return;
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

	/** 벽 위 몸 질의 0x1E25E0 의 선분 10 개: 몸 오른쪽 축 × w 계수, 위 × 키/2 계수, 맞으면 세울 반환 비트 [확실: 0x1E26E8 – 0x1E2870]. */
	private static final float[][] WALL_BODY_SEGS = {
			{1, 1, 0x40}, {-1, 1, 0x100}, {0, 1, 0x40}, {1, -1, 0x40}, {-1, -1, 0x100}, {0, -1, 0x40},
			{1, 1 / 3f, 0x40}, {-1, 1 / 3f, 0x100}, {1, -1 / 3f, 0x40}, {-1, -1 / 3f, 0x100}};

	/**
	 * 벽 위 몸 질의 (0x1E2100 이 +0x174 0x20 이고 0x300000 이 아닐 때 부르는 0x1E25E0) [확실: 디스어셈블]: 몸 가운데
	 * c = 내비 위치 + 위 × (+0x140 − 키/2) = 발 + 키/2 에서 벽 평면 안의 선분 10 개 (± 반폭 × 오른쪽, ± 키/2 · 키/6 × 위, 위·아래) 를 차례로
	 * 0x11E190 (가장 가까운 것) 으로 쏘고, 맞을 때마다 c −= n · (n · (끝 − 충돌점)) — 끝점이 그 면 위에 오도록 민다 (0x1E1F28).
	 * 면 거름 = 0x1EA5F0 의 벽 마스크 ({@link NavWorld#bodyExclude}: <b>ClimbGuide 는 막고</b> CombatGuide·Water·Death·AIBlock·NoPlayerCollision 은 지나감).
	 * 앞뒤 성분 (0x50(sp)) 은 0 이라 오르는 벽 자체에는 닿지 않는다.
	 *
	 * @return 반환 비트 (0x40 = 위·아래·왼쪽 선분 — 원작 그대로, 0x100 = 오른쪽 축 반대쪽 선분)
	 */
	int wallBody(Vector3f feet, Vector3f right) {
		long ex = NavWorld.bodyExclude(true, false, true, false, 0, 0);
		float w = BODY_W * 0.5f, hh = ROPE_HAND * 0.5f;
		Vector3f c = new Vector3f(feet).add(0, hh, 0);
		Vector3f end = new Vector3f();
		int flags = 0;
		for (float[] s : WALL_BODY_SEGS) {
			end.set(right).mul(s[0] * w).add(c).add(0, s[1] * hh, 0);
			NavWorld.Contact h = world.contact(c, end, ex);
			if (h != null) {
				Vector3f n = h.normal();
				float d = n.dot(end.x - h.point().x, end.y - h.point().y, end.z - h.point().z);
				c.fma(-d, n);
				flags |= (int) s[2];
			}
		}
		feet.set(c).sub(0, hh, 0);
		return flags;
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

	/**
	 * 벽 옆 끝 판정 (0x1E52D4 – 0x1E53A8, 벽 오르기 중 t1 = 1): 두 선분 중 한쪽만 붙는 면 (Climbable·Ladder, 좁은 턱 면 제외 — 좁은 턱은 0 을 돌려 떨어짐) 에 맞으면
	 * 그 반대쪽이 막힌 쪽. 왼쪽 선분 (−오른쪽 × 8) 이 빗나가면 −1 (반환 3 → +0x2D8 0x100), 오른쪽이면 +1 (반환 5 → 0x200), 둘 다 맞거나 둘 다 빗나가면 0 [확실: 코드 / 근사: 머리 선분 조건 생략].
	 */
	int edgeSide(Vector3f feet, Vector3f fwd) {
		Vector3f f = new Vector3f(fwd.x, 0, fwd.z).normalize();
		Vector3f o = new Vector3f(feet).add(0, FOOT, 0);
		Vector3f right = rightOf(f);
		LevelCollision.Hit h1 = wseg(o, new Vector3f(o).fma(-WALL_SPREAD, right).fma(WALL_PROBE, f));
		LevelCollision.Hit h2 = wseg(o, new Vector3f(o).fma(WALL_SPREAD, right).fma(WALL_PROBE, f));
		boolean m1 = h1 != null && climbSurface(h1.tri()) && !narrowSurface(h1.tri());
		boolean m2 = h2 != null && climbSurface(h2.tri()) && !narrowSurface(h2.tri());
		return m1 == m2 ? 0 : m1 ? 1 : -1;
	}

	/** 벽 옆 끝인지 (−1 왼쪽 / +1 오른쪽 / 0). */
	public int wallEdge() {
		return wallEdge;
	}

	/**
	 * 벽 옆 끝 점프 (0x1E9C9C: 벽 상태 + +0x2D8 0x300 + ×): 몸 좌표축을 90° 돌리고 (0x100 → +0x70 = 옛 +0x50, 0x200 → 반대) 지역 속도
	 * (0, Sold+0x120 12, Sold+0x114 10) → 위 192 · 앞 160, +0x2D8 0x1000020 (다시 붙지 않음) [확실: 값·90° / 추정: 도는 쪽 = 막힌 쪽].
	 */
	private void edgeJump() {
		Vector3f face = state == NavState.WALL_WALK && wallBack ? new Vector3f(wallN) : new Vector3f(wallN).negate();
		Vector3f dir = rightOf(face).mul(wallEdge);
		body.setYaw(yawOf(dir));
		int side = wallEdge;
		wallEdge = 0;
		edgeWallN.set(wallN);
		leave(new Vector3f(dir).mul(EDGE_JUMP_OUT), EDGE_JUMP_UP, "ANIJump");
		// +0x2D8 0x300 은 공중에서도 남고 (옆 벽 질의 0x1E53F0 의 방향), 0x20 (다시 붙지 않음) 은 +0x2A0 0.1 초 [추정: 0x20 이 풀리는 시간]
		edgeJumpSide = side;
		edgeJumpDir.set(dir);
		edgeAirTime = 0;
		cooldown = 0.1f;
	}

	/** 벽 옆 끝 점프 뒤 공중인지 (뛴 쪽 −1/+1, 0 = 아님). */
	public int edgeJumpSide() {
		return edgeJumpSide;
	}

	// ---- 좁은 턱 벽 따라 걷기 (+0x174 0x100000 벽 보고 / 0x200000 등 대고) ----

	/** 좁은 턱 진입 (0x1C7280): 벽 보고면 정면 = −법선, 등 대고 (BackPress) 면 정면 = 법선 (0x1C8000: +0x70 = ∓+0x220), 발은 질의가 맞춘 바닥. */
	private void enterWallWalk(WallProbe w, boolean back) {
		state = NavState.WALL_WALK;
		stateTime = 0;
		wallBack = back;
		wallN.set(w.n());
		wallFlags(w.tri());
		body.position().set(w.feet());
		body.setYaw(yawOf(back ? new Vector3f(wallN) : new Vector3f(wallN).negate()));
		body.setVy(0);
		body.setGrounded(false);
		body.launch().zero();
		body.play(anims.get(back ? "ANIWallBack" : "ANIWallFront"), true);
	}

	/**
	 * 좁은 턱 벽 따라 걷기 (0x1C8C70): 지역 좌우 속도 = (스틱 · 몸 오른쪽) 의 부호 × Sold+0x168 1.25 m/s (크기 무관), 상하 0.
	 * 매 틱 벽 질의 (0x1E47E0) 로 벽 + 9.6 과 발밑 바닥에 맞추고, 실패하면 (턱 끝·바닥 없음) 떨어진다 (0x1E52D4: 좁은 턱 면은 막힘 대신 0 → 0x1E9030(2)) [확실].
	 * × = 벽 반대 점프 (0x1E9D9C: 좌우 속도 ≤ 12 + 스틱 벽 반대, NoBackJumps 아님), ○ = 놓기 [엔진].
	 */
	private void wallWalk(Input in, float camYaw, float dt) {
		Vector3f p = body.position();
		Vector3f face = wallBack ? new Vector3f(wallN) : new Vector3f(wallN).negate();
		Vector3f right = rightOf(face);
		boolean stick = stickMag(in) > 0.2f;
		Vector3f sd = stickDir(in, camYaw);
		float side = stick ? sd.dot(right) : 0, away = stick ? sd.dot(wallN) : 0;
		// 스틱 좌우 성분 부호 (원작은 1e-4 문턱, 엔진은 대각선 떨림을 막으려 0.3)
		float vx = Math.abs(side) > 0.3f ? Math.signum(side) * LEDGE_WALK_SPEED : 0;
		if (in.pressed(PadButton.CROSS) && vx == 0 && away > 0.3f && !noBackJumps) {
			body.setYaw(yawOf(wallN));
			leave(new Vector3f(wallN).mul(BACK_JUMP_OUT), BACK_JUMP_UP, "ANIWJump");
			return;
		}
		if (in.pressed(PadButton.CIRCLE)) {
			leave(new Vector3f(), 0, "ANIFall");
			return;
		}
		if (vx != 0) {
			Vector3f cand = new Vector3f(p).fma(vx * dt, right);
			WallProbe w = probeWall(cand, new Vector3f(wallN).negate(), wallN);
			if (w == null) {
				leave(new Vector3f(), 0, "ANIFall");
				return;
			}
			wallN.set(w.n());
			wallFlags(w.tri());
			p.set(w.feet());
			body.setYaw(yawOf(wallBack ? new Vector3f(wallN) : new Vector3f(wallN).negate()));
		}
		String k = wallBack ? "ANIWallBack" : "ANIWallFront";
		body.play(anims.get(vx < 0 ? k + "L" : vx > 0 ? k + "R" : k), false);
	}

	/** 지금 등 대고 걷기인지 (+0x174 0x200000). */
	public boolean wallBack() {
		return wallBack;
	}

	// ---- 턱 매달리기 ----

	/**
	 * 턱 매달리기 진입 (원작 0x1C75F8, +0x174 = (… &amp; ~0x7FF) | 0x10, +0x164 = Sold+0x104) [확실: 디스어셈블].
	 * <pre>
	 * 벽 면 (+0x238) 이 Climbable·Ladder (bitfield0 0x18) 가 아니고 턱 윗면 (+0x234) 이 Narrow·NarrowNoBB (0x10000004) 면 <b>평균대 매달림</b> (+0x2D8 0x40):
	 *   애니 = 윗면 Narrow (0x4) 면 0x30 ANIBalanceCatch ("balancebeamcatch" — 한 손으로 매달림) 아니면 0x33 ANIWallCatch,
	 *   시간 = Sold+0x22C (크라토스 1 초, 0x1C7678 – 0x1C76F4) — 다 쓰면 떨어진다 (0x1C7B04 → 0x1E9030 모드 2),
	 *   좌우 이동 없음 (0x1C89D4 → 0x1C8DF0), 올라서기는 스틱 조건 없이 (0x1E9EC0, {@link #pullUpNarrow}).
	 * 그 밖: 공중 (0x4C) 이면 0xD ANIWallHangCatch, 시간 = 턱 면 WallHangTimer (+0x234 면, 0 이면 무한).
	 * 타이머가 이미 돌고 있으면 (+0x2D8 0x400, 남은 시간 &gt; 0) 이어 간다 — 지상·벽 오르기·물에 닿아야 초기화 (0x1C78A0).
	 * </pre>
	 */
	private void enterHang(Ledge l) {
		LevelCollision.Tri wall = l.wall();
		narrowHang = l.top() != null && narrowLedge(l.top())
				&& !(wall != null && (wall.has(SurfaceAttr.CLIMBABLE) || wall.has(SurfaceAttr.LADDER)));
		hangTop = l.top();
		float limit = narrowHang ? BALANCE_HANG_TIME : l.top() != null && l.top().info() != null ? l.top().info().wallHangTimer() : 0;
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
		body.play(anims.get(hangCatchAnim()), true);
	}

	/** 매달림 잡기 애니 (0x1C75F8): 평균대 매달림이면 Narrow 윗면 0x30 ANIBalanceCatch / NarrowNoBB 0x33 ANIWallCatch, 그 밖 0xD ANIWallHangCatch. */
	private String hangCatchAnim() {
		if (!narrowHang) {
			return "ANIWallHangCatch";
		}
		return hangTop != null && hangTop.has(SurfaceAttr.NARROW) ? "ANIBalanceCatch" : "ANIWallCatch";
	}

	/** 지금 평균대 매달림인지 (+0x2D8 0x40 — 턱 윗면 Narrow·NarrowNoBB, 0x1C75F8). */
	public boolean narrowHang() {
		return state == NavState.LEDGE_HANG && narrowHang;
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
				narrowHang = false;
				leave(new Vector3f(), 0, "ANIFall");
				return;
			}
		}
		float sx = in.leftX(), sy = in.leftY();
		if (in.pressed(PadButton.CROSS) || sy > 0.6f) {
			if (narrowHang) {
				if (pullUpNarrow(p)) {
					return;
				}
			} else {
				Vector3f over = new Vector3f(p).fma(-(WALL_OFF + 14), wallN);
				NavWorld.Floor top = world.floor(over.x, ledgeY + 8, over.z, 24);
				if (top != null) {
					p.set(over.x, top.y(), over.z);
					land("ANIWallPullup");
					return;
				}
			}
		}
		if (in.pressed(PadButton.CIRCLE) || sy < -0.6f) {
			narrowHang = false;
			leave(new Vector3f(), 0, "ANIFall");
			return;
		}
		if (narrowHang) {
			// 0x1C89D4: 평균대 매달림은 좌우 이동이 없고, 매 프레임 0x1C75F8 이 같은 잡기 애니를 다시 고른다 (0x1C7B80 → 0x1C7C18)
			body.play(anims.get(hangCatchAnim()), false);
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

	/**
	 * 평균대 매달림에서 올라서기 (× 처리 0x1E9B68 의 0x1E9EB0 – 0x1E9FB8): 지상 상태 (+0x174 0x1), 애니 = 윗면 NarrowNoBB (0x10000000) 면
	 * 0x34 ANIWallPullup, 아니면 +0x2D8 0x8000000 (붙잡을 때 법선 · 오른쪽 &gt; 0) 이면 0x32 ANIBalancePullupR ("balancebeampullup2"),
	 * 아니면 0x31 ANIBalancePullupL ("balancebeampullup"), 애니 구동 (+0x2D8 0x800000) [확실: 디스어셈블].
	 * 엔진은 애니 루트 이동 대신 끝 자리에 둔다 (일반 턱 올라서기와 같은 방식) [근사]: 끝 자리 = 매달린 자리 (벽 + 9.6) 에서 벽 쪽으로
	 * {@link #BALANCE_PULLUP_FORWARD} / {@link #WALL_PULLUP_FORWARD} 간 곳의 윗면 — 일반 턱의 벽 + 23.6 은 폭 8 들보 (ATHN06B) 너머라 바닥이 없다.
	 */
	private boolean pullUpNarrow(Vector3f p) {
		boolean noBb = hangTop != null && hangTop.has(SurfaceAttr.NARROW_NO_BB);
		Vector3f over = new Vector3f(p).fma(-(noBb ? WALL_PULLUP_FORWARD : BALANCE_PULLUP_FORWARD), wallN);
		NavWorld.Floor top = world.floor(over.x, ledgeY + 8, over.z, 24);
		if (top == null) {
			return false;
		}
		narrowHang = false;
		p.set(over.x, top.y(), over.z);
		land(noBb ? "ANIWallPullup" : catchRight ? "ANIBalancePullupR" : "ANIBalancePullupL");
		return true;
	}

	// ---- 좁은 바닥 (평균대) 단계 · 평균대 붙잡기 (원작 0x1BFA70 / 0x1BFBF0 / 0x1E5A70) ----

	/** 좁은 바닥 단계 결과 ({@link #narrowStep}). */
	public enum NarrowStep {
		/** 다섯 선분 중 하나가 바닥을 찾음 — 발 높이를 그 바닥에 맞췄다. */
		FLOOR,
		/** 바닥 없음 — 떨어진다 (붙잡을 면도 없음). */
		FALL,
		/** 바닥 없음 → 방금 서 있던 들보 옆면을 붙잡아 매달렸다 ({@link NavState#LEDGE_HANG}). */
		CAUGHT
	}

	/**
	 * 좁은 바닥 상태 (원작 +0x174 0x80000 Narrow / 0x2000 NarrowNoBB, 0x1BFA70): 지상 단계 (0x1EA5F0) 끝에 능력 Balance (+0x2DC 0x2, 크라토스) 이면
	 * 발밑 면 (+0x234) 으로 정한다 — 지상이고 bitfield0 &amp; 0x4 (Narrow) 면 0x80000|0x2000, 0x10000000 (NarrowNoBB) 면 0x2000, 지상이 아니면 끔 [확실].
	 * 다음 프레임 지상 단계는 이 상태면 바닥 질의 0x1E3298 대신 {@link #narrowStep} (0x1BFBF0) 을 쓴다 (0x1EA838 – 0x1EA85C).
	 *
	 * @param grounded 이번 단계 뒤 지상인가
	 * @param attrs    발밑 면의 표면 속성 (지상이 아니면 무시)
	 */
	public void groundSurface(boolean grounded, long attrs) {
		narrowGround = grounded && (attrs & (SurfaceAttr.NARROW.bit() | SurfaceAttr.NARROW_NO_BB.bit())) != 0;
		if (!narrowGround) {
			narrowFlags = 0;
		}
	}

	/** 다음 지상 단계가 좁은 바닥 단계 ({@link #narrowStep}) 인가 (+0x174 0x80000 | 0x2000). */
	public boolean narrowGround() {
		return narrowGround;
	}

	/** 마지막으로 바닥을 찾은 좁은 바닥 질의의 선분 비트 (0x2C8BD8: 1 가운데 · 2 오른앞 · 4 오른뒤 · 8 왼뒤 · 0x10 왼앞). */
	public int narrowFlags() {
		return narrowFlags;
	}

	/** 마지막 좁은 바닥 질의가 찾은 바닥 면의 표면 속성 (+0x234). */
	public long narrowSurface() {
		return narrowSurface;
	}

	/**
	 * 좁은 바닥 단계 (원작 0x1BFBF0) [확실: 디스어셈블]: 0x1E5A70 다섯 선분 ({@link #narrowProbe}) 이 바닥을 찾으면 그 높이에 서고
	 * (비트 → 0x2C8BD8), 못 찾으면 떨어지며 (0x1E9030 모드 2) 지난 비트 쪽 들보를 붙잡는다 ({@link #balanceCatch}).
	 * 0x80000 (Narrow) 이면 가운데가 비었을 때 바깥으로 기우는 균형 동작 (+0x260.x, +0x2BC/+0x2C0 흔들림, 0x1BFC4C – 0x1BFE48) 도 하지만
	 * 엔진 영웅 이동은 애니 이동량이라 넣지 않았다 [근사].
	 *
	 * @param prev 이번 이동 전 발 (원작 +0x110 − 6.4, 발판에 실린 이동 포함 — 0x1E6248 이 먼저 돈다)
	 * @param feet 이번 이동 뒤 발 (바뀐다: FLOOR 면 바닥 높이, CAUGHT 면 매달린 자리)
	 */
	public NarrowStep narrowStep(Vector3f prev, Vector3f feet) {
		int flags = narrowProbe(prev, feet);
		if (flags != 0) {
			narrowFlags = flags;
			return NarrowStep.FLOOR;
		}
		if (balanceCatch(prev, feet)) {
			return NarrowStep.CAUGHT;
		}
		narrowGround = false;
		return NarrowStep.FALL;
	}

	/**
	 * 좁은 바닥 질의 (원작 0x1E5A70) [확실: 디스어셈블].
	 * <pre>
	 * d = max(|새 − 옛|, 3.2) (내비 위치 = 발 + 6.4). 시작 = 새 + 위 × max(d − 6.4, 0), 끝 = 새 − 위 × (6.4 + d) (= 발 − d) 에서 수평으로
	 *   가운데 · 오른쪽 4.8 + 앞 9.6 · 오른쪽 4.8 − 앞 9.6 · 왼쪽 4.8 − 앞 9.6 · 왼쪽 4.8 + 앞 9.6 (비트 1 · 2 · 4 · 8 · 0x10, 오른쪽 = +0x50 × 폭/4,
	 *   앞 = −+0x70 × 깊이/2) 다섯 선분, 면 거름 0x30042 (Water·Death·AIBlock·ClimbGuide), 대상 = 바닥 질의와 같은 개체 (정적 시트 한쪽 면 + CDV 몸).
	 * 맞은 면 법선 · 위 &gt; 0.1 이면 그 비트. 처음 맞은 것이 바닥 (+0x234 면 · +0x210 법선 · 발판 +0x240/+0x24C/+0x250), 내비 y = 적중 y + 6.4, 수직 속도 0.
	 * </pre>
	 * 평균대 (폭 8) 위를 걸으면 가운데만 맞고, 끝·옆으로 나가도 모서리 선분이 들보에 닿는 동안은 그 높이에 선다.
	 *
	 * @return 비트 (0 = 바닥 없음). 바닥이면 feet.y 를 그 높이로.
	 */
	int narrowProbe(Vector3f prev, Vector3f feet) {
		float d = Math.max(prev != null ? feet.distance(prev) : 0, NARROW_REACH);
		Vector3f f = forward(body.yaw());
		Vector3f r = rightOf(f);
		Vector3f start = new Vector3f(feet).add(0, FOOT + Math.max(d - FOOT, 0), 0);
		int flags = 0;
		for (int i = 0; i < 5; i++) {
			float rs = i == 0 ? 0 : i <= 2 ? BODY_W / 4 : -BODY_W / 4;
			float fs = i == 0 ? 0 : i == 1 || i == 4 ? BODY_W / 2 : -BODY_W / 2;
			Vector3f end = new Vector3f(feet.x, feet.y - d, feet.z).fma(rs, r).fma(fs, f);
			NavWorld.Contact c = world.frontContactAll(start, end, NARROW_PROBE_EXCLUDE);
			if (c == null || c.normal().y <= NARROW_FLOOR_NY) {
				continue;
			}
			if (flags == 0) {
				feet.y = c.point().y;
				narrowSurface = c.attrs();
			}
			flags |= 1 << i;
		}
		return flags;
	}

	/**
	 * 몸 질의 0x1E2100 의 아래 단 (지상·공중 0x1EA5F0 → 플레이어 a3 = 1, 0x1E2498 – 0x1E2548) [확실: 디스어셈블]: 내비 위치 (발 + 6.4) 에서 몸 상자
	 * (+0x120 = 폭 19.2 · 깊이 19.2) 모서리로 선분 — 앞 둘은 0x1E1FE8 (왼앞 · 오른앞 모서리 선분, 두 앞 모서리 사이 선분을 양쪽에서 쏴 맞은 점들의 가운데로
	 * 선분), 이어 오른뒤 · 왼뒤. 선분 ({@link NavWorld#bodySegment}: 한쪽 면 벽·Slide 만) 이 맞으면 끝점이 그 면 위에 오도록 민다 (0x1E1F28:
	 * c −= n · (n · (끝 − 충돌점))). 위 단 (발 + 35.2 의 왼앞 · 오른앞 · 뒤 가운데) 은 엔진 몸 구가 맡는다.
	 * <p>엔진은 좁은 바닥 단계 앞에서만 부른다 [근사: 원작은 지상·공중 이동마다]: 엔진 몸 구 (발 + 20, 반지름 12) 는 발 + 8 보다 낮은 벽을 못 막는데,
	 * 아레나 바닥 (NarrowNoBB 원판) 둘레 울타리는 바닥 위 8.6 이라 영웅이 원판 가장자리와 울타리 사이 틈 (폭 1 – 4.5) 에 들어가 다섯 선분이 비면
	 * 떨어졌다 (순회 ARENA06 · 19 · 20 · 26 · 09). 원작은 이 선분이 울타리에서 9.6 떨어뜨려 틈에 들지 않는다. 밀기는 수평만 [근사].
	 *
	 * @param feet    발 (x, z 가 밀린다)
	 * @param exclude 몸 질의 면 거름 (0x3309F4, {@link NavWorld#bodyExclude})
	 * @return 반환 비트 (0x20 앞 · 4 오른뒤 · 8 왼뒤)
	 */
	public int narrowBody(Vector3f feet, long exclude) {
		Vector3f f = forward(body.yaw());
		Vector3f r = rightOf(f);
		float w = BODY_W / 2, d = BODY_W / 2;
		Vector3f c = new Vector3f(feet).add(0, FOOT, 0);
		Vector3f fl = new Vector3f(f).mul(d).fma(-w, r), fr = new Vector3f(f).mul(d).fma(w, r);
		Vector3f bl = new Vector3f(f).mul(-d).fma(-w, r), br = new Vector3f(f).mul(-d).fma(w, r);
		int flags = 0;
		boolean front = bodyRay(c, fl, exclude) | bodyRay(c, fr, exclude); // 0x1E1FE8
		Vector3f pa = new Vector3f(c).add(fl), pb = new Vector3f(c).add(fr);
		NavWorld.Contact h = world.bodySegment(pa, pb, exclude);
		if (h != null) {
			pa.set(h.point());
		}
		h = world.bodySegment(pb, pa, exclude);
		if (h != null) {
			pb.set(h.point());
		}
		front |= bodyRay(c, new Vector3f(pa).add(pb).mul(0.5f).sub(c), exclude);
		if (front) {
			flags |= 0x20;
		}
		if (bodyRay(c, br, exclude)) {
			flags |= 0x4;
		}
		if (bodyRay(c, bl, exclude)) {
			flags |= 0x8;
		}
		feet.x = c.x;
		feet.z = c.z;
		return flags;
	}

	/** 몸 질의 선분 하나 (0x1E1F28): c → c + off 가 벽에 맞으면 끝점이 그 면 위에 오도록 c 를 민다 (수평만 [근사]). */
	private boolean bodyRay(Vector3f c, Vector3f off, long exclude) {
		Vector3f end = new Vector3f(c).add(off);
		NavWorld.Contact h = world.bodySegment(c, end, exclude);
		if (h == null) {
			return false;
		}
		Vector3f n = h.normal();
		float k = n.dot(end.x - h.point().x, end.y - h.point().y, end.z - h.point().z);
		c.x -= k * n.x;
		c.z -= k * n.z;
		return true;
	}

	/**
	 * 평균대 붙잡기 (원작 0x1BFEA8 – 0x1C0288: 좁은 바닥 질의가 바닥을 못 찾은 프레임) [확실: 디스어셈블].
	 * <pre>
	 * 0x1E9030(2) 로 떨어짐 (공중 0x8, 발판 끊기, 기술 끝 0x209918) 뒤 지난 프레임 비트 (0x2C8BD8) 로 방향 s:
	 *   앞 비트 (0x12) 가 있으면 앞 (−+0x70), 없으면 뒤 (+0x70); 오른쪽 비트 (0x6) 면 + 오른쪽, 왼쪽 비트 (0x18) 면 − 오른쪽; 수평 정규화.
	 * a = 옛 내비 − 위 × (6.4 + 1.6) (= 옛 발 − 1.6) → a + s × 16 선분 (거름 0x9030042 | +0x258) 이 맞으면
	 *   P = 적중점 + 면 법선 × 깊이/2 (9.6), P.y = 옛 내비 y − 키 35.2 (발 = 옛 발 − 35.2 = 손이 옛 발 높이).
	 *   새 내비 → P 선분 (같은 거름·같은 개체) 이 막히면 그만 (떨어지기만). 아니면 위치 = P, +0x2D8 0x8000000 = 면 법선 · 옛 오른쪽 &gt; 0
	 *   (올라서기 R/L), 몸 뒤 (+0x70) = 수평 법선 (들보를 봄), 수평 속도 0, +0x174 |= 0x60000000, 턱 질의 0x1E3A80 (새 = 옛 = P) 이 맞으면 매달림 0x1C75F8.
	 * </pre>
	 * 위치는 턱 질의가 실패해도 P 그대로다 (0x1C008C: +0x110 = P 가 턱 질의보다 먼저). 즉 평균대 끝·옆으로 걸어 나가거나 발밑 들보가 빠지면
	 * 방금 서 있던 들보 (또는 뒤의 교차 들보) 옆면을 한 손으로 잡는다 — ATHN06B 들보 홀 (영상 9163.25 · 9207.75).
	 * 선분은 원작처럼 한쪽 면 (시트 면 검사 0x16BDE0 — 뒷면은 지나감, 예 ARENA06 둘레 울타리는 안쪽 면만 있어 바깥에서는 잡히지 않는다).
	 * 엔진: 첫 선분은 정적 시트만 ({@link NavWorld#frontSegment}) — 이어지는 턱 질의가 정적 시트만 보므로 [근사: 원작은 CDV 몸도 질의], 막힘 선분은 CDV 몸 포함
	 * ({@link NavWorld#frontContactAll}: 발밑에서 아직 덜 내려간 무너지는 들보가 P 와의 사이에 있으면 원작처럼 붙잡지 않는다).
	 */
	private boolean balanceCatch(Vector3f old, Vector3f feet) {
		int fl = narrowFlags;
		Vector3f f = forward(body.yaw());
		Vector3f r = rightOf(f);
		Vector3f s = (fl & 0x12) != 0 ? new Vector3f(f) : new Vector3f(f).negate();
		if ((fl & 0x6) != 0) {
			s.add(r);
		}
		if ((fl & 0x18) != 0) {
			s.sub(r);
		}
		s.y = 0;
		if (s.lengthSquared() < 1e-6f) {
			return false;
		}
		s.normalize();
		Vector3f a = new Vector3f(old).sub(0, CATCH_DROP, 0);
		LevelCollision.Hit h = world.frontSegment(a, new Vector3f(a).fma(CATCH_REACH, s), NavWorld.WALL_PROBE_EXCLUDE);
		if (h == null) {
			return false;
		}
		Vector3f n = facing(h, a);
		Vector3f pos = new Vector3f(h.point()).fma(WALL_OFF, n);
		pos.y = old.y - ROPE_HAND;
		Vector3f from = new Vector3f(feet).add(0, FOOT, 0), to = new Vector3f(pos).add(0, FOOT, 0);
		if (world.frontContactAll(from, to, NavWorld.WALL_PROBE_EXCLUDE) != null) {
			return false;
		}
		Vector3f nh = new Vector3f(n.x, 0, n.z);
		if (nh.lengthSquared() < 1e-6f) {
			return false;
		}
		nh.normalize();
		catchRight = n.dot(r) > 0;
		feet.set(pos);
		body.position().set(pos);
		body.setYaw(yawOf(new Vector3f(nh).negate()));
		body.launch().zero();
		body.setGrounded(false);
		narrowGround = false;
		Ledge l = probeLedge(pos, pos, new Vector3f(nh).negate(), null);
		System.out.printf(java.util.Locale.ROOT, "평균대 붙잡기: 비트 0x%x 방향 (%.2f, %.2f) 면 (%.1f, %.1f, %.1f) → 발 (%.1f, %.1f, %.1f) 턱 %s%n", fl, s.x, s.z,
				h.point().x, h.point().y, h.point().z, pos.x, pos.y, pos.z, l != null ? String.format(java.util.Locale.ROOT, "%.1f", l.y()) : "없음");
		if (l == null) {
			state = NavState.AIR;
			airJump = false;
			return false;
		}
		enterHang(l);
		return true;
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
		world.ropeGrabbed(); // 0x1C5D98: 0x29DBF8 = 0 (누가 잡든)
		listener.ropeGrabbed(r); // 0x1C4FC0: 이벤트 1051 (인자 0) → 스크립트
		Vector3f d = new Vector3f(r.end()).sub(r.top());
		switch (r.type()) {
			case ZIPLINE -> {
				state = NavState.ZIPLINE;
				// 낮은 쪽으로 미끄러진다
				zipDir = r.end().y <= r.top().y ? 1 : -1;
				zipSpeed = 0; // [추정: 잡을 때 줄 방향 속도 0 에서 가속]
				body.setYaw(yawOf(new Vector3f(d).mul(zipDir)));
				sim(r).attach(ropeT * sim(r).length());
				body.play(anims.get("ANIZipLine"), true);
			}
			case TRAVERSE -> {
				state = NavState.ROPE_TRAVERSE;
				sim(r).attach(ropeT * sim(r).length());
				body.play(anims.get("ANIBRope"), true);
			}
			default -> {
				state = NavState.ROPE;
				RopeSim sim = sim(r);
				sim.attach(ropeT * sim.length());
				ropeT = sim.dist() / sim.length();
				// 잡을 때 (0x1C5D54): 몸 오른쪽 +0x50 → 0x303FC0, 뒤 +0x70 → 0x303FB0, 흔들기 창 0x29DBF4 = 0.25 초 [확실]
				Vector3f face = forward(body.yaw());
				// 짧은 사슬 줄 (SCR f3, 줄 +0x374 & 4) 은 영웅 +0x174 = 0x10000 (0x1C5DA4) → 흔들기 힘 구간 (0x1C6BC8–0x1C71B8) 전체를 건너뛴다:
				// 잡을 때 미는 창도 없다 (HDES01E ← ATRET01A 도착 rope3 이 크게 흔들리던 원인, 43 §13.10) [확실]
				ride.grab(face, r.shortChain());
				// 몸 방향 = 줄 축 4 (또는 2) 방향 중 지금 뒤에 가장 가까운 것 (0x1C6918), 보이는 몸은 보간 [추정: 잡을 때 +0x150 = 0.1]
				ropeDir = r.bestDir(new Vector3f(face).negate());
				turnBlend = TURN_BLEND_START;
				body.play(anims.get("ANIRope"), true);
			}
		}
		placeOnRope();
	}

	/** 줄 시뮬레이션 (없으면 만든다): 매단 줄 = 매단 점에서 아래로, 양끝 고정 줄 (수평 줄·짚라인) = 두 끝 고정 (0x218488). */
	public RopeSim sim(NavRope r) {
		return world.sim(r); // 줄 오브젝트당 하나 — 적도 같은 시뮬레이션에 매달린다 (38 §18.3)
	}

	/** 줄 위 손 위치 → 몸 위치 (0x1C66E4: 몸 = 줄 점 − 키). 손 = 시뮬레이션 줄 위 붙은 거리의 점 (0x2184E8). */
	private void placeOnRope() {
		RopeSim sim = sim(rope);
		if (state != NavState.ROPE) {
			sim.moveAttach(ropeT * sim.length());
		}
		Vector3f hand = sim.pointAt(sim.dist(), new Vector3f());
		body.position().set(hand.x, hand.y - ROPE_HAND, hand.z);
	}

	/** 줄에서 떨어짐: 매달림 해제 (0x218270, 무게는 줄어들며 줄은 계속 흔들림). */
	private void releaseRope() {
		if (rope != null && (state == NavState.ROPE || state == NavState.ROPE_TRAVERSE || state == NavState.ZIPLINE)) {
			sim(rope).detach();
		}
	}

	/** 줄에서 × 가 먹기 시작하는 시간 0.5 초 (0x1C5544 / 0x1C55A4, 잡을 때 0 인 0x29DBF8) [확실]. */
	public static final float ROPE_JUMP_DELAY = 0.5f;
	/** 매단 줄 × 뛰어내리기: 위 Sold+0x140 14 → 224, 앞 Sold+0x144 14 → 224 (0x1C55F4 / 0x1C561C) [확실]. */
	public static final float ROPE_JUMP_UP = K.units(NavTuning.ROPE_JUMP_UP), ROPE_JUMP_OUT = K.units(NavTuning.ROPE_JUMP_OUT);
	/** 뛰어내릴 때 줄에 주는 반작용 = −속도 × 5 × min(1, 붙은 거리 m × 0.25) (0x1C57A8 → 0x218470) [확실]. */
	public static final float ROPE_KICK = RopeRide.KICK;
	/** 매단 줄 위 회전: 스틱 좌우 문턱 0.5 (0x1C6A7C), 보간 가중치 0.1 에서 프레임마다 ×1.1111 (+0x150 / +0x154, 0x1E0DC0) [확실]. */
	public static final float TURN_STICK = 0.5f, TURN_BLEND_START = 0.1f, TURN_BLEND_GROW = 1.1111f;

	/** 매단 줄 위 몸 방향 번호 (0..3, {@link NavRope#backDirs()} 의 뒤쪽). */
	public int ropeDir() {
		return ropeDir;
	}

	/** 몸 방향 보간 가중치 (1 = 다 돎, 그때만 다음 회전 입력을 받음). */
	public float turnBlend() {
		return turnBlend;
	}

	private void hangRope(Input in, float camYaw, float dt) {
		RopeSim sim = sim(rope);
		float sy = in.leftY(), sx = in.leftX();
		boolean hasStick = stickMag(in) > 0.2f;
		// 원작 +0x2E0: 스틱 월드 방향, 스틱이 없으면 −뒤 (= 정면) (0x1D6DE4)
		Vector3f ref = hasStick ? stickDir(in, camYaw) : forward(body.yaw());
		if (in.pressed(PadButton.CROSS) && world.ropeClock() > ROPE_JUMP_DELAY) {
			ropeJump(sim, ref);
			return;
		}
		String a = anims.get("ANIRope");
		// 흔들기 버튼 (+0x310 0x10 ← 논리 0x40000 ← 패드 비트 5 = ○, 0x1D8108) 을 누르고 있으면 오르내리기 대신 흔들기 (0x1C61FC) [확실: 비트 / 추정: ○]
		boolean swing = !rope.shortChain() && in.held(PadButton.CIRCLE); // 0x1C61CC: 0x10000 (짧은 사슬) 이면 흔들기 버튼 무시
		ropeTurn(sx, ref, swing, dt);
		ropeVel = swing ? 0 : ropeVelocity(sy, dt);
		if (ropeVel != 0) {
			// 줄 위치 (m) += 속도 / 16 · dt, [0.25, 길이 − 0.25] (0x1C6578 → 0x2183B0)
			float want = sim.dist() + ropeVel / NavTuning.UNITS_PER_METER * dt;
			if (rope.shortChain()) {
				want = Math.max(0.5f, want); // 0x1C65E4: 최소 0.25 m, 0x10000 이면 0.5 m
			}
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
		if (swing) {
			a = anims.get("ANIRopeIdle");
		}
		// 매 프레임 매달림 힘을 준다 (0x1C71A8 → 0x218470, 줄이 잠들지 않음)
		if (!rope.shortChain()) {
			sim.setForce(ride.force(sim, rope.backDirs()[ropeDir], swing && !ropeSlide, SWING_FORCE, dt));
		}
		placeOnRope();
		body.play(a, false);
	}

	/**
	 * 매단 줄 × 뛰어내리기 (원작 0x1C5488, 줄 갱신 0x1C6B58 에서) [확실: 디스어셈블].
	 * <pre>
	 * 잡은 뒤 0.5 초 (0x29DBF8 &gt; 0.5) 가 지나야 한다. 뒤 후보 = 줄 축 방향 ({@link NavRope#backDirs()}, 템플릿 방향 제한) 중
	 * −(스틱 월드 방향, 없으면 정면) 과 내적이 가장 큰 것 → +0x70, 그 이전 번호 → +0x50.
	 * 지역 속도 (0, Sold+0x140, Sold+0x144) → 월드 속도 = 위 × 224 + 정면 × 224 (줄 점 속도는 더하지 않음 — +0xE0 를 덮어씀).
	 * 줄에 반작용 −속도 × 5 × min(1, 붙은 거리 m × 0.25) (0x218470), 애니 = 0x1E98B8 (앞 속도 ≥ 1 m/s → ANIJump).
	 * </pre>
	 */
	private void ropeJump(RopeSim sim, Vector3f ref) {
		Vector3f face = RopeRide.jumpFace(rope.axis(), rope.dirs(), ref);
		Vector3f v = RopeRide.jumpVelocity(face, ROPE_JUMP_UP, ROPE_JUMP_OUT);
		sim.setForce(RopeRide.kick(sim, v));
		body.setYaw(yawOf(face));
		leave(new Vector3f(face).mul(ROPE_JUMP_OUT), ROPE_JUMP_UP, ROPE_JUMP_OUT < NavTuning.UNITS_PER_METER ? "ANIVJump" : "ANIJump");
	}

	/**
	 * 매단 줄 위 몸 방향 (원작 0x1C6858 – 0x1C6B18, 흔들기 버튼·줄 미끄럼 아닐 때) [확실: 디스어셈블].
	 * <pre>
	 * 지금 번호 = 줄 축 후보 중 몸 뒤 (+0x70) 에 가장 가까운 것. +0x2D8 0x2000000 이 아니고 몸 보간 +0x150 이 1 (이전 회전이 끝남) 일 때만:
	 *   방향 제한 (2 방향 줄): 후보 중 −(+0x2E0 스틱 월드 방향 / 없으면 정면) 에 가장 가까운 것으로 (바뀌면 회전).
	 *   4 방향 줄: +0x2F0 (패드 스틱) 의 x · w &gt; 0.5 면 번호 + 1, &lt; −0.5 면 − 1 (90° 씩).
	 *   회전하면 +0x174 0x20000000, +0x150 = 0.1, +0x154 = 1.1111.
	 * 몸 축 = 후보[번호] (뒤), 후보[번호 − 1] (+0x50). 보이는 몸 (+0x90 행렬, 0x1E0C60) 은 매 프레임 slerp(보이는 몸, 몸 축, +0x150 × f),
	 * 그 뒤 +0x150 ×= 1 + (+0x154 − 1) × f (f = 60 Hz 프레임 수, 1 에서 멈춤) → 0.1 × 1.1111^k 가 1 에 닿는 22 프레임 (0.37 초) 에 한 번 돈다.
	 * </pre>
	 * 엔진: 번호 + 1 = 몸 오른쪽 (엔진 rightOf) 으로 90° — 스틱 오른쪽이 오른쪽으로 돈다 [추정: 좌우 대응, w = 1 로 봄].
	 */
	private void ropeTurn(float sx, Vector3f ref, boolean swing, float dt) {
		Vector3f[] dirs = rope.backDirs();
		float f = dt * 60f;
		if (!swing && !ropeSlide && turnBlend >= 1) {
			int next = ropeDir;
			if (rope.dirStep() == 2) {
				next = rope.bestDir(new Vector3f(ref.x, 0, ref.z).negate());
			} else if (sx > TURN_STICK) {
				next = (ropeDir + 1) & 3;
			} else if (sx < -TURN_STICK) {
				next = (ropeDir + 3) & 3;
			}
			if (next != ropeDir) {
				ropeDir = next;
				turnBlend = TURN_BLEND_START;
			}
		}
		// 보이는 몸 = 몸 축 쪽으로 slerp (yaw 보간)
		float want = yawOf(new Vector3f(dirs[ropeDir]).negate());
		float diff = (float) Math.IEEEremainder(want - body.yaw(), 2 * Math.PI);
		body.setYaw(body.yaw() + diff * Math.min(1, turnBlend * f));
		turnBlend = Math.min(1, turnBlend * (1 + (TURN_BLEND_GROW - 1) * f));
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

	/**
	 * 수평 줄에 매달린 채 전투 기술 중 (원작 Combat_StateMask 0x207DC0: +0x174 0x400 · 0x20000 → 분기 마스크 0x80 — □ ROPEHOHA 발차기 · △ ROPEPOWER01 ·
	 * ○ ROPEHOHGRAB · 피격 ROPEHOHR1, 10 문서 §2.4) [확실: 분기]: 손은 줄 위 자리 그대로 (0x1C71A8 매달림 매 프레임), 줄 이동·내비 애니 없음 — 몸 애니는 기술이
	 * 맡는다. 줄 시뮬레이션과 줄 시계는 그대로 돈다 [근사: 기술 중 줄 위 자리 고정].
	 *
	 * @param target 기술을 시작할 때 바라볼 대상 (null = 그대로) — 줄 방향 두 쪽 중 대상 쪽 [근사: 원작 자동 조준 0x2077A0 을 줄 축에 투영]
	 */
	public void holdRope(Vector3f target, float dt) {
		stateTime += dt;
		world.updateRopes(dt);
		world.tickRopeClock(dt);
		if (target != null && rope != null) {
			Vector3f d = new Vector3f(rope.end()).sub(rope.top());
			d.y = 0;
			Vector3f to = new Vector3f(target).sub(body.position());
			if (d.lengthSquared() > 1e-6f && to.x * to.x + to.z * to.z > 1e-4f) {
				d.normalize().mul(Math.signum(d.x * to.x + d.z * to.z) >= 0 ? 1 : -1);
				body.setYaw(yawOf(d));
			}
		}
		placeOnRope();
	}

	private void traverse(Input in, float camYaw, float dt) {
		// × = 잡은 뒤 0.5 초가 지나면 놓기 (0x1C552C: 수평 줄 0x20000 → 0x1E9030 모드 2) [확실]. ○ 는 줄 잡기 기술 ROPEHOHGRAB (A202, 분기 0x80 —
		// HeroRig 가 수평 줄에서 전투를 돌린다, 43 §34) — 예전 엔진의 "○ 놓기" 는 원작에 없다
		if (in.pressed(PadButton.CROSS) && world.ropeClock() > ROPE_JUMP_DELAY) {
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
		// × 는 짚라인 (0x40000) 에서 아무것도 하지 않는다 (0x1C551C 에서 바로 돌아감) [확실] — 끝까지 타야 놓는다
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

	// ---- 물 (원작 0x1C9BB8 물 상태 + 0x1CA448 수영 갱신, 38 문서 §11) ----

	/**
	 * 물 하위 상태 = 원작 +0x174 비트 [확실: 0x1C93B8 / 0x1C94B0 / 0x1C95B0].
	 * TREAD 0x80 물 위 떠 있기 (ANITread / ANITreadSwim, 4.5 m/s, × = 점프), SINK 0x100 물에 떨어진 직후 가라앉았다가 부력으로 떠오름,
	 * DIVE 0x200 잠수 (□ 아래 · △/× 위, R1 충전 돌진, 빠르게 떠오르면 물 밖으로 도약).
	 */
	public enum Water {
		TREAD, SINK, DIVE
	}

	/** 물 위 떠 있기 목표 속도 Sold+0x1D8 4.5 → 72 (0x1C93B8 → +0x164, 0x1CA7EC) [확실]. */
	public static final float TREAD_SPEED = K.units(NavTuning.TREAD);
	/** 부력 Sold+0x1F0 15 m/s² → 240 (0x1CAC68: 0x80/0x100 에서 월드 수직 속도 += 이 값 × dt) [확실]. */
	public static final float BUOYANCY = K.units(NavTuning.BUOYANCY);
	/** 물에 들어갈 때 수직 속도 = max(× 0.4, −64) (0x1CA0E8) [확실]. */
	public static final float ENTRY_DAMP = 0.4f, ENTRY_MIN_VY = -64f;
	/** 잠수 중 수면에 닿을 때 수직 속도 ≥ Sold+0x1D4 × 16 × 0.5 = 60 이면 물 밖 도약 (0x1C9DB8), 속도 × min(2, 점프 × 1.5 / 수직 속도) (0x1C9DE4) [확실]. */
	public static final float LEAP_MIN_VY = SWIM_SPEED * 0.5f, LEAP_GAIN_MAX = 2f, LEAP_JUMP_SCALE = 1.5f;
	/**
	 * 잠수 돌진 (R1, 0x1C9E70): 누르는 동안 충전, 떼면 k = clamp((충전 − Sold+0x1F8) / (+0x1FC − +0x1F8)) 가 0 보다 크면 속도 lerp(+0x200, +0x204, k),
	 * 조종 시간 +0x2B8 = lerp(+0x208, +0x20C, k) + 1 (1 이상인 동안 상하 조종, 0 이 되면 끝) [확실].
	 */
	public static final float DASH_CHARGE_MIN = K.get(NavTuning.DASH_CHARGE_MIN), DASH_CHARGE_MAX = K.get(NavTuning.DASH_CHARGE_MAX),
			DASH_MIN = K.units(NavTuning.DASH_MIN), DASH_MAX = K.units(NavTuning.DASH_MAX), DASH_STEER_MIN = K.get(NavTuning.DASH_STEER_MIN),
			DASH_STEER_MAX = K.get(NavTuning.DASH_STEER_MAX);
	/** 공중 다이빙 자세 (0x1CA318): 낙하 속도 &gt; 점프 초속이면 0.4 초, 앞 속도 &gt; 80 이면 0.35 초 뒤까지의 궤적 선분 (0x1C98D8) [확실: 값]. */
	public static final float DIVE_LOOK_FALL = 0.4f, DIVE_LOOK_RUN = 0.35f, DIVE_RUN_SPEED = 80f;

	private Water water = Water.TREAD;
	/** 물속 월드 수직 속도 (+0xE0.y), 잠수 수직 속도 (+0x260.y). */
	private float waterVy, diveVy;
	/** 아직 떠 있는 높이 위에서 떨어지는 중 (공중 물리), 다이빙 (+0x2D8 0x100000), 이번 낙하는 다이빙 안 함 (0x200000), 수면이 NoDiving. */
	private boolean plunging, divingIn, noDiveFall, waterNoDiving;
	/** 물가 바닥 (0x1CA198 / 0x1E3298): 창 안의 마른 바닥이 수면 − 28.16 보다 높으면 그 바닥. */
	NavWorld.Floor bankFloor(Vector3f p, Vector3f cand) {
		float d = Math.max(p.distance(cand), LEDGE_WINDOW);
		float top = cand.y + FOOT + Math.max(d - FOOT, 0);
		float bottom = cand.y - d;
		Vector3f f = forward(body.yaw());
		NavWorld.Floor best = null;
		for (int i = 0; i < 2; i++) {
			float x = cand.x + (i == 0 ? 0 : f.x * BODY), z = cand.z + (i == 0 ? 0 : f.z * BODY);
			NavWorld.Floor fl = world.floor(x, top, z, top - bottom);
			if (fl != null && !fl.has(SurfaceAttr.WATER) && !fl.has(SurfaceAttr.NO_DIVING) && fl.y() > waterY - SWIM_DEPTH
					&& (best == null || fl.y() > best.y())) {
				best = fl;
			}
		}
		return best;
	}

	/** 잠수 돌진: 충전 중 (+0x2D8 0x40000), 돌진 중 (0x80000), 충전 시간·조종 시간 (+0x2B8), 속도 (+0x2C4). */
	private boolean dashCharging, dashing;
	private float dashCharge, dashTime, dashSpeed;

	public Water water() {
		return water;
	}

	/** 수면 높이 (물 상태일 때). */
	public float waterSurface() {
		return waterY;
	}

	/** 지금 공중 다이빙 자세인지 (+0x2D8 0x100000). */
	public boolean divingIn() {
		return divingIn;
	}

	/**
	 * 깊은 물: 수면 아래 SWIM_DEPTH (28.16) 안에 단단한 바닥이 없다. 원작은 내비 위치 (발 + 6.4) 가 수면 − 21.76 아래로 내려가야 물 상태가 되므로
	 * (0x1E57D8) 그보다 얕으면 바닥에 서서 걷는다 [확실: 기준 높이]. 예: ATHN08A/08C 아레스 만은 Water 면(y 1) 아래 7.7 에 바다 바닥 —
	 * 거인 크레토스가 물을 헤치고 싸운다.
	 */
	private boolean deepWater(Vector3f p, float surface) {
		NavWorld.Floor bed = world.floor(p.x, surface - 0.5f, p.z, SWIM_DEPTH);
		return bed == null || bed.has(SurfaceAttr.WATER);
	}

	/**
	 * 물 들어가기 (0x1CA098): 아직 떠 있는 높이보다 위면 공중 물리로 계속 떨어지다가 (plunging) 그 높이를 지나면 수직 속도 × 0.4 (최소 −64) 로 SINK,
	 * 처음부터 그 높이 아래면 바로 TREAD 로 맞춘다 (0x1E57D8 a3 = 1: 위에 있으면 수면 맞춤).
	 */
	private void enterWater(NavWorld.Floor surface, float vy) {
		state = NavState.SWIM;
		stateTime = 0;
		waterY = surface.y();
		waterNoDiving = surface.has(SurfaceAttr.NO_DIVING);
		Vector3f l = body.launch();
		swimSpeed = (float) Math.hypot(l.x, l.z);
		Vector3f p = body.position();
		float line = waterY - SWIM_DEPTH;
		diveVy = 0;
		dashCharging = dashing = false;
		noDiveFall = false;
		if (vy < 0 && p.y > line) {
			water = Water.SINK;
			plunging = true;
			waterVy = vy;
		} else {
			water = Water.TREAD;
			plunging = false;
			waterVy = 0;
			p.y = line;
		}
		body.setVy(0);
		body.setGrounded(false);
		body.launch().zero();
		body.play(waterAnim(), true);
	}

	/** 원작 가속 도우미 0x1E5FE0: 목표가 0 이 아니면 |목표| ≥ min, 같은 부호로 커지면 acc, 아니면 dec 만큼 다가감 [확실]. */
	static float accel(float target, float cur, float acc, float dec, float min) {
		if (Math.abs(target) > 1e-4f) {
			target = target < 0 ? Math.min(target, -min) : Math.max(target, min);
		} else {
			target = 0;
		}
		if (cur < target) {
			return Math.min(target, cur + (target > 0 ? acc : dec));
		}
		return Math.max(target, cur - (target < 0 ? acc : dec));
	}

	private void swim(Input in, float camYaw, float dt) {
		Vector3f p = body.position();
		float mag = stickMag(in);
		// 물속 버튼 (0x1D7FD0, +0x174 0x380): 논리 0x1000 (□) → +0x310 0x40 아래, 0x2000/0x4000 (△/×) → 0x20 위, 잠수 중 0x800 (R1) → 0x800 돌진 [확실]
		boolean down = in.held(PadButton.SQUARE), up = in.held(PadButton.TRIANGLE) || in.held(PadButton.CROSS);
		// 점프: 물 위 떠 있기 (+0x174 0x80) 에서만 × (0x1E9C2C → 0x1E9E88: 수직 속도 += Sold+0xC4) [확실]
		if (water == Water.TREAD && in.pressed(PadButton.CROSS)) {
			Vector3f f = forward(body.yaw()).mul(swimSpeed);
			leave(f, JUMP, swimSpeed < NavTuning.UNITS_PER_METER ? "ANIVJump" : "ANIJump");
			return;
		}
		// 수평: 정면을 스틱 쪽으로 프레임당 Sold+0x1E8 (0x1CA5F4), 목표 = (떠 있기 +0x1D8 / 그 밖 +0x1D4) × 스틱 (최소 +0x1DC), 가감속 +0x1E0/+0x1E4
		float target = 0;
		if (mag > 0.2f) {
			float want = yawOf(stickDir(in, camYaw));
			float d = (float) Math.IEEEremainder(want - body.yaw(), 2 * Math.PI);
			body.setYaw(body.yaw() + d * Math.min(1f, SWIM_TURN * 60f * dt));
			target = (water == Water.TREAD ? TREAD_SPEED : SWIM_SPEED) * mag;
		}
		swimSpeed = Math.max(0, accel(target, swimSpeed, SWIM_ACCEL * dt, SWIM_DECEL * dt, target > 0 ? SWIM_MIN : 0));
		// 수직
		if (water == Water.DIVE) {
			dashUpdate(in, dt);
			// 0x1CAA58: 목표 = □ 아래 / △·× 위 × +0x1D4 (돌진 조종 중엔 돌진 속도, 가감속도 같은 비율)
			float vmax = SWIM_SPEED, k = 1;
			if (dashing && dashTime >= 1) {
				vmax = dashSpeed;
				k = dashSpeed / SWIM_SPEED;
			}
			diveVy = accel(down ? -vmax : up ? vmax : 0, diveVy, SWIM_ACCEL * k * dt, SWIM_DECEL * k * dt, 0);
			if (dashCharging) {
				// 충전 중 감속: 속도 ×= 1 − 0.1 × 60·dt (0x1CAAC4)
				float s = Math.max(0, 1 - 0.1f * 60f * dt);
				swimSpeed *= s;
				diveVy *= s;
			}
			waterVy = diveVy;
		} else if (plunging) {
			waterVy = Math.max(-FALL_MAX, waterVy - GRAVITY * dt);
		} else {
			waterVy += BUOYANCY * dt;
		}
		Vector3f fwd = forward(body.yaw());
		Vector3f cand = new Vector3f(p).fma(swimSpeed * dt, fwd).add(0, waterVy * dt, 0);
		// 물가 (0x1EA5F0 → 물 상태 이동 뒤 0x1CA198) [확실]: 바닥 질의 0x1E3298 (Water·NoDiving 제외 마스크 0x8020000) 의 창 =
		// 내비 위치 (+ max(d − 6.4, 0)) 에서 발 − d 까지 (d = max(이동, 3.2)). 바닥이 맞고 그 위 내비 위치가 수면 − 21.76 (떠 있는 높이) 보다
		// 높으면 = 바닥 &gt; 수면 − 28.16 이면 착지 (0x1E9470). 몸 충돌 0x1E2100 에 계단 오르기는 없으므로 떠 있는 발보다 6.4 넘게 높은 물가는
		// 못 오른다 — 비탈·얕은 턱·사다리/Climbable 벽으로 나온다. 엔진 표본 = 몸 가운데 + 정면 9.6 [근사: 원작 4–5 점]
		NavWorld.Floor bank = bankFloor(p, cand);
		if (bank != null) {
			p.set(cand.x, bank.y(), cand.z);
			land(null);
			return;
		}
		if (water != Water.DIVE && swimSpeed > 0) {
			// 물 위에서 벽 오르기 (0x1C7DA8: +0x174 0x80 이고 앞으로 가면 0x1E47E0 → 0x1C7280) [확실]
			if (water == Water.TREAD) {
				WallProbe w = probeWall(p, fwd, null);
				if (w != null && w.n().dot(fwd) < -0.6f) {
					p.y += 1.6f; // 0x1C7E54: 위치 y += 1.6, 수직 속도 0.1
					enterClimb(w, w.tri().has(SurfaceAttr.LADDER));
					return;
				}
			}
		}
		// 벽 밀어내기 (몸 구), 물 밑바닥
		float off = water == Water.DIVE ? HANG * 0.5f : SWIM_DEPTH;
		Vector3f c = new Vector3f(cand).add(0, off, 0);
		world.collideSphere(c, BODY);
		cand.x = c.x;
		cand.z = c.z;
		NavWorld.Floor bed = world.floor(cand.x, Math.max(cand.y, p.y) + FOOT, cand.z, 400);
		if (bed != null && !bed.has(SurfaceAttr.WATER) && cand.y < bed.y()) {
			cand.y = bed.y();
			waterVy = Math.max(0, waterVy);
			diveVy = Math.max(0, diveVy);
		}
		// 수면 따라가기 (원작은 매 프레임 0x1E57D8 로 +0x230 갱신)
		NavWorld.Floor s = world.floor(cand.x, waterY + 8, cand.z, 16);
		if (s != null && s.has(SurfaceAttr.WATER)) {
			waterY = s.y();
			waterNoDiving = s.has(SurfaceAttr.NO_DIVING);
		}
		float line = waterY - SWIM_DEPTH;
		// 물 상태 전환 (0x1C9BB8)
		if (water == Water.DIVE) {
			// 0x1E57D8 a3 = 1 (떠 있기 아님): 판정 높이보다 위 (f12 &lt; y) 일 때만 수면
			if (cand.y > line + 1e-3f) {
				if (waterVy >= LEAP_MIN_VY) {
					// 물 밖 도약 (0x1C9750): 공중 + 다이빙 (+0x2D8 0x100000), ANIDive1, 속도 × min(2, 점프 × 1.5 / 수직 속도)
					float k = Math.min(LEAP_GAIN_MAX, JUMP * LEAP_JUMP_SCALE / waterVy);
					p.set(cand.x, line, cand.z);
					leave(new Vector3f(fwd).mul(swimSpeed * k), waterVy * k, "ANIDive1");
					divingIn = true;
					return;
				}
				water = Water.TREAD;
				cand.y = line;
				waterVy = diveVy = 0;
				dashCharging = dashing = false;
			}
		} else {
			if (plunging && cand.y <= line) {
				plunging = false;
				waterVy = Math.max(waterVy * ENTRY_DAMP, ENTRY_MIN_VY);
			}
			if (!plunging) {
				// 0x1E57D8 a3 = 1: 떠 있기 중이거나 판정 높이 위면 수면 높이로 맞추고 수직 속도 0 → TREAD, 아니면 SINK
				if (water == Water.TREAD || cand.y >= line) {
					cand.y = line;
					waterVy = 0;
					water = Water.TREAD;
				} else {
					water = Water.SINK;
				}
				if (waterVy >= 0) {
					divingIn = false; // 0x1C9C6C: 수직 속도 ≥ 0 이면 다이빙 끝
				}
				// 잠수 (0x1C9CC8 / 0x1C9D18): 잠수 능력 (+0x2DC 0x20) + □ (+0x310 0x40) + 지역 수직 속도 ≤ 0 [확실: 조건 / 추정: 영웅은 처음부터 잠수 가능]
				if (down) {
					water = Water.DIVE;
					diveVy = Math.min(0, waterVy);
					waterVy = diveVy;
				}
			}
		}
		p.set(cand);
		body.play(waterAnim(), false);
	}

	/** 잠수 돌진 (0x1C9E70 – 0x1CA094, 0x1C9F58): 돌진 중이면 조종 시간을 줄이고, 아니면 R1 충전 → 떼면 돌진 [확실: 식 / 추정: 영웅은 돌진 능력 +0x2DC 0x200000 있음]. */
	private void dashUpdate(Input in, float dt) {
		if (dashing) {
			dashTime -= dt;
			if (dashTime <= 0) {
				dashing = false;
			}
			return;
		}
		if (in.held(PadButton.R1)) {
			if (!dashCharging) {
				dashCharging = true;
				dashCharge = 0;
			} else {
				dashCharge += dt;
			}
			return;
		}
		if (!dashCharging) {
			return;
		}
		dashCharging = false;
		float k = Math.max(0, Math.min(1, (dashCharge - DASH_CHARGE_MIN) / (DASH_CHARGE_MAX - DASH_CHARGE_MIN)));
		if (k <= 0) {
			return;
		}
		dashSpeed = DASH_MIN + (DASH_MAX - DASH_MIN) * k;
		dashTime = DASH_STEER_MIN + (DASH_STEER_MAX - DASH_STEER_MIN) * k + 1;
		dashing = true;
		// 지역 속도 = (0, 수직 × r, (+0x1D4 − |수직|) × r), r = 돌진 / +0x1D4
		float r = dashSpeed / SWIM_SPEED;
		swimSpeed = Math.max(0, SWIM_SPEED - Math.abs(diveVy)) * r;
		diveVy *= r;
	}

	/** 물속 정면 속도 (+0x260.z) / 수직 속도 (월드). */
	public float swimSpeed() {
		return swimSpeed;
	}

	public float waterVy() {
		return waterVy;
	}

	/** 잠수 돌진 중인지 / 충전 중인지. */
	public boolean dashing() {
		return dashing;
	}

	public boolean dashCharging() {
		return dashCharging;
	}

	/** 물 애니 (0x1C93B8 / 0x1C94B0 / 0x1C95B0): 속도 1.6 이하 = 멈춤 애니. */
	private String waterAnim() {
		float speed = (float) Math.hypot(swimSpeed, water == Water.DIVE ? diveVy : 0);
		return switch (water) {
			case TREAD -> anims.get(speed <= 1.6f ? "ANITread" : "ANITreadSwim");
			case SINK -> divingIn ? anims.get(waterVy >= 0 ? "ANISwim" : "ANIDive2") : anims.get(swimSpeed <= 1.6f ? "ANITread" : "ANISwim");
			case DIVE -> dashCharging ? anims.get("ANIDiveDashC")
					: dashing || speed > SWIM_SPEED + NavTuning.UNITS_PER_METER ? anims.get("ANIDiveDash")
					: anims.get(speed <= 1.6f ? "ANISwimIdle" : "ANISwim");
		};
	}

	/**
	 * 공중 다이빙 자세 (0x1CA318, 공중 갱신): 낙하가 점프 초속보다 빠르거나 앞 속도 &gt; 80 이면 0.4 / 0.35 초 뒤까지의 포물선 궤적 선분 (0x1C98D8) 이
	 * 처음 만나는 면이 NoDiving 이 아닌 물이면 ANIDive1 (0x1C9750, +0x2D8 0x100000), 다른 면 (NoDiving 물·땅) 이면 이번 낙하는 다이빙 안 함 (0x200000)
	 * [확실: 조건 / 근사: 원작은 0.35–0.4 구간 선분 다음 시작점 선분].
	 */
	private void airDive(Vector3f p) {
		if (divingIn || noDiveFall) {
			return;
		}
		Vector3f l = body.launch();
		float t = body.vy() < -JUMP ? DIVE_LOOK_FALL : Math.hypot(l.x, l.z) > DIVE_RUN_SPEED ? DIVE_LOOK_RUN : -1;
		if (t < 0) {
			return;
		}
		Vector3f to = new Vector3f(p).fma(t, l).add(0, body.vy() * t - 0.5f * GRAVITY * t * t, 0);
		LevelCollision.Hit h = world.segment(p, to);
		if (h == null) {
			return;
		}
		if (h.tri().has(SurfaceAttr.WATER) && !h.tri().has(SurfaceAttr.NO_DIVING)) {
			divingIn = true;
			body.play(anims.get("ANIDive1"), true);
		} else {
			noDiveFall = true;
		}
	}

	// ---- 밀기 / 상황 동작 ----

	/** 상황 동작 영웅 애니 단계: TAndF 잡기 (ANITandF1) / 본 동작 (ANICSM1·leverpull·ANITandF2) / 끝 (ANICSM2·ANITandF3). */
	static final int PH_GRAB = 0, PH_MAIN = 1, PH_FINISH = 2;
	/** 단계 끝 판정 여유 (영웅 애니와 오브젝트 애니 길이가 같으므로 같은 틱에 끝나게). */
	private static final float EPS = 1e-4f;

	/** 잡은 손잡이 {x, y, z, 축 x, 축 z} (없으면 null — 중심 쪽을 봄 [근사]). */
	private float[] handle;

	/**
	 * 붙은 자리 (0x1C4318–0x1C470C, 매 프레임): 영웅 = 손잡이 수평 위치 + 축·9.6 (몸 반폭), 정면 = −축 (바로, 블렌드 없음). 높이는 그대로
	 * (TAndF 모드 0x10). 영웅 애니는 자기 좌표계 — trapDoorEnter/TandF 의 손 (앞 −12.5, 높이 7–8.5) 이 손잡이 (높이 9.42) 에 닿는다.
	 */
	private void alignToHandle(Vector3f p) {
		if (handle == null || item == null || item.handles.isEmpty()) {
			return;
		}
		p.x = handle[0] + handle[3] * Interactables.GRAB_FWD;
		p.z = handle[2] + handle[4] * Interactables.GRAB_FWD;
		body.setYaw(yawOf(new Vector3f(-handle[3], 0, -handle[4])));
	}

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
				charging = false;
				pushVy = 0;
				if (DEBUG_PUSH) {
					System.out.printf("[밀기 잡음] %s 상자 %s 영웅 %s 손잡이 %s%n", it.owner, it.pos, p, handle == null ? "-" : java.util.Arrays.toString(handle));
				}
				kickAnimTime = -1;
				if (handle != null && Math.abs(handle[3]) + Math.abs(handle[4]) > 1e-3f) {
					// 잡은 손잡이 관절 (handle1/2, 0x1C3918) 을 향해 서고 그 축이 상자 국소 축 — 영웅 = 손잡이에서 몸 반 깊이 바깥 (0x1C4638: 붙잡기 관절
					// − 위 × 반 키 + 정면 × 반 깊이) [확실: 코드 / 근사: 반 깊이 = 몸 반 너비]. 판 위에서 돌아간 발리스타도 그 옆면 축으로 민다.
					// 손잡이 관절이 없는 상자 (석상·판도라 상자·테트리스 블록 …) 는 몸 옆면 가운데가 잡을 곳이다 (Interactables.faceGrab, 0x1C3CD8 / 0x1C43C0)
					pushAxis.set(-handle[3], 0, -handle[4]).normalize();
					p.x = handle[0] + handle[3] * BODY;
					p.z = handle[2] + handle[4] * BODY;
					grabHeight = handle[1] - it.pos.y;
				} else {
					grabHeight = Float.NaN;
					// Push 템플릿 Radius 도 손잡이 관절도 없는 상자 (합성 시험 상자뿐): 영웅→상자 방향의 주축 (x 또는 z), 상자 경계구 반지름 + 몸 반 너비 뒤 [근사]
					if (Math.abs(to.x) > Math.abs(to.z)) {
						pushAxis.set(Math.signum(to.x), 0, 0);
					} else {
						pushAxis.set(0, 0, Math.signum(to.z));
					}
					float gap = it.radius + BODY;
					p.x = it.pos.x - pushAxis.x * gap;
					p.z = it.pos.z - pushAxis.z * gap;
				}
				body.setYaw(yawOf(pushAxis));
				listener.pushAttach(it);
				body.play(anims.get("ANIPushI"), true);
			}
			default -> {
				state = NavState.INTERACT;
				body.setYaw(yawOf(to.normalize()));
				alignToHandle(p);
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
				if (it.kind == Interactables.Kind.LIFT && it.slide) {
					it.slideBase = it.slideY; // 붙는 순간 처음 y = 지금 GO y (0x2145A4) [확실]
				}
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
			if (it.resetting || it.cannonFired) {
				continue; // 스크립트 1015 리셋·대포 발사 마무리는 끝날 때까지 (idle 이 플래그를 내린다)
			}
			if (it.kind == Interactables.Kind.LIFT && it.slide) {
				if (it.slideSettled()) {
					i.remove(); // 미끄럼 문은 다 오르거나 처음 자리에 내려앉을 때까지 갱신
				}
				continue;
			}
			if (it.pushType == 4 || it.pushType == 3 ? it.task().speed == 0 : it.task().time <= 0 || it.task().done) {
				i.remove();
			}
		}
	}

	/**
	 * 스크립트 이벤트 1015 (EventTransmitter → 오브젝트, 원작 PushPull 메시지 0x266040 → 0x2100E0, 표 0x2B9420) [확실: 디스어셈블]:
	 * 크랭크·대포·링 (Type 1/2/4) = 잡기 클래스 0x1000 끄고 +0x29A 비트 8 → 되감기 가속 #b6ba95a3 로 0 까지 (템플릿 +0x108 비트 2 면 곧바로 0)
	 * → 0 에서 다시 잡힘 (0x212C08); 레버·물속 레버 (5/6) = 속도 −4 → 0 에서 다시 잡힘 (0x2127B0); 상자 (0/7/8) = 잡기 클래스 다시 켬.
	 * 기중기 (3) 는 인자 0 % 자리로 가는 별도 동작 (0x21016C) — 데이터에 1015 를 받는 기중기가 없어 하지 않는다.
	 * 데이터: ATHN02C Gunlever-EventTmid (회전판 레버, 100 % 3.5 초 뒤), BigGun1Crank-EvtReset (발리스타 크랭크, 100 % 에).
	 */
	public void scriptReset(Interactables.Item it) {
		if (it.kind == Interactables.Kind.PUSH) {
			it.done = false;
			return;
		}
		if (it.kind == Interactables.Kind.LIFT) {
			// IO_TandF 메시지 0x266420: 1015 → 0x213E30 = 위치 0, 잡기 클래스 0x80200000 다시 켬, +0x1F4 비트 2 끔 [확실]
			// → 놓인 미끄럼 문은 처음 자리까지 떨어진다 (0x214180). 예: ATHN04C 나무 게이트 — DoorVolume 을 나가면 LiftDoor13-EventTransmitter1
			it.task().reset();
			it.done = false;
			it.openTime = 0;
			if (it != item && !released.contains(it)) {
				released.add(it);
			}
			return;
		}
		if (it.kind != Interactables.Kind.LEVER || it.pushType == 3) {
			return;
		}
		if (it == item && (state == NavState.INTERACT || state == NavState.PUSH)) {
			release(it); // 붙은 채 리셋 (원작은 클래스 비트가 꺼져 다음 프레임 놓음, 0x1C4D10)
		}
		it.resetting = true;
		it.cannonKick = false;
		it.cannonFired = false;
		if (!released.contains(it)) {
			released.add(it);
		}
	}

	/** 미끄럼 문 중력 = /GlobGame/ "Gravity" × 16 (0x214210 / 0x2142E0, 유닛/초²) [확실]. */
	public static final float SLIDE_GRAVITY = NavTuning.GLOBAL_GRAVITY * 16f;

	/**
	 * 미끄럼 문이 붙은 영웅을 따라감 (0x214058): y = 처음 y + 영웅 synchJoint 모델 공간 y (원작 모델 인스턴스 +0x50 행렬의 관절 +0xEA),
	 * 속도 y = Δy / dt (+0xE0) [확실: 디스어셈블]. 영웅 자세를 모르면 (헤드리스) 그대로.
	 */
	private void slideFollow(Interactables.Item it, float dt) {
		float sy = body.jointModelY(Interactables.SYNCH_JOINT);
		if (Float.isNaN(sy) || dt <= 0) {
			return;
		}
		float ny = it.slideBase + sy;
		it.slideVy = (ny - it.slideY) / dt;
		it.slideY = ny;
	}

	/** 영웅이 떨어진 오브젝트 한 프레임: CSM/레버 −4 로 되감기, 크랭크 #b6ba95a3 가속으로 되감기, TAndF 내려옴. */
	private void idle(Interactables.Item it, float dt) {
		TaskProgress t = it.task();
		if (it.kind == Interactables.Kind.LIFT && it.slide) {
			// 0x214180: 놓인 미끄럼 문 — 완료 (위치 2) 면 끝까지 오르고, 아니면 위치·속도 0 (곧바로, 0x2142B8) 에 처음 자리까지 떨어진다 [확실]
			if (!t.done) {
				t.time = 0;
				t.speed = 0;
				send(it, t.lift(false, false, 0, it.liftParams(), dt));
			}
			it.slideIdle(dt, SLIDE_GRAVITY);
			return;
		}
		if (it.resetting) {
			// 1015 리셋 (scriptReset): 크랭크류 = 되감기 가속 (비트 2 면 곧바로), 레버 = −4
			Float spd = it.crank ? (it.resetSnap ? null : Math.max(t.speed - it.rewindAcc * dt, -it.maxBack)) : TaskProgress.REWIND_SPEED;
			send(it, t.rewind(spd, dt));
			if (t.time <= 0) {
				it.resetting = false;
				it.done = false;
			}
			return;
		}
		if (it.cannonFired) {
			// 대포 (0x212B80): 놓인 뒤 첫 갱신 — 끝 시간 그대로 비트 2 → 100 %, 크랭크 클래스 끔 (템플릿 +0x108 비트 1 이면 그대로) [확실]
			it.cannonFired = false;
			send(it, t.fire());
			it.done = !it.keepAtEnd;
			if (it.keepAtEnd) {
				t.done = false;
			}
			return;
		}
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
		if (it.kind != Interactables.Kind.PUSH) {
			alignToHandle(body.position()); // 원작은 매 프레임 손잡이 관절을 다시 읽어 붙인다 (0x1C4258)
		}
		boolean held = in.held(PadButton.USE);
		TaskProgress t = it.task();
		switch (it.kind) {
			case LEVER -> {
				if (it.cannon()) {
					cannon(it, in, held, camYaw, dt);
					return;
				}
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
				}
				// 붙잡을 때 마지막 누름 시간 0 (0x1C3590) → 첫 틱에 한 번 가속. 누름 시간은 ANITandF2 동안만 0 으로 (0x1C4A9C)
				boolean impulse = stateTime <= dt + EPS || phase == PH_MAIN && press;
				sincePress = impulse ? 0 : sincePress + dt;
				boolean wasDone = it.done;
				send(it, t.lift(true, impulse, sincePress, it.liftParams(), dt));
				if (phase == PH_MAIN && !it.done) {
					// 이동 0x60 ANITandF2 = 매개변수 min(문 위치, 1) → 영웅 애니 시간 = 위치 × 길이, 속도 0 (0x1ECA44 → 0x1F77B4) [확실]
					body.scrub(it.heroAnim, Math.min(t.time, 1f) * Math.max(0, clip(it.heroAnim, 0)));
				}
				if (it.slide) {
					if (!wasDone) {
						slideFollow(it, dt); // 0x214058: 붙어 있고 위치 < 2 (완료 프레임 포함)
					} else {
						it.slideIdle(dt, SLIDE_GRAVITY); // 완료 (위치 2) 뒤 ANITandF3 동안에도 문은 끝까지 오른다 (0x214180)
					}
				}
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

	/**
	 * 대포 크랭크 (Push Type 2, 0x212998 모드 3 — ATHN02C 발리스타 AthnGun, ATHN02B 작살 AthensHarpoon) [확실: 디스어셈블].
	 * <pre>
	 * 감기: 크랭크와 같다 — R2 를 누른 채 손잡이 쪽으로 밀면 속도 += Acc·dt (최대 #fe467fe6), 안 밀면 −Dec·dt (0 까지), R2 를 떼면 놓음 (되감김).
	 * 끝: 오브젝트는 끝 시간에서 속도 0 으로 기다리고 99 % 만 보낸다 (0x212AD0). 영웅 +0x2D8 |= 0x20000 → 애니 슬롯 0x53 ANIStartKickBox (0x1C36D4).
	 *     이때부터 R2 를 보지 않는다 (0x1C4D7C: 0x20000 경로는 사용 버튼 검사 0x1C4828 을 건너뜀).
	 * 발사: 영웅 애니가 슬롯 0x54 (ANIKickBoxIdle, 0x53 이 끝나면 다음 슬롯) 가 되면 0x20000 을 지우고 오브젝트 +0x29A 비트 2 → 놓음 (0x1C4DA0 → 0x1C4928
	 *     → 0x1C4D00 0x1E18F8, 1049). 놓인 오브젝트의 다음 갱신이 끝 시간 그대로 100 % (1023) 를 보내고 크랭크 클래스를 끈다 (0x212B80).
	 *     스크립트가 1023(100, id) 로 발사 (1020 → 화살 이미터) 와 1015 리셋 (되감기 → 다시 잡힘) 을 한다.
	 * </pre>
	 */
	private void cannon(Interactables.Item it, Input in, boolean held, float camYaw, float dt) {
		TaskProgress t = it.task();
		if (it.cannonKick) {
			if (phaseTime >= clip(anims.get("ANIStartKickBox"), 0.5f) - EPS) {
				it.cannonKick = false;
				it.cannonFired = true;
				release(it); // 슬롯 0x54 에 닿은 프레임: 비트 2 + 놓음 → rewindReleased → idle → fire() (100 %)
			}
			return;
		}
		if (!held || it.done) {
			release(it);
			return;
		}
		int input = crankInput(in, camYaw);
		boolean turning = input > 0;
		float spd = turning ? Math.min(t.speed + it.acc * dt, it.maxSpeed)
				: t.time > 0 ? Math.min(Math.max(t.speed - it.dec * dt, 0), it.maxSpeed) : 0;
		send(it, t.cannon(spd, dt));
		if (t.wound()) {
			it.cannonKick = true;
			phaseTime = 0;
			body.play(anims.get("ANIStartKickBox"), true);
			return;
		}
		body.play(turning ? anims.get("ANICrankPush") : it.heroAnim, false);
	}

	/** 놓기 = 원작 0x1E18F8 (1049). 끝나지 않은 오브젝트는 스스로 되감긴다 ({@link #rewindReleased}). */
	private void release(Interactables.Item it) {
		listener.pushDetach(it);
		if (!it.done || it.kind == Interactables.Kind.LIFT && it.slide && !it.slideSettled()) {
			released.add(it); // 다 든 미끄럼 문도 끝까지 오를 때까지 (0x214180)
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
		lastDt = dt;
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
			if (grabTooFar(it)) {
				detachPush(it);
			}
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
		// 영웅 국소 속도 = 스틱의 옆·정면 성분 그대로 × Sold+0x188[무게], 길이는 최소 +0x17C[무게] (0x1C448C – 0x1C4630) — 축 방향만이 아니라 옆으로도
		// 간다. 붙은 상자는 영웅 이동량 (영웅 +0x100 의 xz) 을 그대로 따르고 속도는 0 (0x212398 → 0x2119A8, +0x174 0x8000) [확실: 코드] — 그래서
		// 상자 옆 손잡이 (handle1/2, 로컬 ±x) 에 붙은 채 옆걸음으로 밀어 ATHN05B 발리스타를 복도 끝에서 북쪽 홀로 옮긴다 (영상 7738 – 7752).
		// 예전 엔진은 손잡이 축 성분만 써서 발리스타가 홀로 못 나갔다 (43 §22).
		float mag = stickMag(in);
		Vector3f v = new Vector3f();
		if (mag > 0.2f) {
			float sp = Math.max(pushSpeedMin(it.weight), Math.min(pushSpeedMax(it.weight), mag * pushSpeedMax(it.weight)));
			v.set(stickDir(in, camYaw)).mul(sp);
		}
		boolean kicking = kickAnimTime >= 0 && (kickAnimTime += dt) < clip(kick, 0);
		boolean attachedMove = v.lengthSquared() > 0 && !kicking;
		if (attachedMove) {
			it.vel.set(v);
		} else {
			boxFriction(it, dt); // 찬 상자 (상태 2) 만 제 속도로 미끄러진다 (0x211A10)
		}
		Vector3f moved = moveBox(it, dt);
		if (attachedMove) {
			it.vel.zero(); // 붙은 동안 상자 속도 0 (0x2119F8) — 놓아도 미끄러지지 않는다
		}
		follow(moved);
		if (grabTooFar(it)) {
			detachPush(it);
			return;
		}
		if (!kicking) {
			kickAnimTime = -1;
			String a = anims.get("ANIPushI");
			if (attachedMove && moved.lengthSquared() > 0) {
				// 정면 / 뒤 / 옆 (오른쪽 = (−f.z, f.x)) 중 큰 성분 — Push / Pull / PullL / PullR (애니 슬롯 0x4F – 0x52) [추정: 고르는 규칙]
				float fw = v.x * pushAxis.x + v.z * pushAxis.z, rt = -v.x * pushAxis.z + v.z * pushAxis.x;
				a = Math.abs(fw) >= Math.abs(rt) ? anims.get(fw > 0 ? "ANIPushF" : "ANIPushB") : anims.get(rt > 0 ? "ANIPushR" : "ANIPushL");
			}
			body.play(a, false);
		}
	}

	/**
	 * 영웅이 상자 이동량만큼 따라간다. 밀기 중에도 원작 영웅은 중력 (0x1C47B0–0x1C47DC: +0xE0.y −= 50·16·dt) 과 바닥 탐침 (0x1C4DE0 → 0x1EA5F0
	 * → 0x1E3298) 을 거쳐 바닥에 붙는다 [확실: 코드 / 근사: 엔진은 발밑 바닥 높이로 바로 맞춤]. 예전 엔진은 높이를 두지 않아 ATHN05B 발리스타 복도
	 * 내리막 (x −450 → −220, 480 → 444) 에서 영웅이 발리스타 위 허공에 떠 있다가 놓는 순간 34 를 떨어졌다 (43 §22).
	 */
	private void follow(Vector3f moved) {
		Vector3f p = body.position();
		Vector3f to = new Vector3f(p).add(moved.x, 0, moved.z);
		if (world.blockMove(p, to, FOOT, NavWorld.moveExclude(NavWorld.HERO_BODY_EXCLUDE)) && item != null) {
			// 원작은 영웅의 실제 이동량 (영웅 +0x100, 영웅 쪽 충돌 뒤) 으로 붙은 상자를 옮긴다 (0x2119A8) — 영웅이 벽·높은 단의 앞면에 막히면
			// 상자도 그만큼만 간다. 예전 엔진은 영웅 쪽 충돌 없이 상자 이동량을 그대로 따라가 계단 단 앞면 안으로 들어가 빠졌다
			Vector3f back = new Vector3f(to.x - p.x - moved.x, 0, to.z - p.z - moved.z);
			if (back.lengthSquared() > 1e-8f) {
				item.pos.add(back);
				listener.pushMoved(item, back);
			}
		}
		p.x = to.x;
		p.z = to.z;
		NavWorld.Floor f = world.floor(p.x, p.y + 16, p.z, 4000);
		if (f != null && (p.y - f.y() <= 16 && pushVy == 0 || p.y + pushVy * lastDt <= f.y())) {
			p.y = f.y();
			pushVy = 0;
		} else {
			// 발밑 16 보다 깊은 단 (ATHN06B 석상 단 789 → 계단 765) · 낭떠러지: 중력으로 떨어진다 — 예전 엔진은 바닥을 못 찾으면 높이를 그대로 둬
			// 계단 위 허공을 걸어 석상과 같은 높이에 머물렀다
			pushVy = Math.max(pushVy - GRAVITY * lastDt, -FALL_MAX);
			p.y += pushVy * lastDt;
		}
	}

	/** 밀기 중 영웅 낙하 속도 (원작 +0xE0 의 y, 0x1C47B0) 와 이번 틱 dt. */
	private float pushVy, lastDt = 1f / 60;

	static final boolean DEBUG_PUSH = System.getProperty("gow.debug.push") != null;
	private NavState dbgState;

	/** 밀기 놓기 높이 차 (0x1C46B0: 0x42000000) [확실]. */
	static final float PUSH_DETACH_DY = 32f;

	/**
	 * 0x1C4674 – 0x1C46C4: 밀기 (모드 1) 동안 영웅 높이와 잡은 점 − 반 키 (17.6) 의 차가 32 를 넘으면 놓는다 (0x1E18F8) [확실] — 붙은 상자는 바닥을 따르지 않고
	 * 떠 있으므로 (0x2119A8 은 수평 이동량만) 계단 아래로 끌고 내려가면 영웅이 내려간 만큼 벌어져 놓이고, 놓인 상자는 중력으로 떨어진다 ({@link #boxFall}).
	 */
	private boolean grabTooFar(Interactables.Item it) {
		return !Float.isNaN(grabHeight) && Math.abs(body.position().y - (it.pos.y + grabHeight - Interactables.GRAB_UP)) > PUSH_DETACH_DY;
	}

	private void detachPush(Interactables.Item it) {
		if (DEBUG_PUSH) {
			System.out.printf("[밀기 놓음] %s 상자 %s 영웅 %s 잡은높이 %.1f%n", it.owner, it.pos, body.position(), grabHeight);
		}
		listener.pushDetach(it);
		charging = false;
		kickAnimTime = -1;
		if (!sliding.contains(it)) {
			sliding.add(it); // 미끄러지거나 (차기) 떠 있으면 떨어진다 — 멈추고 바닥에 닿으면 slideBoxes 가 뺀다
		}
		end();
		if (pushVy < 0) {
			leave(new Vector3f(), pushVy, "ANIFall"); // 단을 내려가다 놓이면 떨어지는 채로 (공중 상태가 착지시킨다)
			pushVy = 0;
		}
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

	/** 상자 둘레 점 벽 판정 구 반경 (유닛) [근사] — Push 템플릿이 없는 합성 상자만. */
	static final float BOX_PROBE_R = 6f;
	/** 상자 몸 +0x130 의 y (0x20FA50: 6.4) — 위 둘레 높이 +0x140 = 몸 높이 × 16 − 6.4 (0x20FA84). */
	static final float BOX_LIFT = 6.4f;
	/**
	 * 상자 벽 질의가 지나가는 면 (0x210BBC – 0x210BEC: 0x3309F4 = 0x08030042 | GO +0x258 &amp; 0x04300000) — 물·Death·AIBlock·ClimbGuide·CombatGuide
	 * [확실], NoPushPullCollision [추정: +0x258 비트 26 이 밀기 상자에 켜짐].
	 */
	static final long BOX_WALL_EXCLUDE = SurfaceAttr.fromRecord(0x08030042, 0, 0) | SurfaceAttr.NO_PUSH_PULL_COLLISION.bit();
	/** 아래 둘레 점 (GO + 6.4, 0x210C48 – 0x210D08 순서): −z 가운데, +x−z, +x, −x−z, −x, +x+z, −x+z, +z — 반 너비 단위 (x, z). */
	private static final float[][] BOX_BASE = {{0, -1}, {1, -1}, {1, 0}, {-1, -1}, {-1, 0}, {1, 1}, {-1, 1}, {0, 1}};
	/** 위 높이 모서리 (0x210D2C – 0x210D84): −x−z, +x−z, −x+z, +x+z. */
	private static final float[][] BOX_TOP = {{-1, -1}, {1, -1}, {-1, 1}, {1, 1}};

	/**
	 * 상자 몸 벽 밀어내기 (원작 0x210DC8 → 0x210AE8) [확실: 코드]: 몸 (Push Radius·16 반 너비, GO 축) 둘레 12 점 — 물리 위치 (GO + 6.4) 높이의 옆면
	 * 가운데 넷 · 모서리 넷, 그 위 +0x140 (몸 높이 − 6.4) 높이의 모서리 넷 — 마다 중심 → 그 점 선분을 벽 (법선 y &lt; 0.5) 에 던져 (0x1E1F28 → 0x11E190), 맞으면 그 점이 벽 면에 오도록
	 * 위치를 면 법선으로 되민다 (위치 −= N·((끝 − 맞은 점)·N)). 앞 점의 보정이 다음 점에 이어진다. 그래서 벽에서 떨어지는 쪽으로는 늘 움직이고 벽 안으로는 못 들어간다.
	 * 예전 엔진은 둘레 점의 작은 구가 벽에 닿기만 하면 그 틱 이동을 막아, 벽감에 선 ATHN06B 석상 (옆 벽에 몸이 닿음) 을 당겨 꺼낼 수 없었다 (영상 9036 → 9051 은 끌어냄).
	 * @return 밀려났는지
	 */
	private boolean boxWalls(Interactables.Item it, Vector3f c) {
		float[] m = it.goMatrix;
		Vector3f ax = m != null ? new Vector3f(m[0], m[1], m[2]) : new Vector3f(1, 0, 0);
		Vector3f ay = m != null ? new Vector3f(m[4], m[5], m[6]) : new Vector3f(0, 1, 0);
		Vector3f az = m != null ? new Vector3f(m[8], m[9], m[10]) : new Vector3f(0, 0, 1);
		// 중심 = 상자 물리 위치 = GO 위치 + 위·6.4 (+0x130, 0x2119A8 이 붙은 상자 새 자리에 더함) — c 는 관절 0 기준인 아이템 위치라
		// 그 차이만큼 옮겨 질의하고 되돌린다. 바닥 높이 둘레 점이 발밑 단의 윗모서리를 스치지 않는다
		Vector3f go = m != null ? new Vector3f(m[12] - it.pos.x, m[13] - it.pos.y, m[14] - it.pos.z) : new Vector3f();
		go.fma(BOX_LIFT, ay);
		c.add(go);
		float h = it.boxHalf, top = Math.max(0f, it.boxHeight - BOX_LIFT);
		boolean hit = false;
		for (float[] p : BOX_BASE) {
			hit |= boxProbe(c, new Vector3f(ax).mul(p[0] * h).fma(p[1] * h, az));
		}
		Vector3f up = new Vector3f(ay).mul(top);
		Vector3f t = new Vector3f(c).add(up);
		for (float[] p : BOX_TOP) {
			hit |= boxProbe(t, new Vector3f(ax).mul(p[0] * h).fma(p[1] * h, az));
		}
		c.set(t).sub(up).sub(go);
		return hit;
	}

	/** 0x1E1F28: 중심 c → c + off 가 벽에 맞으면 c −= N·((c + off − 맞은 점)·N). */
	private boolean boxProbe(Vector3f c, Vector3f off) {
		Vector3f end = new Vector3f(c).add(off);
		devple.gow.engine.level.LevelCollision.Hit h = world.wallSegment(c, end, BOX_WALL_EXCLUDE);
		if (h == null) {
			return false;
		}
		Vector3f n = h.tri().n();
		float d = end.sub(h.point()).dot(n);
		c.fma(-d, n);
		return true;
	}

	/**
	 * 상자를 속도만큼 옮긴다 (밑에 바닥, 벽은 밀어냄 {@link #boxWalls}). 예전 엔진은 CDV 경계 구 0.8·r (ATHN05B 발리스타 44) 하나라 폭 84 인 방 문 (x −480) 에 걸려
	 * 영상 7731 처럼 복도로 밀고 나가지 못했다 (반 너비 28.2, 43 §22). 템플릿이 없으면 예전 구.
	 * @return 옮긴 양
	 */
	private Vector3f moveBox(Interactables.Item it, float dt) {
		Vector3f delta = new Vector3f(it.vel.x, 0, it.vel.z).mul(dt);
		if (delta.lengthSquared() < 1e-10f) {
			return delta.zero();
		}
		Vector3f cand = new Vector3f(it.pos).add(delta);
		java.util.Set<String> self = world.family(it.owner);
		boolean blocked;
		if (it.boxHalf > 0) {
			blocked = false;
			Vector3f want = new Vector3f(cand);
			if (boxWalls(it, cand)) {
				// 0x2113A0 – 0x211424: 밀려난 쪽 (수평) 으로 들어가던 속도 성분을 없앤다
				Vector3f n = new Vector3f(cand).sub(want).setComponent(1, 0);
				if (n.lengthSquared() > 1e-8f) {
					n.normalize();
					float vd = it.vel.dot(n);
					if (vd < 0) {
						it.vel.fma(-vd, n);
					}
				}
				cand.y = it.pos.y;
			}
		} else {
			Vector3f c = new Vector3f(cand).add(0, it.radius * 0.5f, 0);
			Vector3f probe = new Vector3f(c);
			blocked = world.collideSphere(probe, it.radius * 0.8f) && probe.distance(c) > 0.5f;
		}
		NavWorld.Floor f = world.floor(cand.x, it.pos.y + 16, cand.z, 32, self);
		NavWorld.Floor f0 = world.floor(it.pos.x, it.pos.y + 16, it.pos.z, 32, self);
		if (blocked) {
			it.vel.zero();
			return delta.zero();
		}
		float rest = restDy(it, f0);
		delta.set(cand.x - it.pos.x, 0, cand.z - it.pos.z);
		if (delta.lengthSquared() < 1e-10f) {
			return delta.zero();
		}
		// 원작 붙은 상자는 바닥을 따르지 않고 수평 이동량만 받는다 (0x2119A8, 영웅이 32 넘게 내려가면 놓임 — grabTooFar) [확실]. 엔진은 바닥에 놓인 상자가
		// 비탈·낮은 턱 (±16) 은 따라가게 둔다 (ATHN05B 발리스타 복도 내리막 480 → 444, 43 §22) [근사]. 그보다 깊은 단 (ATHN06B 석상 단 789 → 계단 765) 이나
		// 낭떠러지 위로는 떠서 가고, 놓이면 떨어진다 (boxFall). 예전 엔진은 발밑 ±16 에 바닥이 없으면 막아 석상을 계단으로 끌어내릴 수 없었다
		if (f != null && f0 != null && Math.abs(it.pos.y - rest - f0.y()) < 1f) {
			delta.y = f.y() + rest - it.pos.y;
		}
		it.pos.add(delta);
		listener.pushMoved(it, delta);
		return delta;
	}

	/**
	 * 상자 위치 (관절 0) 높이 − 놓인 바닥 높이: 처음 물을 때 지금 자리 (놓여 있음) 의 발밑 ±16 바닥으로 잰다 — 상자 위치 = 루트 관절이라 발리스타는
	 * 바닥 480 아래 474.5 (−5.5). f0 = 이미 구한 발밑 바닥 (없으면 여기서). 관절 0 ±16 에 바닥이 없으면 GO 원점 (상자 밑면) 아래로 다시 잰다 —
	 * Creator 밀기 상자 DEST02 PushPullBlock 은 관절 0 이 GO 원점 위 15.9 (몸 가운데) 라 바닥 위 17.2 였고, 예전엔 0 으로 잡혀 놓으면 몸이 바닥 아래로
	 * 17 가라앉았다 (43 §32).
	 */
	private float restDy(Interactables.Item it, NavWorld.Floor f0) {
		if (Float.isNaN(it.restDy)) {
			NavWorld.Floor f = f0 != null ? f0 : world.floor(it.pos.x, it.pos.y + 16, it.pos.z, 32, world.family(it.owner));
			if (f == null && it.goMatrix != null) {
				f = world.floor(it.goMatrix[12], it.goMatrix[13] + 16, it.goMatrix[14], 32, world.family(it.owner));
			}
			it.restDy = f != null ? it.pos.y - f.y() : 0f;
		}
		return it.restDy;
	}

	/** 상자 최대 낙하 속도 (0x211B70: 0xC4480000 = −800) [확실]. */
	static final float BOX_FALL_MAX = 800f;

	/**
	 * 붙잡히지 않은 상자의 중력 (0x211A10: 공중이면 v.y −= GlobGame Gravity 50·16·dt, 최저 −800 → 0x1E5F90 이 바닥에 내려놓음) [확실: 값 / 근사: 바닥 = 발밑 첫 면].
	 * @return 아직 공중인지
	 */
	private boolean boxFall(Interactables.Item it, float dt) {
		java.util.Set<String> self = world.family(it.owner);
		float rest = restDy(it, null);
		float bottom = it.pos.y - rest;
		NavWorld.Floor f = world.floor(it.pos.x, bottom + 1, it.pos.z, 4000, self);
		if (f != null && bottom - f.y() < 0.05f) {
			it.fallVy = 0;
			return false;
		}
		if (DEBUG_PUSH && it.fallVy == 0) {
			System.out.printf("[상자 낙하] %s %s 바닥 %s%n", it.owner, it.pos, f == null ? "없음" : String.valueOf(f.y()));
		}
		it.fallVy = Math.max(it.fallVy - GRAVITY * dt, -BOX_FALL_MAX);
		float dy = it.fallVy * dt;
		if (f != null && bottom + dy <= f.y()) {
			dy = f.y() - bottom;
			it.fallVy = 0;
		}
		Vector3f d = new Vector3f(0, dy, 0);
		it.pos.add(d);
		listener.pushMoved(it, d);
		return it.fallVy != 0;
	}

	/** 놓였거나 차인 상자: 마찰로 멈추고 바닥에 닿을 때까지 (원작 상자 갱신 0x212398 은 붙잡기와 무관하게 매 프레임). */
	private void slideBoxes(float dt) {
		for (java.util.Iterator<Interactables.Item> i = sliding.iterator(); i.hasNext(); ) {
			Interactables.Item it = i.next();
			if (it == item) {
				i.remove();
				continue;
			}
			boxFriction(it, dt);
			moveBox(it, dt);
			boolean air = boxFall(it, dt);
			if (it.vel.lengthSquared() == 0 && !air) {
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
		airJump = !"ANIFall".equals(anim);
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
