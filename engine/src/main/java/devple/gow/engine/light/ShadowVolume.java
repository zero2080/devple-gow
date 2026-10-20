package devple.gow.engine.light;

import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * 그림자 볼륨 만들기 — renShadowServer 렌더 0x162FB8 (47 문서 §7.3) [확실: 디스어셈블].
 * <p>
 * 조각마다 (관절 행렬로 옮긴 정점·면 법선, 점 L = 중심 + 그림자 방향 × 64·반경):
 * <ul>
 *   <li>면의 첫 꼭짓점 v0 로 (v0 − L)·n &lt; 0 이면 L 을 보는 면 → 그 면의 모서리 참조마다 모서리 수 +1, 방향 기록 (0x1637D0)</li>
 *   <li>아니면 (L 반대쪽 면) 덮개: 가까운 덮개 = 면 그대로 (또는 투영), 먼 덮개 = 밀어낸 면 (띠, 0x163880)</li>
 *   <li>수가 1 인 모서리 = 실루엣 → 가까운 두 점과 먼 두 점의 사각형 (0x163A84)</li>
 * </ul>
 * 모드 (+0x14C):
 * <ul>
 *   <li>0 (기본) — 먼 점 = v + (v − L)·f, f = 16·min(16, +0x140)/(64·반경) (0x162C74) → 약 256 유닛 밀어냄</li>
 *   <li>1 (AI 생물, 0x1BF680) — 받는 평면에 L 에서 투영한 점 p 로 얇은 판: 가까운 = p + n·2.4 (+0x150), 먼 = p − n·1.6 (+0x154) (0x163C0C)</li>
 *   <li>2 (영웅 = +0x174 bit 31) — 가까운 = v, 먼 = 평면을 1.6 내린 평면에 투영 (0x16450C)</li>
 * </ul>
 * 출력 삼각형은 볼륨 바깥에서 볼 때 반시계 (GL 앞면) — 엔진은 GL 스텐실 z-fail 로 센다 [근사: 원작은 GS 가 직접 처리, 합성 방식 미해석].
 */
public final class ShadowVolume {

	public static final int MODE_EXTRUDE = 0, MODE_SLAB = 1, MODE_GROUND = 2;
	/** 판 위·아래 두께 (+0x150 = 2.4, +0x154 = 1.6; 생성자 0x162338). */
	public static final float ABOVE = 2.4f, BELOW = 1.6f;
	/** 밀어내기 기준 +0x140 = 전역 0x3362D0 = 16 (renShadowServer 생성자 0x165784). */
	public static final float REACH = 16f;
	/** L 거리 = 반경 × 64 (0x162B50). */
	public static final float L_DIST = 64f;

	private ShadowVolume() {
	}

	/** 출력 버퍼 (x, y, z 삼각형 목록). */
	public static final class Out {
		public float[] data = new float[3 * 3 * 256];
		public int size;

		void tri(Vector3f a, Vector3f b, Vector3f c) {
			if (size + 9 > data.length) {
				data = java.util.Arrays.copyOf(data, data.length * 2);
			}
			put(a);
			put(b);
			put(c);
		}

		private void put(Vector3f v) {
			data[size++] = v.x;
			data[size++] = v.y;
			data[size++] = v.z;
		}

		public int triangles() {
			return size / 9;
		}

		public void clear() {
			size = 0;
		}
	}

	/** 모드 0 밀어내기 배율 f = 16·REACH / (64·반경) (0x162C74 – 0x162C90). */
	public static float extrudeFactor(float radius) {
		return 16f * REACH / (L_DIST * Math.max(radius, 1e-3f));
	}

