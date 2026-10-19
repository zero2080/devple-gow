package devple.gow.engine.level;

import devple.gow.tools.level.CollisionSheet;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 정적 충돌 질의 (캐릭터 컨트롤러용). 레벨의 충돌 시트(월드 좌표 삼각형/사각형)를 XZ 균일 격자로 색인한다.
 * 원작은 시트의 kd 트리로 선분 캐스트를 하지만(12 문서 §3), 결과가 같으므로 여기서는 단순 격자를 쓴다.
 * <pre>
 *   float y = col.groundHeight(x, y + stepUp, z, maxDrop);    // 발밑 바닥 높이 (NaN = 없음 → 낙하)
 *   Hit h = col.raycast(origin, dir, maxDist);                 // 첫 충돌 (null = 없음)
 *   col.collideSphere(center, radius);                         // 벽 밀어내기 (center 수정, 밀린 경우 true)
 * </pre>
 * 바닥/벽 구분은 면 법선 Y: {@link #FLOOR_NY} 이상이면 바닥, 그 밖은 벽/천장.
 */
public final class LevelCollision {

	/** 바닥으로 보는 최소 법선 Y (약 45.6°). */
	public static final float FLOOR_NY = 0.7f;
	private static final float CELL = 32f;

	/** 월드 삼각형. n = 단위 법선, surface = 시트 표면(머티리얼) 번호. */
	public record Tri(Vector3f a, Vector3f b, Vector3f c, Vector3f n, int surface) {

		public boolean floor() {
			return Math.abs(n.y) >= FLOOR_NY;
		}
	}

	public record Hit(float distance, Vector3f point, Tri tri) {
	}

	private final List<Tri> tris = new ArrayList<>();
	private final Map<Long, List<Tri>> grid = new HashMap<>();

	public LevelCollision(List<CollisionSheet> sheets) {
		for (CollisionSheet s : sheets) {
			for (int[] t : s.triangles()) {
				Vector3f a = v(s.vertices[t[0]]), b = v(s.vertices[t[1]]), c = v(s.vertices[t[2]]);
				Vector3f n = new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a));
				if (n.lengthSquared() < 1e-8f) {
					continue;
				}
				n.normalize();
				add(new Tri(a, b, c, n, t[3]));
			}
		}
	}

	private static Vector3f v(float[] p) {
		return new Vector3f(p[0], p[1], p[2]);
	}

	private static long key(int ix, int iz) {
		return ((long) ix << 32) ^ (iz & 0xFFFFFFFFL);
	}

	private void add(Tri t) {
		tris.add(t);
		int x0 = (int) Math.floor(Math.min(t.a.x, Math.min(t.b.x, t.c.x)) / CELL);
		int x1 = (int) Math.floor(Math.max(t.a.x, Math.max(t.b.x, t.c.x)) / CELL);
		int z0 = (int) Math.floor(Math.min(t.a.z, Math.min(t.b.z, t.c.z)) / CELL);
		int z1 = (int) Math.floor(Math.max(t.a.z, Math.max(t.b.z, t.c.z)) / CELL);
		for (int ix = x0; ix <= x1; ix++) {
			for (int iz = z0; iz <= z1; iz++) {
				grid.computeIfAbsent(key(ix, iz), k -> new ArrayList<>()).add(t);
			}
		}
	}

	public List<Tri> triangles() {
		return tris;
	}

	/** XZ 사각 영역에 걸친 후보 삼각형 (중복 가능). */
	private List<Tri> candidates(float minX, float minZ, float maxX, float maxZ) {
		List<Tri> out = new ArrayList<>();
		int x0 = (int) Math.floor(minX / CELL), x1 = (int) Math.floor(maxX / CELL);
		int z0 = (int) Math.floor(minZ / CELL), z1 = (int) Math.floor(maxZ / CELL);
		if ((long) (x1 - x0 + 1) * (z1 - z0 + 1) > 4096) {
			return tris;
		}
		for (int ix = x0; ix <= x1; ix++) {
			for (int iz = z0; iz <= z1; iz++) {
				List<Tri> l = grid.get(key(ix, iz));
				if (l != null) {
					out.addAll(l);
				}
			}
		}
		return out;
	}

	/**
	 * 발밑 바닥 높이: (x, z) 수직선이 만나는 바닥 면 중 y 이하에서 가장 높은 것.
	 *
	 * @param y       기준 높이 (보통 발 위치 + 계단 오르기 허용치)
	 * @param maxDrop y 아래로 찾을 최대 거리
	 * @return 바닥 y, 없으면 NaN
	 */
	public float groundHeight(float x, float y, float z, float maxDrop) {
		float best = Float.NaN;
		for (Tri t : candidates(x, z, x, z)) {
			if (t.n.y < FLOOR_NY && t.n.y > -FLOOR_NY) {
				continue;
			}
			float h = heightAt(t, x, z);
			if (!Float.isNaN(h) && h <= y + 1e-3f && h >= y - maxDrop && (Float.isNaN(best) || h > best)) {
				best = h;
			}
		}
		return best;
	}

	/** 삼각형 평면의 (x, z) 높이, 삼각형 XZ 투영 밖이면 NaN. */
	private static float heightAt(Tri t, float x, float z) {
		float ax = t.a.x, az = t.a.z, bx = t.b.x, bz = t.b.z, cx = t.c.x, cz = t.c.z;
		float d = (bz - cz) * (ax - cx) + (cx - bx) * (az - cz);
		if (Math.abs(d) < 1e-9f) {
			return Float.NaN;
		}
		float u = ((bz - cz) * (x - cx) + (cx - bx) * (z - cz)) / d;
		float v = ((cz - az) * (x - cx) + (ax - cx) * (z - cz)) / d;
		float w = 1 - u - v;
		float e = -1e-4f;
		if (u < e || v < e || w < e) {
			return Float.NaN;
		}
		return u * t.a.y + v * t.b.y + w * t.c.y;
	}

	/** 반직선 캐스트 (Möller–Trumbore, 양면). dir 은 정규화하지 않아도 된다. */
	public Hit raycast(Vector3f o, Vector3f dir, float maxDist) {
		Vector3f d = new Vector3f(dir).normalize();
		Vector3f e = new Vector3f(o).fma(maxDist, d);
		Hit best = null;
		for (Tri t : candidates(Math.min(o.x, e.x), Math.min(o.z, e.z), Math.max(o.x, e.x), Math.max(o.z, e.z))) {
			float dist = intersect(o, d, t);
			if (dist >= 0 && dist <= maxDist && (best == null || dist < best.distance)) {
				best = new Hit(dist, new Vector3f(o).fma(dist, d), t);
			}
		}
		return best;
	}

	private static float intersect(Vector3f o, Vector3f d, Tri t) {
		Vector3f e1 = new Vector3f(t.b).sub(t.a), e2 = new Vector3f(t.c).sub(t.a);
		Vector3f p = new Vector3f(d).cross(e2);
		float det = e1.dot(p);
		if (Math.abs(det) < 1e-9f) {
			return -1;
		}
		float inv = 1 / det;
		Vector3f s = new Vector3f(o).sub(t.a);
		float u = s.dot(p) * inv;
		if (u < 0 || u > 1) {
			return -1;
		}
		Vector3f q = new Vector3f(s).cross(e1);
		float v = d.dot(q) * inv;
		if (v < 0 || u + v > 1) {
			return -1;
		}
		return e2.dot(q) * inv;
	}

	/**
	 * 구 vs 벽 밀어내기: 바닥이 아닌 면과 겹친 만큼 center 를 면 밖으로 민다 (3회 반복).
	 * 캡슐은 높이별 구 2~3개로 호출하면 된다.
	 *
	 * @return 한 번이라도 밀렸으면 true
	 */
	public boolean collideSphere(Vector3f center, float radius) {
		boolean moved = false;
		Vector3f cp = new Vector3f();
		for (int iter = 0; iter < 3; iter++) {
			boolean any = false;
			for (Tri t : candidates(center.x - radius, center.z - radius, center.x + radius, center.z + radius)) {
				if (t.floor()) {
					continue;
				}
				closestPoint(center, t, cp);
				float dx = center.x - cp.x, dy = center.y - cp.y, dz = center.z - cp.z;
				float d2 = dx * dx + dy * dy + dz * dz;
				if (d2 <= 1e-10f) {
					// 면 위: 법선 방향(위쪽 성분이 작은 쪽 기준 그대로)으로 반지름만큼
					center.fma(radius, t.n);
					any = moved = true;
				} else if (d2 < radius * radius) {
					float d = (float) Math.sqrt(d2);
					float push = radius - d;
					center.add(dx / d * push, dy / d * push, dz / d * push);
					any = moved = true;
				}
			}
			if (!any) {
				break;
			}
		}
		return moved;
	}

	/** 삼각형 위 최근접점 (Ericson, Real-Time Collision Detection 5.1.5). */
	static void closestPoint(Vector3f p, Tri t, Vector3f out) {
		Vector3f a = t.a, b = t.b, c = t.c;
		Vector3f ab = new Vector3f(b).sub(a), ac = new Vector3f(c).sub(a), ap = new Vector3f(p).sub(a);
		float d1 = ab.dot(ap), d2 = ac.dot(ap);
		if (d1 <= 0 && d2 <= 0) {
			out.set(a);
			return;
		}
		Vector3f bp = new Vector3f(p).sub(b);
		float d3 = ab.dot(bp), d4 = ac.dot(bp);
		if (d3 >= 0 && d4 <= d3) {
			out.set(b);
			return;
		}
		float vc = d1 * d4 - d3 * d2;
		if (vc <= 0 && d1 >= 0 && d3 <= 0) {
			out.set(a).fma(d1 / (d1 - d3), ab);
			return;
		}
		Vector3f cpv = new Vector3f(p).sub(c);
		float d5 = ab.dot(cpv), d6 = ac.dot(cpv);
		if (d6 >= 0 && d5 <= d6) {
			out.set(c);
			return;
		}
		float vb = d5 * d2 - d1 * d6;
		if (vb <= 0 && d2 >= 0 && d6 <= 0) {
			out.set(a).fma(d2 / (d2 - d6), ac);
			return;
		}
		float va = d3 * d6 - d5 * d4;
		if (va <= 0 && (d4 - d3) >= 0 && (d5 - d6) >= 0) {
			out.set(b).fma((d4 - d3) / ((d4 - d3) + (d5 - d6)), new Vector3f(c).sub(b));
			return;
		}
		float denom = 1 / (va + vb + vc);
		out.set(a).fma(vb * denom, ab).fma(vc * denom, ac);
	}
}
