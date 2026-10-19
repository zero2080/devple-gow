package devple.gow.engine.fx;

import devple.gow.tools.anim.Skeleton;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 부서지는 물체의 파편 물리 객체 (Brkb +0x30 bit 0x1, 41 문서 §2.1) [확실: 식].
 * 원작: 파괴 진입 0x1DDF70 이 0xC10 바이트 객체 (0x30 바이트 슬롯 64 개 + 개수 +0xC00) 를 만들고 (0x1DEA28)
 * 루트의 첫 자식부터 <b>자식 없는 관절</b>마다 슬롯 {위치 +0x0, 속도 +0x10, 관절 행렬 +0x20, 관절 +0x24, 플래그 +0x2C = 1 (애니 따름)}.
 * 매 프레임 0x1DEBA8 (0x1DE278 에서):
 * <ul>
 *   <li>애니 따름 (bit 0x1): A = 애니 관절 월드 위치, 속도 = (A − P)·60. 선분 P + (0, 1.6, 0) → A − (0, 16, 0) 충돌이
 *       있으면 그 높이가 물체 루트 높이와 1.6 넘게 다를 때, 없으면 A.y &lt; 루트 y − 1.6 일 때 bit 를 끈다 (물리 시작).
 *       아니면 P = A, 관절 행렬 그대로.</li>
 *   <li>물리: V.y += −640·(1/60), P' = P + V/60. 선분 P + (0, 1.6, 0) → P' − (0, 1.6, 0) 이 부딪히면 V ×= 0.25, V 를 충돌 법선에
 *       반사 (V − 2(V·N)N), P' = P + V/60. 관절 행렬 = 애니 회전 + 위치 P' (부모 역행렬로 되돌려 쓴다).</li>
 *   <li>경계 반지름 = 루트에서 가장 먼 조각 거리 + 32 (0x1DE928) — 엔진은 안 씀.</li>
 * </ul>
 * 엔진: 모델 공간 관절 월드 행렬을 받아 월드로 옮겨 계산하고 다시 모델 공간으로 돌려준다. dt 는 호출자 프레임 (원작 1/60 고정).
 */
public final class BreakDebris {

	static final float GRAVITY = -640f;
	static final float BOUNCE = 0.25f;
	static final float LIFT = 1.6f;
	static final float PROBE = 16f;

	private final int[] leaves;
	private final Vector3f[] p, v;
	private final boolean[] anim;
	private boolean init;

	public BreakDebris(Skeleton sk) {
		int n = sk.size();
		boolean[] hasChild = new boolean[n];
		for (int j = 0; j < n; j++) {
			int par = sk.joints.get(j).parent();
			if (par >= 0 && par < n) {
				hasChild[par] = true;
			}
		}
		int first = 1;
		for (int j = 1; j < n; j++) {
			if (sk.joints.get(j).parent() == 0) {
				first = j; // 루트의 첫 자식 (원작 관절 레코드 +4)
				break;
			}
		}
		java.util.List<Integer> l = new java.util.ArrayList<>();
		for (int j = first; j < n && l.size() < 64; j++) {
			if (!hasChild[j]) {
				l.add(j);
			}
		}
		leaves = l.stream().mapToInt(Integer::intValue).toArray();
		p = new Vector3f[leaves.length];
		v = new Vector3f[leaves.length];
		anim = new boolean[leaves.length];
		for (int i = 0; i < leaves.length; i++) {
			p[i] = new Vector3f();
			v[i] = new Vector3f();
			anim[i] = true;
		}
	}

	public int pieces() {
		return leaves.length;
	}

	/** 물리로 넘어간 조각 수 (테스트). */
	public int falling() {
		int c = 0;
		for (boolean a : anim) {
			c += a ? 0 : 1;
		}
		return c;
	}

	/** 조각 i 의 월드 위치 (테스트). */
	public Vector3f position(int i) {
		return p[i];
	}

	/**
	 * 한 프레임.
	 *
	 * @param jw    애니 관절 월드 (모델 공간, 행벡터 float[16] — JOML 열 우선 배열과 같은 변환), 조각 관절은 이 배열에 덮어쓴다
	 * @param world 물체 월드 행렬
	 * @param s     레벨 충돌 (null 이면 바닥 없음)
	 */
	public void step(float[][] jw, Matrix4f world, MagicFx.Surface s, float dt) {
		if (dt <= 0) {
			dt = 1e-4f;
		}
		Matrix4f inv = new Matrix4f(world).invert();
		Matrix4f m = new Matrix4f();
		float rootY = m.set(jw[0]).mulLocal(world).m31();
		Vector3f a = new Vector3f();
		for (int i = 0; i < leaves.length; i++) {
			int j = leaves[i];
			m.set(jw[j]).mulLocal(world);
			m.getTranslation(a);
			if (!init) {
				p[i].set(a);
			}
			if (anim[i]) {
				Vector3f est = new Vector3f(a).sub(p[i]).div(dt);
				float[] hit = s != null ? s.cast(new Vector3f(p[i]).add(0, LIFT, 0), new Vector3f(a).sub(0, PROBE, 0)) : null;
				if (hit != null ? Math.abs(hit[1] - rootY) > LIFT : a.y < rootY - LIFT) {
					anim[i] = false;
				} else {
					p[i].set(a);
					v[i].set(est);
					continue;
				}
			}
			v[i].y += GRAVITY * dt;
			Vector3f np = new Vector3f(v[i]).mul(dt).add(p[i]);
			float[] hit = s != null ? s.cast(new Vector3f(p[i]).add(0, LIFT, 0), new Vector3f(np).sub(0, LIFT, 0)) : null;
			if (hit != null && hit.length >= 6) {
				Vector3f nrm = new Vector3f(hit[3], hit[4], hit[5]);
				v[i].mul(BOUNCE);
				v[i].sub(new Vector3f(nrm).mul(2 * v[i].dot(nrm)));
				np.set(v[i]).mul(dt).add(p[i]);
			}
			p[i].set(np);
			// 애니 회전 + 시뮬레이션 위치 → 모델 공간
			m.setTranslation(np);
			m.mulLocal(inv);
			m.get(jw[j]);
		}
		init = true;
	}
}
