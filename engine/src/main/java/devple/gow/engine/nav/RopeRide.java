package devple.gow.engine.nav;

import org.joml.Vector3f;

/**
 * 매단 줄 위 생물의 공용 줄 코드 (원작 내비게이션 줄 갱신 0x1C6008 — 영웅·적이 같은 함수를 부른다, 38 §18).
 * <ul>
 *   <li>흔들기 힘 (0x1C6BC8 – 0x1C71A8): 잡을 때 0.25 초 창 (0x29DBF4) 동안 잡은 정면 쪽으로 한 번, 그 뒤는 흔들기 버튼 (+0x310 0x10) 을 누르는 동안.
 *       적은 AI 가 흔들기 버튼을 내지 않으므로 창의 한 번만 민다 [확실: 비트 31 검사 없음].</li>
 *   <li>× 뛰어내리기 (0x1C5488): 속도 = 위 × Sold+0x140 + 정면 × Sold+0x144 (생물마다 자기 Sold, owner+0x320 → +8), 반작용 −속도 × 5 × min(1, 거리 m × 0.25).</li>
 * </ul>
 */
public final class RopeRide {

	/** 잡을 때 흔들기 창 0x29DBF4 = 0.25 초 (0x1C5D80) [확실]. */
	public static final float SWING_WINDOW = 0.25f;
	/** × 뛰어내리기 반작용 배율 5 (0x1C57A8 → 0x218470) [확실]. */
	public static final float KICK = 5f;

	private float window, force;
	private final Vector3f grabRight = new Vector3f(), grabBack = new Vector3f();

	/**
	 * 잡을 때 (0x1C5D54): 몸 오른쪽 → 0x303FC0, 뒤 → 0x303FB0, 창 0.25 초. 짧은 사슬 줄 (SCR f3, +0x374 &amp; 4 → 0x10000) 은 흔들기 구간 전체를 건너뛰므로 창도 없다.
	 *
	 * @param face 잡을 때 몸 정면 (수평)
	 */
	public void grab(Vector3f face, boolean shortChain) {
		Vector3f f = new Vector3f(face.x, 0, face.z);
		if (f.lengthSquared() < 1e-8f) {
			f.set(0, 0, -1);
		}
		f.normalize();
		grabRight.set(f).cross(0, 1, 0).normalize();
		grabBack.set(f).negate();
		window = shortChain ? 0 : SWING_WINDOW;
		force = 0;
	}

	/** 남은 흔들기 창 (초). */
	public float window() {
		return window;
	}

	/**
	 * 매단 줄 흔들기 힘 (0x1C6BC8 – 0x1C71A8) [확실: 디스어셈블]. u = 매단 점 − 붙은 점, F = swingK (Sold+0x148) × 붙은 거리 (m).
	 * <pre>
	 * 창 안: T = u × 잡을 때 오른쪽, A = (잡을 때 뒤 × −u) · 잡을 때 오른쪽, A &gt; 0 이면 û·뒤 ≤ 0.1 일 때 +F × 2 × 남은 창, 아니면 û·뒤 ≥ −0.1 일 때 −F × 2 × 남은 창.
	 * 창 뒤: T = u × 지금 오른쪽, 흔들기 버튼이면 (v × u) · 오른쪽 &gt; 0 이면 û·뒤 ≤ 0.1 일 때 +F, 아니면 û·뒤 ≥ −0.1 일 때 −F.
	 * 크기는 프레임당 0.1 저역 통과 (0x1C7170), 힘 = T̂ × 그 값.
	 * </pre>
	 *
	 * @param back   지금 몸 축 뒤 (줄 축 후보, 논리 축)
	 * @param swing  흔들기 버튼 (줄 미끄럼이 아닐 때)
	 * @param swingK Sold+0x148 (흔들기 힘 N/m)
	 */
	public Vector3f force(RopeSim sim, Vector3f back, boolean swing, float swingK, float dt) {
		Vector3f u = sim.pointAt(0, new Vector3f()).sub(sim.pointAt(sim.dist(), new Vector3f()));
		if (u.lengthSquared() < 1e-8f) {
			return new Vector3f();
		}
		Vector3f un = new Vector3f(u).normalize();
		float big = swingK * sim.dist();
		float f = 0;
		Vector3f t;
		if (window > 0) {
			t = new Vector3f(u).cross(grabRight);
			float side = new Vector3f(grabBack).cross(new Vector3f(u).negate()).dot(grabRight);
			float d = un.dot(grabBack);
			f = side > 0 ? (d <= 0.1f ? big * 2 * window : 0) : (d >= -0.1f ? -big * 2 * window : 0);
			window = Math.max(0, window - dt);
		} else {
			Vector3f face = new Vector3f(back).negate();
			Vector3f right = new Vector3f(face.x, 0, face.z).cross(0, 1, 0);
			if (right.lengthSquared() > 1e-8f) {
				right.normalize();
			}
			t = new Vector3f(u).cross(right);
			if (swing) {
				Vector3f v = sim.velocityAt(sim.dist(), new Vector3f());
				float side = new Vector3f(v).cross(u).dot(right);
				float d = un.dot(back);
				f = side > 0 ? (d <= 0.1f ? big : 0) : (d >= -0.1f ? -big : 0);
			}
		}
		force += (f - force) * 0.1f;
		if (t.lengthSquared() < 1e-8f) {
			return new Vector3f();
		}
		return t.normalize().mul(force);
	}

	/**
	 * 매단 줄 × 뛰어내리기 방향 (0x1C5488) [확실]: 뒤 후보 = 줄 축 방향 (템플릿 방향 제한) 중 −ref 와 내적이 가장 큰 것, 정면 = −그 후보.
	 *
	 * @param ref 원작 +0x2E0 — 스틱 월드 방향, 스틱이 없으면 정면 (적은 AI 스틱 0 → 지금 정면)
	 */
	public static Vector3f jumpFace(Vector3f axis, int dirs, Vector3f ref) {
		int i = NavRope.bestDir(axis, dirs, new Vector3f(ref.x, 0, ref.z).negate());
		return new Vector3f(NavRope.backDirs(axis)[i]).negate();
	}

	/** 뛰어내릴 때 월드 속도 = 위 × up + 정면 × out (+0xE0 를 덮어씀 — 줄 점 속도는 더하지 않음). */
	public static Vector3f jumpVelocity(Vector3f face, float up, float out) {
		return new Vector3f(face).mul(out).add(0, up, 0);
	}

	/** 줄에 주는 반작용 (N): −속도 × 5 × min(1, 붙은 거리 m × 0.25) / 16 (속도는 유닛/s). */
	public static Vector3f kick(RopeSim sim, Vector3f velocity) {
		return new Vector3f(velocity).mul(-KICK * Math.min(1, sim.dist() * 0.25f) / NavTuning.UNITS_PER_METER);
	}
}