	/**
	 * 조각 하나의 볼륨.
	 *
	 * @param m      조각 → 월드 (물체 행렬 × 관절 행렬)
	 * @param light  점 L (월드)
	 * @param mode   0 / 1 / 2
	 * @param plane  받는 평면 (n, d: n·x + d = 0, n 은 L 쪽) — 모드 1·2
	 * @param factor 모드 0 밀어내기 배율
	 * @return 넣은 삼각형 수 (평면 투영이 안 되면 0)
	 */
	public static int build(ShadowGeometry.Piece p, Matrix4fc m, Vector3f light, int mode, Vector4f plane, float factor, Out out) {
		int nv = p.verts().length;
		Vector3f[] w = new Vector3f[nv], near = new Vector3f[nv], far = new Vector3f[nv];
		for (int i = 0; i < nv; i++) {
			float[] v = p.verts()[i];
			w[i] = m.transformPosition(v[0], v[1], v[2], new Vector3f());
		}
		if (mode != MODE_EXTRUDE) {
			float nl = plane.x * light.x + plane.y * light.y + plane.z * light.z;
			if (nl + plane.w <= 0) {
				return 0; // L 이 평면 아래 (0x162660 이 반경 0 을 돌려 그리지 않는 경우)
			}
		}
		for (int i = 0; i < nv; i++) {
			switch (mode) {
				case MODE_SLAB -> {
					Vector3f q = project(w[i], light, plane, 0f);
					if (q == null) {
						return 0;
					}
					near[i] = new Vector3f(plane.x, plane.y, plane.z).mul(ABOVE).add(q);
					far[i] = new Vector3f(plane.x, plane.y, plane.z).mul(-BELOW).add(q);
				}
				case MODE_GROUND -> {
					near[i] = w[i];
					far[i] = project(w[i], light, plane, BELOW);
					if (far[i] == null) {
						return 0;
					}
				}
				default -> {
					near[i] = w[i];
					far[i] = new Vector3f(w[i]).sub(light).mul(factor).add(w[i]);
				}
			}
		}
		int nf = p.faces().length;
		int[] count = new int[p.edges().length];
		int[] side = new int[p.edges().length];
		int start = out.size;
		Vector3f n = new Vector3f(), t = new Vector3f(), e1 = new Vector3f(), e2 = new Vector3f(), c = new Vector3f();
		for (int f = 0; f < nf; f++) {
			int cnt = p.faces()[f][0], s0 = p.faces()[f][1];
			float[] fn = p.normals()[f];
			m.transformDirection(fn[0], fn[1], fn[2], n);
			Vector3f v0 = w[p.corners()[s0]];
			float dot = (v0.x - light.x) * n.x + (v0.y - light.y) * n.y + (v0.z - light.z) * n.z;
			if (dot < 0) {
				for (int k = 0; k < cnt; k++) {
					int[] r = p.refs()[s0 + k];
					count[r[0]]++;
					side[r[0]] = r[1];
				}
				continue;
			}
			// 덮개: 띠 순서 꼭짓점 → 삼각형. 방향은 면 고리 (모서리 참조, 바깥 반시계) 를 따른다:
			// 먼 덮개 = 고리 방향 (바깥 = L 반대쪽), 가까운 덮개 = 뒤집음 (바깥 = L 쪽)
			loopNormal(p, w, s0, cnt, t);
			for (int k = 0; k + 2 < cnt; k++) {
				int a = p.corners()[s0 + k], b = p.corners()[s0 + k + 1], d = p.corners()[s0 + k + 2];
				e1.set(w[b]).sub(w[a]);
				e2.set(w[d]).sub(w[a]);
				if (e1.cross(e2, c).dot(t) < 0) {
					int x = b;
					b = d;
					d = x;
				}
				out.tri(near[a], near[d], near[b]);
				out.tri(far[a], far[b], far[d]);
			}
		}
		for (int e = 0; e < count.length; e++) {
			if (count[e] != 1) {
				continue;
			}
			// L 을 보는 면이 모서리를 a → b 로 돈다 (면 고리는 법선 기준 반시계) → 옆면 (b, a, a'), (b, a', b')
			int a = p.edges()[e][side[e] == 0 ? 0 : 1], b = p.edges()[e][side[e] == 0 ? 1 : 0];
			out.tri(near[b], near[a], far[a]);
			out.tri(near[b], far[a], far[b]);
		}
		return (out.size - start) / 9;
	}

	/** 면 고리 (모서리 참조 순서) 의 Newell 법선 — 위상 방향 (저장된 면 법선과 거의 같다). */
	private static void loopNormal(ShadowGeometry.Piece p, Vector3f[] w, int s0, int cnt, Vector3f out) {
		out.zero();
		for (int k = 0; k < cnt; k++) {
			int[] r = p.refs()[s0 + k];
			Vector3f a = w[p.edges()[r[0]][r[1]]], b = w[p.edges()[r[0]][1 - r[1]]];
			out.add(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x);
		}
	}

