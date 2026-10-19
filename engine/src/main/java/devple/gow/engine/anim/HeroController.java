package devple.gow.engine.anim;

import devple.gow.engine.combat.Combatant;
import devple.gow.engine.input.Input;
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
public final class HeroController {

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

	private Animation rootClip;
	private final Vector3f lastZero = new Vector3f();

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
			lastZero.set(zero());
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
		Vector3f z = zero();
		if (!cur.looping()) {
			Vector3f d = new Vector3f(z).sub(lastZero).rotateY(yaw);
			position.add(d.x, 0, d.z);
		}
		lastZero.set(z);
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

	private Vector3f zero() {
		if (zeroJoint < 0 || anim.current() == null) {
			return new Vector3f();
		}
		float[] p = anim.current().sample(anim.skeleton(), anim.time(), anim.current().looping()).pos[zeroJoint];
		return new Vector3f(p[0], p[1], p[2]);
	}

	/** @param camYaw 카메라 Y 회전 (스틱 입력을 카메라 기준으로 해석) */
	public void update(Input in, float camYaw, float dt) {
		prevPosition.set(position);
		prevYaw = yaw;
		rootClip = null;
		float sx = in.leftX(), sy = in.leftY();
		float mag = Math.min(1f, (float) Math.hypot(sx, sy));
		if (mag < 0.15f) {
			anim.play("standIdle", false);
		} else {
			// 스틱 → 월드 방향 (카메라 정면 = 스틱 위)
			float dirYaw = camYaw + (float) Math.atan2(-sx, sy);
			float d = wrap(dirYaw - yaw);
			yaw += d * Math.min(1f, TURN_RATE * dt);
			anim.play(mag > RUN_THRESHOLD ? "walkBlend2" : "walkBlend1", false);
			float speed = clipSpeed(anim.current());
			// 정면 -Z 를 yaw 만큼 회전
			position.add((float) -Math.sin(yaw) * speed * dt, 0, (float) -Math.cos(yaw) * speed * dt);
		}
		anim.update(dt);
	}

	/** 루프 이동 클립의 초당 이동량. */
	private float clipSpeed(Animation a) {
		if (a == null || zeroJoint < 0) {
			return 0;
		}
		float z = a.sample(anim.skeleton(), 0, true).pos[zeroJoint][2];
		return Math.abs(z) * ROOT_FPS;
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
		return out.translation(p).rotateY(y + (float) Math.PI);
	}

	public Animator animator() {
		return anim;
	}

	public Skeleton skeleton() {
		return anim.skeleton();
	}
}
