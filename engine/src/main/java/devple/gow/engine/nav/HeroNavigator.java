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
 *   지상 + R1 + 가까운 오브젝트 → PUSH (PushPull) / INTERACT (CSM·상자·레버)
 * </pre>
 * 수치 (속도·손 높이) 는 원작 상수 미복원 → 영웅 애니 크기(서 있는 키 32.6, 매달린 손 35.9 유닛)에 맞춘 값 [근사].
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

	public static final float BODY = 14f;
	/** 벽과 몸 중심 거리. */
	public static final float WALL_OFF = 10f;
	/** 벽 판정 높이 (가슴). */
	public static final float CHEST = 20f;
	/** 매달렸을 때 발 → 손 높이 (wallHangI 손끝 35.9). */
	public static final float HANG = 34f;
	/** 줄을 잡은 손 높이 (ropeIdle 손 32.7). */
	public static final float ROPE_HAND = 30f;
	public static final float CLIMB_SPEED = 28f, LADDER_SPEED = 32f, ROPE_SPEED = 30f, SHIMMY_SPEED = 24f, TRAVERSE_SPEED = 30f;
	public static final float SWIM_SPEED = 40f, SWIM_DEPTH = 16f, PUSH_SPEED = 26f, BALANCE_SPEED = 0.55f;
	public static final float ZIP_MIN = 60f, ZIP_MAX = 420f, GRAB = 22f;
	public static final float GRAVITY = 9.82f * 16f;
	public static final float JUMP = (float) Math.sqrt(2 * 9.82 * 16 * 45);

	private final NavBody body;
	private final NavWorld world;
	private final NavAnims anims;
	private Interactables items = new Interactables();
	private Listener listener = new Listener() {
	};
	private NavState state = NavState.GROUND;
	private boolean balancing;
	private float stateTime, cooldown, idleTime;
	private final Vector3f wallN = new Vector3f();
	private float ledgeY;
	private NavRope rope;
	private float ropeT, zipSpeed, zipDir, swing, swingVel;
	private final Vector3f swingAxis = new Vector3f(1, 0, 0);
	private float waterY;
	private Interactables.Item item;
	/** 영웅이 놓은 뒤 되감기는 중인 CSM/레버 (원작: 오브젝트가 스스로 −4 로 되감음). */
	private final java.util.List<Interactables.Item> released = new java.util.ArrayList<>();
	private final Vector3f pushAxis = new Vector3f();
	private boolean finishing;

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
		if (item != null && state == NavState.PUSH) {
			listener.pushDetach(item);
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
				interact(in, dt);
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
			if (in.pressed(PadButton.R1)) {
				Interactables.Item it = items.nearest(p, BODY + 14);
				if (it != null) {
					startItem(it);
					return true;
				}
			}
		}
		if (cooldown > 0) {
			return false;
		}
		// 줄 잡기
		Vector3f chest = new Vector3f(p).add(0, CHEST, 0);
		NavRope r = world.nearestRope(new Vector3f(p).add(0, ROPE_HAND, 0), GRAB);
		if (r == null) {
			r = world.nearestRope(chest, GRAB);
		}
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
		Vector3f p = body.position();
		p.x = hit.point().x + n.x * WALL_OFF;
		p.z = hit.point().z + n.z * WALL_OFF;
		faceWall();
		body.setVy(0);
		body.setGrounded(false);
		body.launch().zero();
		body.play(ladder ? anims.get("ANILadder") : anims.get("ANIClimbIdle"), true);
	}

	private void climb(Input in, float dt) {
		boolean ladder = state == NavState.LADDER;
		Vector3f p = body.position();
		if (in.pressed(PadButton.CROSS)) {
			leave(new Vector3f(wallN).mul(70), JUMP * 0.75f, "ANIWJump");
			return;
		}
		if (in.pressed(PadButton.CIRCLE)) {
			leave(new Vector3f(), 0, "ANIFall");
			return;
		}
		float sx = ladder ? 0 : in.leftX(), sy = in.leftY();
		if (Math.abs(sx) < 0.2f) {
			sx = 0;
		}
		if (Math.abs(sy) < 0.2f) {
			sy = 0;
		}
		float speed = ladder ? LADDER_SPEED : CLIMB_SPEED;
		Vector3f right = new Vector3f(wallN).negate().cross(0, 1, 0).normalize();
		Vector3f cand = new Vector3f(p).fma(sx * speed * dt, right).add(0, sy * speed * dt, 0);
		LevelCollision.Hit h = world.wallcast(new Vector3f(cand).add(0, CHEST, 0).fma(8, wallN), new Vector3f(wallN).negate(), WALL_OFF + 24);
		boolean ok = h != null && (h.tri().has(SurfaceAttr.CLIMBABLE) || h.tri().has(SurfaceAttr.LADDER));
		if (ok) {
			Vector3f n = horizontalNormal(h, cand);
			wallN.set(n);
			p.set(h.point().x + n.x * WALL_OFF, cand.y, h.point().z + n.z * WALL_OFF);
			faceWall();
		} else if (sy > 0) {
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
		if (sy < 0) {
			NavWorld.Floor f = world.floor(p.x, p.y + 2, p.z, 6);
			if (f != null) {
				p.y = f.y();
				land(null);
				return;
			}
		}
		String a;
		if (ladder) {
			a = sy > 0 ? anims.get("ANILadderU") : sy < 0 ? anims.get("ANILadderD") : anims.get("ANILadder");
		} else if (sy == 0 && sx == 0) {
			a = anims.get("ANIClimbIdle");
		} else if (Math.abs(sy) >= Math.abs(sx)) {
			a = NavAnims.CLIMB[sy > 0 ? 0 : 2];
		} else {
			a = NavAnims.CLIMB[sx > 0 ? 1 : 3];
		}
		body.play(a, false);
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
		body.setVy(0);
		body.setGrounded(false);
		body.launch().zero();
		Vector3f d = new Vector3f(r.end()).sub(r.top());
		switch (r.type()) {
			case ZIPLINE -> {
				state = NavState.ZIPLINE;
				// 낮은 쪽으로 미끄러진다
				zipDir = r.end().y <= r.top().y ? 1 : -1;
				zipSpeed = ZIP_MIN;
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
			// 접선 속도로 뛰어내림
			float v = swingVel * l;
			Vector3f tan = new Vector3f(swingAxis).mul((float) Math.cos(swing) * v);
			leave(tan.add(new Vector3f(forward(body.yaw())).mul(40)), JUMP * 0.6f + (float) Math.sin(swing) * v, "ANIFall");
			return;
		}
		float sy = in.leftY(), sx = in.leftX();
		String a = anims.get("ANIRope");
		if (Math.abs(sy) > 0.3f && Math.abs(swing) < 0.15f) {
			float t = ropeT - Math.signum(sy) * ROPE_SPEED * dt / Math.max(1, rope.length());
			ropeT = Math.max(Math.min(0.05f, 24 / Math.max(1, rope.length())), Math.min(1, t));
			a = anims.get(sy > 0 ? "ANIRopeU" : "ANIRopeD");
			// 아래 끝에서 바닥이 있으면 내림
			if (sy < 0 && ropeT >= 1) {
				Vector3f p = body.position();
				NavWorld.Floor f = world.floor(p.x, p.y + 4, p.z, 24);
				if (f != null) {
					p.y = f.y();
					land(null);
					return;
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
		float g = GRAVITY / l;
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

	private void traverse(Input in, float camYaw, float dt) {
		if (in.pressed(PadButton.CROSS) || in.pressed(PadButton.CIRCLE)) {
			leave(new Vector3f(), 0, "ANIFall");
			return;
		}
		Vector3f d = new Vector3f(rope.end()).sub(rope.top()).normalize();
		float along = stickMag(in) > 0.3f ? stickDir(in, camYaw).dot(new Vector3f(d.x, 0, d.z).normalize()) : 0;
		String a = anims.get("ANIBRope");
		if (Math.abs(along) > 0.3f) {
			ropeT = Math.max(0, Math.min(1, ropeT + Math.signum(along) * TRAVERSE_SPEED * dt / Math.max(1, rope.length())));
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
			leave(new Vector3f(d.x, 0, d.z).mul(zipSpeed * 0.5f), JUMP * 0.4f, "ANIFall");
			return;
		}
		// 중력의 줄 방향 성분으로 가속 (d.y < 0 = 내리막), 마찰
		zipSpeed += (-d.y * GRAVITY * 2f - 0.15f * zipSpeed) * dt;
		zipSpeed = Math.max(ZIP_MIN, Math.min(ZIP_MAX, zipSpeed));
		ropeT += zipDir * zipSpeed * dt / len;
		if (ropeT >= 1 || ropeT <= 0) {
			ropeT = Math.max(0, Math.min(1, ropeT));
			placeOnRope();
			// 끝: 진행 방향으로 놓아 준다 (원작 ANIRSlide 뒤 착지)
			leave(new Vector3f(d.x, 0, d.z).mul(Math.min(zipSpeed, 160)), d.y * Math.min(zipSpeed, 160), "ANIFall");
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
		body.position().y = surface - SWIM_DEPTH;
		body.setVy(0);
		body.setGrounded(false);
		body.launch().zero();
		body.play(anims.get("ANISwimIdle"), true);
	}

	private void swim(Input in, float camYaw, float dt) {
		Vector3f p = body.position();
		float mag = stickMag(in);
		String a = anims.get("ANISwimIdle");
		if (mag > 0.2f) {
			Vector3f d = stickDir(in, camYaw);
			body.setYaw(yawOf(d));
			Vector3f cand = new Vector3f(p).fma(SWIM_SPEED * mag * dt, d);
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
			a = anims.get("ANISwim");
		}
		body.play(a, false);
	}

	// ---- 밀기 / 상황 동작 ----

	private void startItem(Interactables.Item it) {
		item = it;
		released.remove(it);
		stateTime = 0;
		idleTime = 0;
		finishing = false;
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
				if (it.kind == Interactables.Kind.LEVER) {
					listener.pushAttach(it);
				}
				body.play(it.heroAnim, true);
			}
		}
	}

	/**
	 * CSM 연타 유지 시간: 마지막 R1 뒤 이 시간 안이면 영웅이 붙어 있는 것으로 본다 [근사: 원작은 영웅 CSM 기술이 붙음(+0x190)을 유지,
	 * 기술 쪽 연타 판정 미분석]. 붙어 있는 동안 오브젝트 애니가 속도 1 로, 떨어지면 −4 로 되감긴다 ({@link TaskProgress}, 원작 0x215DF0).
	 */
	static final float MASH_WINDOW = 0.6f;

	private void rewindReleased(float dt) {
		for (java.util.Iterator<Interactables.Item> i = released.iterator(); i.hasNext(); ) {
			Interactables.Item it = i.next();
			if (it == item) {
				i.remove();
				continue;
			}
			progress(it, false, dt);
			if (it.task().time <= 0 || it.task().done) {
				i.remove();
			}
		}
	}

	/** 오브젝트 애니 한 프레임 → 진행률 이벤트 (원작 0x215928). */
	private void progress(Interactables.Item it, boolean engaged, float dt) {
		TaskProgress.Event e = it.task().update(engaged, dt);
		it.progress = it.task().fraction();
		if (e != null) {
			it.lastPercent = e.percent();
			listener.taskProgress(it, e);
		}
	}

	private void interact(Input in, float dt) {
		Interactables.Item it = item;
		if (finishing) {
			if (body.animFinished() || stateTime > 2f) {
				end();
			}
			return;
		}
		if (in.pressed(PadButton.CIRCLE) || stickMag(in) > 0.7f) {
			// 놓음: 오브젝트는 −4 로 되감긴다 (원작 0x2160C0) — 남은 되감기는 {@link #rewindReleased}
			if (it.kind == Interactables.Kind.LEVER) {
				listener.pushDetach(it);
			}
			progress(it, false, dt);
			released.add(it);
			end();
			return;
		}
		boolean engaged;
		if (it.kind == Interactables.Kind.LEVER) {
			engaged = true; // 레버/크랭크: 잡고 있는 동안 (PushPull 0x2127B0 속도 1)
		} else {
			if (in.pressed(PadButton.R1)) {
				idleTime = 0;
				body.play(it.heroAnim, true);
			} else {
				idleTime += dt;
			}
			engaged = idleTime < (it.kind == Interactables.Kind.LIFT ? it.mashWindow : MASH_WINDOW);
		}
		progress(it, engaged, dt);
		if (it.task().done) {
			if (it.kind == Interactables.Kind.LEVER) {
				listener.pushDetach(it);
				listener.interactDone(it);
				it.done = true;
				end();
				return;
			}
			it.done = true;
			listener.interactDone(it);
			if (it.finishAnim != null) {
				body.play(it.finishAnim, true);
			}
			finishing = true;
			stateTime = 0;
		}
	}

	private void push(Input in, float camYaw, float dt) {
		Interactables.Item it = item;
		if (in.pressed(PadButton.R1) || in.pressed(PadButton.CIRCLE) || in.pressed(PadButton.CROSS)) {
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
		finishing = false;
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
