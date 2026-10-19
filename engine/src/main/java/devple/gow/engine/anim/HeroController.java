package devple.gow.engine.anim;

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
	private static final float RUN_THRESHOLD = 0.6f;
	private static final float TURN_RATE = 14f;

	private final Animator anim;
	private final int zeroJoint;
	public final Vector3f position = new Vector3f();
	public final Vector3f prevPosition = new Vector3f();
	public float yaw;
	private float prevYaw;

	public HeroController(Animator anim) {
		this.anim = anim;
		this.zeroJoint = anim.skeleton().indexOf("zeroJoint");
		anim.play("standIdle", true);
	}

	/** @param camYaw 카메라 Y 회전 (스틱 입력을 카메라 기준으로 해석) */
	public void update(Input in, float camYaw, float dt) {
		prevPosition.set(position);
		prevYaw = yaw;
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
