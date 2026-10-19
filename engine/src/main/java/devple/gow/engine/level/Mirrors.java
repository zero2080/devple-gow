package devple.gow.engine.level;

import devple.gow.tools.fx.EnvParams;
import devple.gow.tools.model.TriMesh;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * SCR_Mirror (0x2464C8) 반사 평면 [확실: 코드 흐름 / 근사: GL 합성] — 43 문서 §9.
 * <pre>
 * 원작: 0x2464C8 → 0x129820 이 소유 GO 모델의 렌더 플래그 bit 61 (반사에 비침) 을 지워 거울이 자기 자신을 비추지 않게 하고
 *       (일반 뷰에는 그대로 그린다), 거울 관리자 (표 0x29CD48, 뷰 2 개) 마다 거울 렌더러 0x12AA18 {+0x20 모델, +0x24 값 [0], +0x28 값 [4]} 를 건다.
 *       관리자 갱신 0x12A6C8: 렌더러 중 0x12A330 (모델 Y 축 = 법선, 카메라가 앞쪽일 때 거리) 가 가장 가까운 거울 하나만 고른다 →
 *       거울 뷰 (+0x8) 를 켜고 카메라를 vtable 1 = 0x12A4E8 로 평면 반사 (v − 2(n·v − d)·n, X 축 부호 반전).
 * 무엇을 그리나 [확실: 뷰 마스크]: 모델 뷰 마스크 (+0xD8, 0x1508D0) & 뷰 마스크 (+0x3AC) ≠ 0 인 모델만 그 뷰에 그린다.
 *       일반 뷰 = 0x41, 거울 뷰 = (0x41 & 0x40000055) &lt;&lt; 1 = 0x82, 값 [4] ≠ 0 이면 0x80 을 뺀 0x02.
 *       모델 마스크 0x1508D0(m): bit 57 → (m & 0x40000055) &lt;&lt; 1 (거울 전용), bit 61 → m | 그 시프트 (둘 다), 그 밖 → m & 0x7FFFFF55 (일반만).
 *       레벨 모델 m = 0x01 (0x1503E0), 렌더 플래그 = MDL +0x28 (0x156820). 영웅 (0x1DAEC0)·블레이드 (Blades_Init, 0x2015B8)·무기 (0x19C240)·
 *       PushPull/IO_TandF/IO_CSM (0x1DF1E0) 은 bit 61 을 켜고 m = 0x40 → 0xC0 (값 [4] 가 끄는 0x80 쪽).
 *       ⇒ 정적 월드는 다시 그리지 않는다: 반사 = 반사 전용 대리 메시 (bit 57) + bit 61 모델 + (값 [4] = 0 이면) 영웅 무리. 적은 bit 61 이 없다.
 *       값 [0] ≠ 0 = 거울 뷰 목록 +0x14 (vtable 0x2BE268, 0x120498 → 0x11FEA0 스프라이트 2 회, 계수 0.5) 켬 — 반사 이미지 후처리 [추정: 흐림] 미구현.
 * 엔진: 평면 = 거울 메시 (월드) 의 면적 가중 법선 · 무게중심. 불투명 패스 뒤 거울 화소를 스텐실로 표시 → 그 화소 깊이를 먼 값으로 →
 *       VP·R (R = 평면 반사) 로 하늘 + 거울 뷰 마스크에 맞는 인스턴스·영웅을 다시 그린다 (평면 뒤쪽은 gl_ClipDistance 로 자름, 감김 반전) →
 *       거울 깊이 복원 → 거울 모델을 그 위에 두 패스로 [근사: GS 합성 0x12ABA0 미해석].
 * </pre>
 */
final class Mirrors {

	/** 거울 하나: 평면 n·x = d (n 단위), 최대 평면 이탈 (평탄도 검사). */
	record Mirror(String key, Vector3f normal, float d, float maxDev, int a, int b, Vector3f center) {

		Mirror(String key, Vector3f normal, float d, float maxDev, int a, int b) {
			this(key, normal, d, maxDev, a, b, new Vector3f(normal).mul(d));
		}

		/** 거울 뷰 마스크 (0x12A7A4: 값 [4] ≠ 0 이면 영웅 무리 0x80 을 뺀다). */
		int viewMask() {
			return mirrorViewMask(MAIN_VIEW, b);
		}

		/** 카메라 쪽을 향하는 평면 (n, −d) — gl_ClipDistance 용 (원래 장면에서 거울 뒤쪽을 자른다). */
		float[] clipPlane(Vector3f eye) {
			float s = normal.dot(eye) - d >= 0 ? 1 : -1;
			return new float[]{normal.x * s, normal.y * s, normal.z * s, -d * s};
		}

		/** 평면 반사 행렬 (월드). */
		Matrix4f reflection() {
			return new Matrix4f().reflection(normal.x, normal.y, normal.z, -d);
		}

		Vector3f reflect(Vector3f p) {
			float k = 2 * (normal.dot(p) - d);
			return new Vector3f(p).sub(normal.x * k, normal.y * k, normal.z * k);
		}
	}

	private Mirrors() {
	}

