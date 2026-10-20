package devple.gow.engine.light;

import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;

/**
 * LightServer (타입 6, 0x29CD44, 47 문서). 불러온 WAD 의 라이트를 모두 갖고, 물체마다 0x1521B0 으로 라이트 묶음을 만든다.
 * <p>
 * 목록 다시 만들기 (0x152150 → 0x152098, 더티 +0x40): 종류 비트 8 라이트는 전역 목록 (+0x44, 수 +0x74), 나머지는
 * 공간 색인 ("Lights" 레지스트리 0xC8 칸) 에 구 (위치, r1) 로 넣는다. 모으기는 공간 색인 질의 0x1755B0 (최대 0x20 개) + 전역 목록에서
 * |P − C|² ≤ (R + r1)² 인 것 [확실: 전역 목록 / 추정: 공간 색인도 같은 구-구 겹침].
 */
public final class LightServer {

	/** 공간 색인 질의 상한 (0x152264: a3 = 0x20). */
	public static final int QUERY_MAX = 0x20;
	/** 물체 구 반경 상한 (0x1521B4: 1000). */
	public static final float RADIUS_MAX = 1000f;

	private final List<Light> lights = new ArrayList<>();

	/** 게임 씬이 정하는 현재 서버 (없으면 렌더는 예전 고정 조명). */
	private static volatile LightServer current;

	public static LightServer current() {
		return current;
	}

	public static void setCurrent(LightServer s) {
		current = s;
	}

	public List<Light> lights() {
		return lights;
	}

	/** WAD 의 라이트를 모두 넣는다 (레벨 = 켜진 상태). 넣은 것들을 돌려준다. */
	public List<Light> addAll(WadFile wad) {
		List<Light> out = new ArrayList<>();
		for (LightDef d : LightDef.load(wad)) {
			Light l = new Light(d);
			lights.add(l);
			out.add(l);
		}
		return out;
	}

	public Light add(LightDef d) {
		Light l = new Light(d);
		lights.add(l);
		return l;
	}

	public void remove(Light l) {
		lights.remove(l);
	}

	/** 이름으로 (0x17A7B8 레지스트리 조회 대신, 대소문자 무시). */
	public Light find(String name) {
		for (Light l : lights) {
			if (l.def.name().equalsIgnoreCase(name)) {
				return l;
			}
		}
		return null;
	}

