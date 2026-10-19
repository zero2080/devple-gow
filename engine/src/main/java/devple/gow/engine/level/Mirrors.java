package devple.gow.engine.level;

import devple.gow.tools.fx.EnvParams;
import devple.gow.tools.model.TriMesh;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * SCR_Mirror (0x2464C8) 반사 평면 [확실: 코드 흐름 / 근사: GL 합성].
 * <pre>
 * 원작: 0x2464C8 → 0x129820 이 소유 GO 의 렌더 플래그 bit 61 을 지워 일반 그리기에서 빼고, 뷰 슬롯 2 개 (표 0x29CD48) 마다
 *       거울 렌더러 0x12AA18 (vtable 0x2BDBF8, 인자 = 모델, 값 [0], [4]) 를 뷰 목록에 건다.
 *       vtable 1 = 0x12A4E8: 카메라 행렬 (축 3 + 위치) 을 평면 (n, d) 에 대해 반사한다 — 각 행 v' = v − 2(n·v − d)·n
 *       (위치는 그대로, 축은 원점 기준 반사 후 정규화, X 축 부호 반전 = 손잡이 보정). vtable 5 = 0x12ABA0: GS 패킷 (FRAME/TEST/ALPHA …)
 *       으로 반사 카메라 장면을 거울 화면 영역에 그리고 거울 모델을 그 위에 합성한다 (세부 GS 레지스터 순서는 미해석).
 * 데이터: 거울 모델은 바닥 그 자체다 — OLYMP02 {@code gomirror} = MAT_tnMarblePlainNew00b (알파 블렌드 대리석),
 *       ATHN06B/OLYMP01 floorReflect, ATHN04A puddle00, PAND01BB mirrorFloor … (전 레벨 인스턴스 37 개, 값 [0],[4] = (1,0)/(0,1)/(0,0)).
 * 엔진: 평면 = 거울 메시 (월드) 의 면적 가중 법선 · 무게중심. 불투명 패스 뒤 거울 화소를 스텐실로 표시 → 그 화소 깊이를 먼 값으로 →
 *       VP·R (R = 평면 반사) 로 하늘·레벨 인스턴스를 다시 그린다 (평면 뒤쪽은 gl_ClipDistance 로 자름) → 거울 깊이 복원 →
 *       거울 모델은 반투명 패스에서 평소처럼 (알파 블렌드) 위에 그려진다.
 *       캐릭터 (영웅·적) 는 반사에 넣지 않는다 [근사]. 값 [0],[4] 의 의미 (캐릭터/월드 반사 여부 추정) 미해석.
 * </pre>
 */
final class Mirrors {

	/** 거울 하나: 평면 n·x = d (n 단위), 최대 평면 이탈 (평탄도 검사). */
	record Mirror(String key, Vector3f normal, float d, float maxDev, int a, int b) {

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
		return new Mirror(key, n, d, dev, ab[0], ab[1]);
	}
}
