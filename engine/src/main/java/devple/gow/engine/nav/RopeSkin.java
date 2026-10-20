package devple.gow.engine.nav;

import devple.gow.tools.anim.Skeleton;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 줄 시뮬레이션 노드 → 줄 모델 관절 (SCR_RopeSkinned, 38 문서 §17) [확실: 디스어셈블].
 * <ul>
 *   <li>사슬 (0x216FC8): 기준 관절 (+0x48, 줄 오브젝트 관절 0 = 매단 점) 의 첫 자식부터 형제를 따라가며 관절 플래그 0x20 인 것만,
 *       기준 관절 위치에서의 거리 순 (0x234088 정렬 목록, 가까운 것 = 노드 0). 노드 수 +0x54 = 사슬 관절 수, 노드 i 의 관절 포인터 +0x34.</li>
 *   <li>관절 행렬 (0x217E5C – 0x218190, 매 프레임 시뮬레이션 뒤, 잠들면 건너뜀): 노드 i 의 Y = normalize(p[i−1] − p[i+1])
 *       (양 끝은 한쪽 차분, 줄 위쪽 방향), X = normalize(Y × ref), Z = X × Y, 위치 = p[i] × 16.
 *       ref = 양끝 고정 줄 (+0x374 비트 1) 이면 (0, 1, 0), 매단 줄이면 (0, 0, 1). 행 (X, Y, Z, P) × 오브젝트 행렬⁻¹ = 오브젝트 공간 관절 행렬
 *       → 렌더가 오브젝트 행렬을 다시 곱하므로 월드 관절 행렬 = (X, Y, Z, P).</li>
 * </ul>
 */
public final class RopeSkin {

	/** 줄 사슬 관절 플래그 (0x217050: 관절 레코드 +0 & 0x20). */
	public static final int CHAIN_FLAG = 0x20;

	private RopeSkin() {
	}

	/**
	 * 사슬 관절 번호 (노드 순서).
	 *
	 * @param world 관절 월드 행렬 (거리 정렬용, 행벡터 이동 [12..14])
	 * @param root  기준 관절 (+0x48)
	 */
	public static int[] chain(Skeleton sk, float[][] world, int root) {
		List<Integer> out = new ArrayList<>();
		if (root < 0 || root >= sk.size()) {
			return new int[0];
		}
		int j = sk.joints.get(root).firstChild();
		java.util.Set<Integer> seen = new java.util.HashSet<>();
		while (j >= 0 && j < sk.size() && seen.add(j)) {
			if ((sk.joints.get(j).flags() & CHAIN_FLAG) != 0) {
				out.add(j);
			}
			j = sk.joints.get(j).nextSibling();
		}
		float[] r = world[root];
		out.sort(Comparator.comparingDouble(i -> dist2(world[i], r)));
		return out.stream().mapToInt(Integer::intValue).toArray();
	}

	private static double dist2(float[] a, float[] b) {
		double x = a[12] - b[12], y = a[13] - b[13], z = a[14] - b[14];
		return x * x + y * y + z * z;
	}

	/**
	 * 노드 위치 (월드 유닛) → 사슬 관절 월드 행렬 (행벡터 float[16]: 행 0 = X, 1 = Y, 2 = Z, 3 = 위치).
	 *
	 * @param fixed 양끝 고정 줄 (ref = 위)
	 */
	public static float[][] joints(Vector3f[] p, boolean fixed) {
		int n = p.length;
		float[][] out = new float[n][];
		Vector3f ref = fixed ? new Vector3f(0, 1, 0) : new Vector3f(0, 0, 1);
		Vector3f y = new Vector3f(), x = new Vector3f(), z = new Vector3f();
		for (int i = 0; i < n; i++) {
			if (n < 2) {
				y.set(0, 1, 0);
			} else if (i == 0) {
				y.set(p[0]).sub(p[1]);
			} else if (i == n - 1) {
				y.set(p[n - 2]).sub(p[n - 1]);
			} else {
				y.set(p[i - 1]).sub(p[i + 1]);
			}
			if (y.lengthSquared() < 1e-12f) {
				y.set(0, 1, 0);
			}
			y.normalize();
			y.cross(ref, x);
			if (x.lengthSquared() < 1e-12f) {
				x.set(1, 0, 0); // Y ∥ ref (원작은 0 나누기) [추정]
			}
			x.normalize();
			x.cross(y, z);
			out[i] = new float[]{x.x, x.y, x.z, 0, y.x, y.y, y.z, 0, z.x, z.y, z.z, 0, p[i].x, p[i].y, p[i].z, 1};
		}
		return out;
	}

	/** 기본 관절 월드 (정지 자세) 에 사슬 관절만 시뮬레이션 자세로 바꾼 복사본. 노드 수와 사슬 수가 다르면 짧은 쪽까지. */
	public static float[][] pose(float[][] base, int[] chain, Vector3f[] nodes, boolean fixed) {
		float[][] w = base.clone();
		float[][] j = joints(nodes, fixed);
		for (int i = 0; i < Math.min(chain.length, j.length); i++) {
			w[chain[i]] = j[i];
		}
		return w;
	}
}