	/** 일반 뷰 마스크 (뷰 생성자 0x168D38 / 0x168E68). */
	static final int MAIN_VIEW = 0x41;
	/** 레벨 모델 기본 마스크 (0x1503E0). */
	static final int LEVEL_MODEL = 0x01;
	/** 영웅·블레이드·PushPull/IO_TandF/IO_CSM 모델 마스크 (0x1DAEC0, 0x1DF1E0 …: bit 61 과 함께). */
	static final int HERO_GROUP = 0x40;

	/** 0x1508D0: 렌더 플래그 → 모델 뷰 마스크. */
	static int modelMask(long renderFlags, int m) {
		if ((renderFlags & (1L << 57)) != 0) {
			return (m & 0x40000055) << 1;
		}
		if ((renderFlags & (1L << 61)) != 0) {
			return ((m & 0x40000055) << 1) | m;
		}
		return m & 0x7FFFFF55;
	}

	/** 0x129AA8 + 0x12A7A4: 거울 뷰 마스크 = (일반 & 0x40000055) &lt;&lt; 1, 값 [4] ≠ 0 이면 0x80 뺌. */
	static int mirrorViewMask(int mainView, int b) {
		int v = (mainView & 0x40000055) << 1;
		return b != 0 ? v & ~0x80 : v | 0x80;
	}

	/** 0x12A6C8: 가장 가까운 거울 하나 (카메라 → 거울 중심 거리, 0x12A330). 후보가 없으면 null. */
	static Mirror nearest(java.util.List<Mirror> candidates, Vector3f eye) {
		Mirror best = null;
		float bd = Float.MAX_VALUE;
		for (Mirror m : candidates) {
			float dd = m.center().distanceSquared(eye);
			if (dd < bd) {
				bd = dd;
				best = m;
			}
		}
		return best;
	}

	/** SCR_Mirror 인스턴스 스크립트의 소유 GO 이름 (소문자) → 값 [0], [4]. 빈 소유자 (ATHN04B 템플릿 복사본) 는 뺀다. */
	static Map<String, int[]> owners(EnvParams env) {
		Map<String, int[]> out = new HashMap<>();
		for (EnvParams.Raw r : env.others) {
			if (!"SCR_Mirror".equals(r.target()) || r.owner() == null || r.owner().isBlank() || !r.script().instance()) {
				continue;
			}
			int sz = r.script().values().limit();
			out.put(r.owner().trim().toLowerCase(Locale.ROOT), new int[]{sz >= 4 ? r.script().i(0) : 0, sz >= 8 ? r.script().i(4) : 0});
		}
		return out;
	}

	/**
	 * 평면이 아닌 거울 메시 (ATHN01E goreflfloor 이탈 10, PAND05B oorMirrorGRP …): 원작 0x12A330 처럼 모델 Y 축 (관절 0 월드 행렬 2 행) 을
	 * 법선으로, 무게중심을 지나는 평면. 데이터상 평면 거울의 맞춘 법선도 모두 이 축과 평행하다.
	 */
	static Mirror axisPlane(Mirror fitted, float[] joint0World) {
		Vector3f n = new Vector3f(joint0World[4], joint0World[5], joint0World[6]);
		if (n.lengthSquared() == 0) {
			return fitted;
		}
		n.normalize();
		return new Mirror(fitted.key(), n, n.dot(fitted.center()), fitted.maxDev(), fitted.a(), fitted.b(), fitted.center());
	}

	/** 월드 메시의 평면: 면적 가중 법선 (단위), d = n·무게중심, 최대 이탈. 면적이 0 이면 null. */
	static Mirror fit(String key, TriMesh tm, int[] ab) {
		Vector3f n = new Vector3f();
		Vector3f c = new Vector3f();
		float area = 0;
		Vector3f e1 = new Vector3f(), e2 = new Vector3f(), x = new Vector3f();
		for (TriMesh.Sub s : tm.subs) {
			for (int[] t : s.tris) {
				float[] p0 = s.pos.get(t[0]), p1 = s.pos.get(t[1]), p2 = s.pos.get(t[2]);
				e1.set(p1[0] - p0[0], p1[1] - p0[1], p1[2] - p0[2]);
				e2.set(p2[0] - p0[0], p2[1] - p0[1], p2[2] - p0[2]);
				e1.cross(e2, x);
				float a = x.length() / 2;
				if (a <= 0) {
					continue;
				}
				// 감김 방향이 섞여 있어도 한쪽으로 모은다 (첫 면 기준)
				if (n.lengthSquared() > 0 && x.dot(n) < 0) {
					x.negate();
				}
				n.add(x);
				c.add((p0[0] + p1[0] + p2[0]) / 3 * a, (p0[1] + p1[1] + p2[1]) / 3 * a, (p0[2] + p1[2] + p2[2]) / 3 * a);
				area += a;
			}
		}
		if (area <= 0 || n.lengthSquared() == 0) {
			return null;
		}
		n.normalize();
		c.div(area);
		float d = n.dot(c);
		float dev = 0;
		for (TriMesh.Sub s : tm.subs) {
			for (float[] p : s.pos) {
				dev = Math.max(dev, Math.abs(n.x * p[0] + n.y * p[1] + n.z * p[2] - d));
			}
		}
		return new Mirror(key, n, d, dev, ab[0], ab[1], c);
	}
}