	/** 켜진 라이트가 하나라도 있나 (없으면 렌더는 예전 고정 조명). */
	public boolean any() {
		for (Light l : lights) {
			if (l.active) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 라이트 세기 (0x152330 – 0x1523EC) [확실]: d = |P − C| − R/3,
	 * d &gt; r1 → 0, d ≤ r0 → lerp(I0, I1, d / r0), 그 밖 → I1·(1 − (d − r0)/(r1 − r0)). 끝에 동적 배율 +0xE0 을 곱한다.
	 */
	public static float intensity(LightDef d, float dist, float dyn) {
		float f;
		if (d.r1() < dist) {
			f = 0f;
		} else if (dist <= d.r0()) {
			float t = d.r0() != 0 ? dist / d.r0() : 0f;
			f = (1f - t) * d.i0() + t * d.i1();
		} else {
			float span = d.r1() - d.r0();
			f = span != 0 ? (1f - (dist - d.r0()) / span) * d.i1() : 0f;
		}
		return f * dyn;
	}

	/** 구 (c, radius) 에 대한 라이트 묶음 (desat = 물체 렌더 플래그 8). */
	public LightRig gather(Vector3f c, float radius, boolean desat) {
		return gather(lights, c, radius, desat, new LightRig());
	}

	/**
	 * 0x1521B0 그대로 [확실: 디스어셈블]. 주변광 (종류 0 / 8) 은 색·세기 합을 수로 나눈 평균, 나머지는 그룹 (+0xD0) 별로
	 * 색·세기의 채널별 최댓값과 방향·세기 합 (정규화) 으로 하나의 방향 라이트가 된다. 점 라이트 (비트 1) 방향 = normalize(P − C).
	 */
	public static LightRig gather(List<Light> lights, Vector3f c, float radius, boolean desat, LightRig out) {
		float r = Math.min(radius, RADIUS_MAX);
		Vector3f ambSum = new Vector3f(), sum0 = new Vector3f(), sum1 = new Vector3f(), shadow = new Vector3f();
		Vector4f max0 = new Vector4f(), max1 = new Vector4f();
		int nAmb = 0, n0 = 0, n1 = 0, queried = 0;
		Vector3f d = new Vector3f(), col = new Vector3f();
		for (Light l : lights) {
			if (!l.active) {
				continue;
			}
			float d2 = l.pos.distanceSquared(c);
			float reach = r + l.def.r1();
			if (d2 > reach * reach) {
				continue;
			}
			if ((l.def.type() & LightDef.GLOBAL) == 0 && ++queried > QUERY_MAX) {
				continue;
			}
			float f = intensity(l.def, (float) Math.sqrt(d2) - r / 3f, l.dyn);
			col.set(l.def.color()[0], l.def.color()[1], l.def.color()[2]);
			float iw = l.def.i0(); // +0x30.w 도 같이 곱해지지만 슬롯 색 w 는 나중에 max(rgb) 로 덮인다
			if (desat && l.def.desat() > 0) {
				// 0x1523F0: 색을 최대 채널 쪽으로 +0xE4 만큼
				float m = Math.max(col.x, Math.max(col.y, col.z));
				col.add((m - col.x) * l.def.desat(), (m - col.y) * l.def.desat(), (m - col.z) * l.def.desat());
			}
			if (l.def.ambient()) {
				ambSum.fma(f, col);
				nAmb++;
				continue;
			}
			if (Math.abs(f) <= 1e-5f) {
				continue;
			}
			if ((l.def.type() & LightDef.POINT) != 0) {
				d.set(l.pos).sub(c);
				if (d.lengthSquared() > 0) {
					d.normalize();
				}
			} else {
				d.set(l.dir);
			}
			d.mul(f);
			if (l.def.group() == 0) {
				max0.max(new Vector4f(col.x * f, col.y * f, col.z * f, iw * f));
				sum0.add(d);
				n0++;
			} else {
				max1.max(new Vector4f(col.x * f, col.y * f, col.z * f, iw * f));
				sum1.add(d);
				n1++;
			}
			if ((l.def.type() & LightDef.SHADOW) != 0) {
				shadow.add(d);
			}
		}
		out.countAmbient = nAmb;
		out.count0 = n0;
		out.count1 = n1;
		if (nAmb > 0) {
			out.ambient.set(ambSum).div(nAmb);
		} else {
			out.ambient.zero();
		}
		slot(n0, sum0, max0, out.dir0, out.col0);
		slot(n1, sum1, max1, out.dir1, out.col1);
		if (shadow.lengthSquared() > 0) {
			out.shadowDir.set(shadow).normalize();
		} else {
			out.shadowDir.zero();
		}
		return out;
	}

	/**
	 * 관절 물체의 구 (중심 xyz, 반경 w) [근사]: 원작 0x157E90 은 모델 컴포넌트의 LOD 별 바운딩 구 (+0x124 + 0x14·LOD: 반경 +4, 중심 +8)
	 * 를 물체 행렬로 옮긴다. 엔진은 관절 월드 위치 AABB 의 중심·반 대각선으로 대신한다.
	 *
	 * @param joints 관절 월드 행렬 (행벡터, 이동 = [12..14])
	 * @param world  물체 행렬 (JOML 열벡터)
	 */
	public static org.joml.Vector4f jointSphere(float[][] joints, org.joml.Matrix4fc world, org.joml.Vector4f out) {
		float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, z0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE, z1 = -Float.MAX_VALUE;
		Vector3f p = new Vector3f();
		for (float[] j : joints) {
			world.transformPosition(j[12], j[13], j[14], p);
			x0 = Math.min(x0, p.x);
			y0 = Math.min(y0, p.y);
			z0 = Math.min(z0, p.z);
			x1 = Math.max(x1, p.x);
			y1 = Math.max(y1, p.y);
			z1 = Math.max(z1, p.z);
		}
		if (joints.length == 0) {
			world.transformPosition(0, 0, 0, p);
			return out.set(p.x, p.y, p.z, 1f);
		}
		float hx = (x1 - x0) / 2, hy = (y1 - y0) / 2, hz = (z1 - z0) / 2;
		return out.set(x0 + hx, y0 + hy, z0 + hz, Math.max(1f, (float) Math.sqrt(hx * hx + hy * hy + hz * hz)));
	}

	private static void slot(int n, Vector3f sum, Vector4f max, Vector3f dir, Vector4f col) {
		if (n == 0 || sum.lengthSquared() == 0) {
			dir.zero();
			col.zero();
			return;
		}
		dir.set(sum).normalize();
		col.set(max.x, max.y, max.z, Math.max(max.x, Math.max(max.y, max.z)));
	}
}