	/**
	 * L 에서 v 를 지나는 직선과 평면 (d 를 drop 만큼 내림) 의 교점 (0x1640C0: t = (n·v + d')/(n·v − n·L), p = v − (v − L)·t).
	 * v 가 L 보다 평면 쪽에 있지 않으면 null.
	 */
	public static Vector3f project(Vector3f v, Vector3f light, Vector4f plane, float drop) {
		float nv = plane.x * v.x + plane.y * v.y + plane.z * v.z;
		float nl = plane.x * light.x + plane.y * light.y + plane.z * light.z;
		float den = nv - nl;
		if (den > -1e-4f) {
			return null;
		}
		float tt = (nv + plane.w + drop) / den;
		return new Vector3f(v).sub(light).mul(-tt).add(v);
	}

	/**
	 * 사슬 그림자 (Blades_Init 의 MAT_stencil 띠, 0x1FD218: 블레이드 +0xA8 bit 1 = 받는 평면 있음) — 사슬 점을 L 에서 평면에 투영한 얇은 판 [근사: 폭·두께].
	 *
	 * @param pts       사슬 표본 (월드)
	 * @param halfWidth 반폭 (Shadow Diameter × 8)
	 */
	public static int chain(Vector3f[] pts, float halfWidth, Vector3f light, Vector4f plane, Out out) {
		int start = out.size;
		Vector3f nrm = new Vector3f(plane.x, plane.y, plane.z);
		for (int i = 0; i + 1 < pts.length; i++) {
			Vector3f a = project(pts[i], light, plane, 0f), b = project(pts[i + 1], light, plane, 0f);
			if (a == null || b == null) {
				continue;
			}
			Vector3f dir = new Vector3f(b).sub(a);
			if (dir.lengthSquared() < 1e-8f) {
				continue;
			}
			Vector3f sideV = new Vector3f(nrm).cross(dir).normalize(halfWidth);
			box(a, b, sideV, nrm, out);
		}
		return (out.size - start) / 9;
	}

	/** 선분 a→b 를 옆 ±s, 위 ABOVE / 아래 BELOW 로 감싼 상자 (바깥 반시계). */
	static void box(Vector3f a, Vector3f b, Vector3f s, Vector3f up, Out out) {
		Vector3f[] v = new Vector3f[8];
		Vector3f hi = new Vector3f(up).mul(ABOVE), lo = new Vector3f(up).mul(-BELOW);
		int k = 0;
		for (Vector3f p : new Vector3f[]{a, b}) {
			for (Vector3f h : new Vector3f[]{lo, hi}) {
				v[k++] = new Vector3f(p).sub(s).add(h);
				v[k++] = new Vector3f(p).add(s).add(h);
			}
		}
		// v: 0 a−s lo, 1 a+s lo, 2 a−s hi, 3 a+s hi, 4 b−s lo, 5 b+s lo, 6 b−s hi, 7 b+s hi
		int[][] quads = {{0, 1, 3, 2}, {4, 6, 7, 5}, {0, 2, 6, 4}, {1, 5, 7, 3}, {2, 3, 7, 6}, {0, 4, 5, 1}};
		Vector3f center = new Vector3f(a).add(b).mul(0.5f).add(new Vector3f(up).mul((ABOVE - BELOW) / 2));
		Vector3f e1 = new Vector3f(), e2 = new Vector3f(), n = new Vector3f();
		for (int[] q : quads) {
			e1.set(v[q[1]]).sub(v[q[0]]);
			e2.set(v[q[2]]).sub(v[q[0]]);
			e1.cross(e2, n);
			boolean flip = n.dot(new Vector3f(v[q[0]]).sub(center)) < 0;
			if (!flip) {
				out.tri(v[q[0]], v[q[1]], v[q[2]]);
				out.tri(v[q[0]], v[q[2]], v[q[3]]);
			} else {
				out.tri(v[q[0]], v[q[2]], v[q[1]]);
				out.tri(v[q[0]], v[q[3]], v[q[2]]);
			}
		}
	}
}
