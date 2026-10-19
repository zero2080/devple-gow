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
 *   공중 + 정면 벽 Climbable      → WALL_CLIMB        지상 + 벽 쪽 스틱 + ×  → WALL_CLIMB
 *   공중 + 정면 벽 Ladder / 지상 + 벽 쪽 스틱 → LADDER
 *   공중(하강) + 정면 벽 위 턱 (손 높이, NoWallHang 아님) → LEDGE_HANG
 *   가슴 가까이 줄 (공중, 또는 지상 + ×)  → ROPE / ROPE_TRAVERSE / ZIPLINE (줄 종류 = SCR_RopeSkinned)
 *   발밑 바닥 Water → SWIM,  Narrow → 균형 걷기 (지상 유지, 애니·속도만),  Death → 사망 통지
 *   지상 + R1 + 가까운 오브젝트 → PUSH (PushPull) / INTERACT (CSM·상자·레버·크랭크·TAndF — 붙음 유지 규칙은 {@link #interact})
 * </pre>
 * 속도·가속·줄 손 높이·잡기 반경·점프·중력은 원작 Sold_000 / GlobGame 값 ({@link NavTuning}, 38 문서 §2.2). 벽·턱 판정 기하만 [근사].
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

		/** CSM/상자/레버 완료. */
		default void interactDone(Interactables.Item it) {
		}

		/** Death 면을 밟음. */
		default void deathSurface() {
		}
	}

	/** 원작 수치 (크라토스 TWK_Sold_000, {@link NavTuning} 표). 아래 상수는 전부 이 값 × 16 (1 m = 16 유닛). */
	public static final NavTuning K = NavTuning.KRATOS;
	/** 몸 반지름 (밀기 상자 간격·수영 구 충돌) [근사: 원작 몸 상자 반폭은 Sold+0x234 0.6 m = 9.6]. */
	public static final float BODY = 14f;
	/** 벽과 몸 중심 거리 [근사: 원작 벽 탐색은 정면 0.75 × 상자 깊이 19.2 = 14.4 (0x1E48E4) 의 선분 질의, 엔진은 정면 캐스트]. */
	public static final float WALL_OFF = 10f;
	/** 벽 판정 높이 (가슴) [근사]. */
	public static final float CHEST = 20f;
	/** 매달렸을 때 발 → 손 높이 (wallHangI 손끝 35.9) [근사: 원작 턱 판정 0x1E47E0 기하 미해석]. */
	public static final float HANG = 34f;
	/** 키 = 줄 잡기 점·줄 손 높이: Sold+0x238 2.2 m = 35.2 (0x1C5C5C 잡기 점 = 발 + 위축 × +0x120.y, 0x1C66E4 줄 위 몸 = 줄 점 − 같은 값) [확실]. */
	public static final float ROPE_HAND = K.units(NavTuning.BODY_HEIGHT);
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
	/** 밀기 속도 [근사: 원작은 상자 물리 속도를 영웅이 따라감 (0x1C4878, 최대 Sold+0xEC → 120)]. */
	public static final float PUSH_SPEED = 26f;
	/** 좁은 길 지상 속도 배율 Sold+0x230 = 1 (0x1EAFB0) [확실: 값 / 추정: +0x2D8 비트 4 = 좁은 길]. */
	public static final float BALANCE_SPEED = K.get(NavTuning.NARROW_SCALE);
	/** 짚라인: 목표 Sold+0x160 20 → 320 으로 가속 +0x164 10 → 160 (0x1C6134, 기울기 무관), 끝 0.25 m 앞에서 속도 0 으로 놓음 (0x1C6B1C) [확실: 속도 / 추정: 끝 처리]. */
	public static final float ZIP_SPEED = K.units(NavTuning.ZIP_SPEED), ZIP_ACCEL = K.units(NavTuning.ZIP_ACCEL);
	/** 중력 /GlobGame/ "Gravity" 50 → 800 (0x1EBC0C), 최대 낙하 Sold+0xDC 50 → 800 (0x1EBC4C) [확실]. */
	public static final float GRAVITY = NavTuning.GLOBAL_GRAVITY * NavTuning.UNITS_PER_METER, FALL_MAX = K.units(NavTuning.FALL_MAX);
	/** 줄 흔들기 진자 중력 = 줄 TWK "Gravity" 9.82 [근사: 원작은 질점-스프링 줄]. */
	public static final float ROPE_GRAVITY = 9.82f * NavTuning.UNITS_PER_METER;
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
	private float ropeT, zipSpeed, zipDir, swing, swingVel;
	private final Vector3f swingAxis = new Vector3f(1, 0, 0);
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
		state = body.grounded() ? NavState.GROUND : NavState.AIR;
		rope = null;
		item = null;
		cooldown = 0.3f;
	}

	// ---- 매 틱 ----

	/** @return true = 이번 틱 이동을 처리함 (기본 이동·중력 생략) */
	public boolean update(Input in, float camYaw, float dt) {
		stateTime += dt;
		cooldown -= dt;
		rewindReleased(dt);
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
		Vector3f chest = new Vector3f(p).add(0, CHEST, 0);
		NavRope r = world.nearestRope(new Vector3f(p).add(0, ROPE_HAND, 0), GRAB);
		if (r != null && (!body.grounded() || in.pressed(PadButton.CROSS))) {
			grabRope(r, new Vector3f(p).add(0, ROPE_HAND, 0));
			return true;
		}
		// 벽: 정면 (공중) 또는 스틱 방향 (지상)
		Vector3f dir = body.grounded() && mag > 0.5f ? stick : fwd;
		LevelCollision.Hit hit = world.wallcast(chest, dir, BODY + 10);
		if (hit != null) {
			Vector3f n = horizontalNormal(hit, p);
			boolean into = n.dot(dir) < -0.6f;
			if (into && hit.tri().has(SurfaceAttr.LADDER) && (!body.grounded() || mag > 0.5f)) {
				enterClimb(hit, n, true);
				return true;
			}
			if (into && hit.tri().has(SurfaceAttr.CLIMBABLE) && (!body.grounded() || in.pressed(PadButton.CROSS))) {
				enterClimb(hit, n, false);
				return true;
			}
			if (!body.grounded() && body.vy() < 40 && into && !hit.tri().has(SurfaceAttr.NO_WALL_HANG) && tryLedge(hit, n)) {
				return true;
			}
		}
		return false;
	}

	// ---- 벽 오르기 / 사다리 ----

	private void enterClimb(LevelCollision.Hit hit, Vector3f n, boolean ladder) {
		state = ladder ? NavState.LADDER : NavState.WALL_CLIMB;
		stateTime = 0;
		wallN.set(n);
		wallFlags(hit);
		ladderVel = 0;
		ladderSlide = false;
		Vector3f p = body.position();
		p.x = hit.point().x + n.x * WALL_OFF;
		p.z = hit.point().z + n.z * WALL_OFF;
		faceWall();
		body.setVy(0);
		body.setGrounded(false);
		body.launch().zero();
		body.play(ladder ? anims.get("ANILadder") : anims.get("ANIClimbIdle"), true);
	}

	private void wallFlags(LevelCollision.Hit h) {
		noBackJumps = h.tri().has(SurfaceAttr.NO_BACK_JUMPS);
		noLadderSliding = h.tri().has(SurfaceAttr.NO_LADDER_SLIDING);
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
		LevelCollision.Hit h = world.wallcast(new Vector3f(cand).add(0, CHEST, 0).fma(8, wallN), new Vector3f(wallN).negate(), WALL_OFF + 24);
		boolean ok = h != null && (h.tri().has(SurfaceAttr.CLIMBABLE) || h.tri().has(SurfaceAttr.LADDER));
		if (ok) {
			Vector3f n = horizontalNormal(h, cand);
			wallN.set(n);
			wallFlags(h);
			p.set(h.point().x + n.x * WALL_OFF, cand.y, h.point().z + n.z * WALL_OFF);
			faceWall();
		} else if (vy > 0) {
			// 꼭대기: 벽 너머 위에 바닥이 있으면 올라선다 (ANIWallPullup / 사다리 ANILDis1)
			Vector3f over = new Vector3f(p).fma(-(WALL_OFF + 16), wallN);
			NavWorld.Floor top = world.floor(over.x, p.y + CHEST + HANG + 16, over.z, HANG + 40);
			if (top != null && top.y() > p.y - 4) {
				p.set(over.x, top.y(), over.z);
				land(ladder ? "ANILDis1" : "ANIWallPullup");
				return;
			}
			// 벽 위에 걸친 발판 (돛대 꼭대기처럼 몸 바로 위 바닥): 그 위로 올라선다
			NavWorld.Floor above = world.floor(p.x, p.y + CHEST + HANG + 16, p.z, CHEST + HANG + 8);
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

	private boolean tryLedge(LevelCollision.Hit hit, Vector3f n) {
		Vector3f p = body.position();
		Vector3f over = new Vector3f(hit.point()).fma(-10, n);
		NavWorld.Floor top = world.floor(over.x, p.y + HANG + 14, over.z, 40);
		if (top == null || top.y() < p.y + HANG - 16 || top.y() > p.y + HANG + 14) {
			return false;
		}
		// 턱 위에 설 자리 (머리 위 벽 없음)
		if (world.wallcast(new Vector3f(p.x, top.y() + 12, p.z), new Vector3f(n).negate(), WALL_OFF + 24) != null) {
			return false;
		}
		state = NavState.LEDGE_HANG;
		stateTime = 0;
		wallN.set(n);
		ledgeY = top.y();
		p.set(hit.point().x + n.x * WALL_OFF, ledgeY - HANG, hit.point().z + n.z * WALL_OFF);
		faceWall();
		body.setVy(0);
		body.setGrounded(false);
		body.launch().zero();
		body.play(anims.get("ANIWallHangCatch"), true);
		return true;
	}

	private void hang(Input in, float dt) {
		Vector3f p = body.position();
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
			Vector3f right = new Vector3f(wallN).negate().cross(0, 1, 0).normalize();
			Vector3f cand = new Vector3f(p).fma(Math.signum(sx) * SHIMMY_SPEED * dt, right);
			LevelCollision.Hit h = world.wallcast(new Vector3f(cand).add(0, HANG - 8, 0).fma(8, wallN), new Vector3f(wallN).negate(), WALL_OFF + 24);
			if (h != null) {
				Vector3f over = new Vector3f(h.point()).fma(-10, horizontalNormal(h, cand));
				NavWorld.Floor top = world.floor(over.x, ledgeY + 8, over.z, 16);
				if (top != null) {
					Vector3f n = horizontalNormal(h, cand);
					wallN.set(n);
					ledgeY = top.y();
					p.set(h.point().x + n.x * WALL_OFF, ledgeY - HANG, h.point().z + n.z * WALL_OFF);
					faceWall();
					a = anims.get(sx > 0 ? "ANIWallHangR" : "ANIWallHangL");
				}
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
				swing = 0;
				swingVel = 0;
				swingAxis.set(forward(body.yaw()));
				body.play(anims.get("ANIRope"), true);
			}
		}
		placeOnRope();
	}

	/** 줄 위 손 위치 → 몸 위치. 매단 줄은 흔들림 각도만큼 매단 점 기준으로 돌린다. */
	private void placeOnRope() {
		Vector3f hand = rope.point(ropeT, new Vector3f());
		if (state == NavState.ROPE && Math.abs(swing) > 1e-4f) {
			float l = hand.distance(rope.top());
			hand.set(rope.top()).add(swingAxis.x * (float) Math.sin(swing) * l, -(float) Math.cos(swing) * l, swingAxis.z * (float) Math.sin(swing) * l);
		}
		body.position().set(hand.x, hand.y - ROPE_HAND, hand.z);
	}

	private void hangRope(Input in, float camYaw, float dt) {
		float l = Math.max(16, rope.length() * ropeT);
		if (in.pressed(PadButton.CROSS)) {
			// 접선 속도로 뛰어내림 (점프 초속을 더함) [근사: 원작 줄 이탈 0x1C5848 미해석]
			float v = swingVel * l;
			Vector3f tan = new Vector3f(swingAxis).mul((float) Math.cos(swing) * v);
			leave(tan.add(new Vector3f(forward(body.yaw())).mul(40)), JUMP + (float) Math.sin(swing) * v, "ANIFall");
			return;
		}
		float sy = in.leftY(), sx = in.leftX();
		String a = anims.get("ANIRope");
		if (Math.abs(swing) < 0.15f) {
			ropeVel = ropeVelocity(sy, dt);
			if (ropeVel != 0) {
				float len = Math.max(1, rope.length());
				float t = ropeT + ropeVel * dt / len;
				ropeT = Math.max(Math.min(0.05f, ROPE_TOP_GAP / len), Math.min(1, t));
				a = ropeSlide ? anims.get("ANIRSlide") : anims.get(ropeVel < 0 ? "ANIRopeU" : "ANIRopeD");
				// 아래 끝에서 바닥이 있으면 내림
				if (ropeVel > 0 && ropeT >= 1) {
					Vector3f p = body.position();
					NavWorld.Floor f = world.floor(p.x, p.y + 4, p.z, 24);
					if (f != null) {
						p.y = f.y();
						land(null);
						return;
					}
				}
			}
		}
		// 흔들기: 좌우 스틱 (카메라 기준) 으로 진자 펌프 [근사: 원작 입력 미확인]
		if (Math.abs(sx) > 0.3f) {
			Vector3f d = stickDir(in, camYaw);
			if (Math.abs(swing) < 0.05f) {
				swingAxis.set(d);
				body.setYaw(yawOf(d));
			}
			swingVel += Math.signum(new Vector3f(d).dot(swingAxis)) * 2.2f * dt * (swingVel == 0 ? 1 : Math.signum(swingVel) == Math.signum(d.dot(swingAxis)) ? 1 : 0.5f);
			a = anims.get("ANIRopeIdle");
		}
		float g = ROPE_GRAVITY / l;
		swingVel += (float) (-g * Math.sin(swing)) * dt;
		swingVel *= (1 - 0.3f * dt);
		swing += swingVel * dt;
		float max = (float) Math.toRadians(Math.max(10, rope.maxSwingDeg()));
		if (Math.abs(swing) > max) {
			swing = Math.signum(swing) * max;
			swingVel = 0;
		}
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

	private void push(Input in, float camYaw, float dt) {
		Interactables.Item it = item;
		// 원작 밀기 (모드 1, 0x1C4258): 사용 버튼을 누르고 있는 동안만 붙어 있음, 떼면 놓음 [확실]. × = 상자 차기 (ANIStartKickBox, 미구현 → 놓음)
		if (!in.held(PadButton.USE) || in.pressed(PadButton.CROSS)) {
			listener.pushDetach(it);
			end();
			return;
		}
		float along = stickMag(in) > 0.3f ? stickDir(in, camYaw).dot(pushAxis) : 0;
		String a = anims.get("ANIPushI");
		if (Math.abs(along) > 0.4f) {
			float s = Math.signum(along);
			Vector3f delta = new Vector3f(pushAxis).mul(s * PUSH_SPEED * dt);
			Vector3f cand = new Vector3f(it.pos).add(delta);
			NavWorld.Floor f = world.floor(cand.x, it.pos.y + 16, cand.z, 32);
			Vector3f c = new Vector3f(cand).add(0, it.radius * 0.5f, 0);
			// 앞쪽 벽: 상자 반지름 구가 밀리면 막힘
			Vector3f probe = new Vector3f(c);
			boolean blocked = world.collideSphere(probe, it.radius * 0.8f) && probe.distance(c) > 0.5f;
			if (f != null && !blocked) {
				delta.y = f.y() - it.pos.y;
				it.pos.add(delta);
				body.position().add(delta.x, 0, delta.z);
				listener.pushMoved(it, delta);
				a = anims.get(s > 0 ? "ANIPushF" : "ANIPushB");
			}
		}
		body.play(a, false);
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
		state = NavState.AIR;
		rope = null;
		body.launch().set(launch.x, 0, launch.z);
		body.setVy(vy);
		body.setGrounded(false);
		cooldown = 0.4f;
		body.play(anims.get(anim), true);
	}

	private void land(String anim) {
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

	/** 면 법선의 수평 성분 (영웅 쪽을 향하게). */
	private static Vector3f horizontalNormal(LevelCollision.Hit h, Vector3f from) {
		Vector3f n = new Vector3f(h.tri().n().x, 0, h.tri().n().z);
		if (n.lengthSquared() < 1e-6f) {
			n.set(from.x - h.point().x, 0, from.z - h.point().z);
		}
		n.normalize();
		if (n.dot(from.x - h.point().x, 0, from.z - h.point().z) < 0) {
			n.negate();
		}
		return n;
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
