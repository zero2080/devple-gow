package devple.gow.engine.anim;

import devple.gow.engine.combat.Combatant;
import devple.gow.engine.input.Input;
import devple.gow.engine.nav.HeroNavigator;
import devple.gow.engine.nav.NavBody;
import devple.gow.tools.anim.Animation;
import devple.gow.tools.anim.Skeleton;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 크레토스 이동 (대기 / 걷기 / 달리기). 원작 데이터 사용:
 * <ul>
 *   <li>클립: standIdle, walkBlend1(걷기, blendParam 3.5), walkBlend2(달리기, 5.0) — 그룹 bwalk</li>
 *   <li>속도: 루프 이동 클립의 zeroJoint Z 가 일정값(걷기 -3.2, 달리기 -8.0) → 15fps 기준 프레임당 이동량으로 해석 [추정]</li>
 *   <li>메시의 시각적 정면 = +Z (엔진 스크린샷으로 확인). 루트 모션 값은 -Z 방향이므로 렌더 시 180° 돌려 이동 방향과 맞춘다</li>
 * </ul>
 * 원작은 카메라 기준 스틱 방향으로 즉시 회전한다 (회전 보간은 짧게).
 */
public final class HeroController implements NavBody {

	public static final float ROOT_FPS = 15f;
	/** 자동 조준 거리 (TWK /Player/Targeting Range = 10 은 1/16 단위로 추정 → 160 유닛) [추정] */
	public static final float AUTO_AIM_RANGE = 160f;
	/** 캐릭터 몸통 반지름 (겹침 방지) */
	public static final float BODY_RADIUS = 14f;
	private static final float RUN_THRESHOLD = 0.6f;
	private static final float TURN_RATE = 14f;

	private final Animator anim;
	private final int zeroJoint;
	public final Vector3f position = new Vector3f();
	public final Vector3f prevPosition = new Vector3f();
	public float yaw;
	private float prevYaw;

	/**
	 * 점프 초속 (유닛/s) = 크라토스 TWK_Sold_000 +0xC4 15.25 m/s × 16 = 244 (0x1E9E88, 내비게이션 공용 점프 0x1E9B68) [확실].
	 * 중력 /GlobGame/ Gravity 50 m/s² (800) 와 함께 정점 ≈ 37 유닛. 점프는 전투 기술이 아니라 내비게이션 코드다 (×  = +0x310 비트 1).
	 */
	public static final float JUMP_SPEED = HeroNavigator.JUMP;

	/** 수직 속도 / 지면 여부 / 사용한 점프 수 (물리는 게임 씬이 적분). */
	public float vy;
	public boolean grounded = true;
	private int jumps;
	private boolean wasGrounded = true;

	private Animation rootClip;
	/** 루트 모션 (원작 0x1F7110, 기술 중) — 이동·회전. */
	private final RootMotion root = new RootMotion();
	private float rootDy;
	/** 동기 상대가 끄는 중 (0x204BF0 의 끌리는 쪽: 루트 모션 D = 단위, 0x1EC058). 동기 코드가 매 틱 정한다. */
	public boolean rootHold;
	/** 공중 수평 발사 속도 (줄·벽에서 뛰어내림, 내비게이터가 정함). 착지하면 0. */
	public final Vector3f launch = new Vector3f();
	/** 지상 이동 모양 (균형 걷기 등, null = 기본) / 속도 배율. */
	private String walkOverride, idleOverride;
	private float speedScale = 1f;
	/** 마지막 지상 이동 속도 / 공중 정면 수평 속도 (원작 +0x260 z, 0x1EB650). */
	private float groundSpeed, airSpeed;

	public HeroController(Animator anim) {
		this.anim = anim;
		this.zeroJoint = anim.skeleton().indexOf("zeroJoint");
		anim.play("standIdle", true);
	}

	/**
	 * 전투 기술 중: 이동 입력 대신 루트 모션 적용. 원작 데이터에서 몸통(pelvis)은 제자리이고
	 * zeroJoint 만 이동량을 가진다 (dash: 1초 동안 Z -108) → zeroJoint 변화량을 월드 이동으로 쓴다.
	 */
	public void updateCombat(Combatant c, Input in, float camYaw, float dt) {
		updateCombat(c, in, camYaw, dt, null);
	}

	/** @param target 자동 조준 대상 위치 (스틱 중립일 때 기술 시작 시 그쪽으로 회전), null 이면 없음 */
	public void updateCombat(Combatant c, Input in, float camYaw, float dt, Vector3f target) {
		prevPosition.set(position);
		prevYaw = yaw;
		if (c.hitPause() > 0) {
			return;
		}
		Animation cur = anim.current();
		if (cur != rootClip) {
			rootClip = cur;
			// 기술 시작 시 스틱 방향으로 즉시 회전 (원작 조작감)
			float sx = in.leftX(), sy = in.leftY();
			if (Math.hypot(sx, sy) > 0.3) {
				yaw = camYaw + (float) Math.atan2(-sx, sy);
			} else if (target != null && target.distance(position) < AUTO_AIM_RANGE) {
				// 정면 -Z 기준: 방향 (dx, dz) → yaw = atan2(-dx, -dz)
				yaw = (float) Math.atan2(-(target.x - position.x), -(target.z - position.z));
			}
		}
		anim.update(dt);
		applyRoot(false);
	}

	/**
	 * 루트 모션 한 프레임 ({@link RootMotion}, 원작 0x1F7110 → 0x1EBD78): 수평 변위·요 변화를 적용. 수직 변위는 {@link #rootDy} 에 남기고
	 * vertical 이면 바로 더한다 (동기 중처럼 물리가 꺼진 경우 — 원작은 변위를 내비게이션 이동에 넘겨 지상이면 바닥에 붙는다).
	 * {@link #rootHold} 면 (동기 상대가 끄는 중) 변위 없음.
	 */
	public void applyRoot(boolean vertical) {
		root.step(anim, 1f, yaw, rootHold);
		position.add(root.dx, vertical ? root.dy : 0, root.dz);
		yaw += root.dyaw;
		rootDy = root.dy;
		rootDyApplied = vertical ? root.dy : 0;
	}

	private float rootDyApplied;

	/**
	 * 이번 틱에 적용한 루트 모션을 되돌린다 — 동기 상대가 이번 프레임부터 끄는 중으로 판명됐을 때 (원작 0x1EBF8C 는 상대의 이벤트 창을
	 * 같은 프레임에 0x206030 으로 갱신한 뒤 0x2441E0 을 보므로 끌리는 첫 프레임부터 D = 단위).
	 */
	public void undoRoot() {
		position.sub(root.dx, rootDyApplied, root.dz);
		yaw -= root.dyaw;
		root.dx = root.dy = root.dz = root.dyaw = 0;
		rootDy = rootDyApplied = 0;
	}

	/** 루트 모션 상태 초기화 (전투 기술이 아닌 프레임 — 원작은 기술 중에만 D 를 적용). */
	public void resetRoot() {
		root.reset();
		rootDy = 0;
	}

	/** 마지막 루트 모션의 수직 변위 (애니 y, 영웅 배율 1). */
	public float rootDy() {
		return rootDy;
	}

	/** 다른 몸통과 겹치지 않게 밀어낸다 (원작 캐릭터 간 충돌의 단순화). */
	public void separate(Vector3f other, float otherRadius) {
		float dx = position.x - other.x, dz = position.z - other.z;
		float min = BODY_RADIUS + otherRadius;
		float d = (float) Math.hypot(dx, dz);
		if (d < min) {
			if (d < 1e-3f) {
				dx = (float) Math.sin(yaw);
				dz = (float) Math.cos(yaw);
				d = 1;
			}
			position.x = other.x + dx / d * min;
			position.z = other.z + dz / d * min;
		}
	}

	/**
	 * 점프 (× 버튼). 원작 Navigation 표: 제자리 = ANIVJump "jumpUp", 이동 중 = ANIJump "jumpAir",
	 * 공중 = ANIDblJump "jumpdoubleair". 2단 점프까지.
	 */
	public boolean jump(boolean moving) {
		if (grounded) {
			jumps = 1;
			anim.play(moving ? "jumpAir" : "jumpUp", true);
			airSpeed = moving ? groundSpeed : 0;
			vy = Math.max(vy, 0) + JUMP_SPEED; // 0x1E9E78: 현재 수직 속도에 더함
		} else if (jumps < 2 && vy < HeroNavigator.DOUBLE_MAX_VY && vy > HeroNavigator.DOUBLE_MIN_VY) {
			// 0x1EA130: 수직 속도가 (Sold+0xD0, +0xCC) 안일 때만, 수직 속도 = +0xC8
			jumps = 2;
			anim.play("jumpdoubleair", true);
			vy = HeroNavigator.DOUBLE_JUMP;
		} else {
			return false;
		}
		grounded = false;
		return true;
	}

	/** @param camYaw 카메라 Y 회전 (스틱 입력을 카메라 기준으로 해석) */
	public void update(Input in, float camYaw, float dt) {
		prevPosition.set(position);
		prevYaw = yaw;
		rootClip = null;
		root.reset();
		rootDyApplied = 0;
		if (!grounded) {
			airUpdate(in, camYaw, dt);
			return;
		}
		if (!wasGrounded) {
			wasGrounded = true;
			jumps = 0;
			launch.zero();
			airSpeed = 0;
			anim.play("land", true); // ANILand
		}
		if (anim.current() != null && anim.current().name.equals("land") && !anim.finished()
				&& Math.hypot(in.leftX(), in.leftY()) < 0.15f) {
			anim.update(dt);
			return;
		}
		float sx = in.leftX(), sy = in.leftY();
		float mag = Math.min(1f, (float) Math.hypot(sx, sy));
		if (mag < 0.15f) {
			anim.play(idleOverride != null ? idleOverride : "standIdle", false);
		} else {
			// 스틱 → 월드 방향 (카메라 정면 = 스틱 위)
			float dirYaw = camYaw + (float) Math.atan2(-sx, sy);
			float d = wrap(dirYaw - yaw);
			yaw += d * Math.min(1f, TURN_RATE * dt);
			anim.play(walkOverride != null ? walkOverride : mag > RUN_THRESHOLD ? "walkBlend2" : "walkBlend1", false);
			float speed = walkOverride != null ? clipSpeed(anim.find("walkBlend1")) * speedScale : clipSpeed(anim.current());
			groundSpeed = speed;
			// 정면 -Z 를 yaw 만큼 회전
			position.add((float) -Math.sin(yaw) * speed * dt, 0, (float) -Math.cos(yaw) * speed * dt);
		}
		anim.update(dt);
	}

	/**
	 * 공중 (원작 0x1EB650): 정면을 스틱 쪽으로 프레임당 Sold+0xE4 비율로 돌리고, 정면 수평 속도를 목표
	 * Sold+0xEC × (스틱 · 정면) 쪽으로 Sold+0xD8 가속 (둘 다 7.5 m/s → 120) [확실: 값 / 추정: 회전 보간]. 하강 시 ANIFall "fallv".
	 */
	private void airUpdate(Input in, float camYaw, float dt) {
		wasGrounded = false;
		float sx = in.leftX(), sy = in.leftY();
		float mag = Math.min(1f, (float) Math.hypot(sx, sy));
		float target = 0;
		if (mag > 0.15f) {
			float dirYaw = camYaw + (float) Math.atan2(-sx, sy);
			yaw += wrap(dirYaw - yaw) * Math.min(1f, HeroNavigator.AIR_TURN * 60f * dt);
			target = HeroNavigator.AIR_SPEED * mag * (float) Math.max(0, Math.cos(wrap(dirYaw - yaw)));
		}
		float acc = HeroNavigator.AIR_ACCEL * dt;
		airSpeed = target > airSpeed ? Math.min(target, airSpeed + acc) : Math.max(target, airSpeed - acc);
		if (airSpeed != 0) {
			position.add((float) -Math.sin(yaw) * airSpeed * dt, 0, (float) -Math.cos(yaw) * airSpeed * dt);
		}
		position.fma(dt, launch);
		if (vy < 0 && anim.finished()) {
			anim.play("fallv", false);
		}
		anim.update(dt);
	}

	/** 루프 이동 클립의 초당 이동량. */
	private float clipSpeed(Animation a) {
		if (a == null || zeroJoint < 0) {
			return 0;
		}
		float z = a.sample(anim.skeleton(), 0, true).pos[zeroJoint][2];
		return Math.abs(z) * ROOT_FPS * anim.speedOf(a);
	}

	private static float wrap(float a) {
		while (a > Math.PI) {
			a -= (float) (2 * Math.PI);
		}
		while (a < -Math.PI) {
			a += (float) (2 * Math.PI);
		}
		return a;
	}

	public Matrix4f world(Matrix4f out, float alpha) {
		Vector3f p = new Vector3f(prevPosition).lerp(position, alpha);
		float y = prevYaw + wrap(yaw - prevYaw) * alpha;
		return out.translation(p).rotateY(y); // 메시 정면 = 모델 -Z (34 문서 §4: 이전 +π 는 등을 보이게 했다)
	}

	public Animator animator() {
		return anim;
	}

	// ---- NavBody (38 문서: 내비게이터가 위치·애니를 직접 정하는 상태) ----

	@Override
	public Vector3f position() {
		return position;
	}

	@Override
	public float yaw() {
		return yaw;
	}

	@Override
	public void setYaw(float y) {
		yaw = y;
	}

	@Override
	public float vy() {
		return vy;
	}

	@Override
	public void setVy(float v) {
		vy = v;
	}

	/** 점프로 뜬 공중인지 (원작 +0x174 0x4, 점프 0x1E9950/0x1E9A38). 아니면 걸어 떨어짐·놓음 0x8 (0x1EA3B0, 0x1E9168). */
	public boolean jumped() {
		return !grounded && jumps > 0;
	}

	@Override
	public boolean grounded() {
		return grounded;
	}

	@Override
	public void setGrounded(boolean g) {
		grounded = g;
		airSpeed = 0; // 내비게이터가 붙이거나 놓을 때 (놓는 속도는 launch)
		if (g) {
			wasGrounded = true;
			jumps = 0;
		}
	}

	@Override
	public Vector3f launch() {
		return launch;
	}

	@Override
	public void play(String name, boolean restart) {
		anim.play(name, restart);
	}

	@Override
	public boolean animFinished() {
		return anim.finished();
	}

	@Override
	public float clipLength(String name) {
		Animation a = name != null ? anim.find(name) : null;
		return a != null ? a.duration : -1f;
	}

	@Override
	public void locomotion(String walk, String idle, float scale) {
		walkOverride = walk;
		idleOverride = idle;
		speedScale = scale;
	}

	/** 내비게이터가 움직인 틱: 보간용 이전 위치 갱신 + 애니 진행. */
	public void navTick(float dt) {
		root.reset();
		anim.update(dt);
	}

	/** 틱 시작 (보간 이전 값 저장). */
	public void beginTick() {
		prevPosition.set(position);
		prevYaw = yaw;
	}

	public Skeleton skeleton() {
		return anim.skeleton();
	}
}
