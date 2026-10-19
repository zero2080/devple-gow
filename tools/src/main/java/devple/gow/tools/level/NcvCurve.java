package devple.gow.tools.level;

import devple.gow.tools.anim.Mat4;

import java.nio.ByteBuffer;

/**
 * 카메라 레일 커브 NCV_ (MESH_EXTRA 0x70 태그). 구간별 3차 다항식, 매개변수는 전역 t (구간 경계 = 매듭값).
 * <pre>
 * 0x00 u32 구간 수 n     0x04 u32 플래그 (bit0 = 닫힌 곡선, t 가 매듭 끝에서 0 으로 감김)
 * 0x08 u32 ×2 0xFFFFFFFF (로더가 구간/매듭 포인터로 덮어씀, 0x13E358)
 * 0x10 구간 n × 64B: vec4 a, b, c, d  — P(t) = a·t³ + b·t² + c·t + d (d.w = 1, 동차 → w 로 나눔)
 *      (구간 로컬 u 가 아니라 전역 t 로 계산한다 — 인접 구간이 경계 t 에서 이어지는 것으로 확인)
 * 0x10+n·64 f32[n] 매듭: 구간 i 는 t ∈ [knot[i-1], knot[i]] (knot[-1] = 0). 끝 매듭 = t 최대값
 * </pre>
 * 실행 파일 평가 0x1F2EA4: t 이상인 첫 매듭의 구간을 골라 (t³, t², t, 1) 을 곱한다 [확실].
 */
public final class NcvCurve {

	public final String name;
	public final boolean closed;
	/** 구간별 계수 16개: a(4) b(4) c(4) d(4). */
	public final float[][] seg;
	public final float[] knots;

	public NcvCurve(String name, boolean closed, float[][] seg, float[] knots) {
		this.name = name;
		this.closed = closed;
		this.seg = seg;
		this.knots = knots;
	}

	public static boolean is(String name) {
		return name.startsWith("NCV_");
	}

	public static NcvCurve parse(String name, ByteBuffer d) {
		int n = d.getInt(0);
		int flags = d.getInt(4);
		float[][] seg = new float[n][16];
		for (int i = 0; i < n; i++) {
			for (int k = 0; k < 16; k++) {
				seg[i][k] = d.getFloat(0x10 + i * 64 + k * 4);
			}
		}
		float[] knots = new float[n];
		for (int i = 0; i < n; i++) {
			knots[i] = d.getFloat(0x10 + n * 64 + i * 4);
		}
		return new NcvCurve(name, (flags & 1) != 0, seg, knots);
	}

	/** t 최대값 (끝 매듭). */
	public float tMax() {
		return knots[knots.length - 1];
	}

	/** 범위 정리: 닫힌 곡선은 감고, 열린 곡선은 [0, tMax] 로 자른다. */
	public float wrap(float t) {
		float m = tMax();
		if (closed) {
			t %= m;
			return t < 0 ? t + m : t;
		}
		return Math.max(0, Math.min(m, t));
	}

	private int segment(float t) {
		int i = 0;
		while (i < knots.length - 1 && t > knots[i]) {
			i++;
		}
		return i;
	}

	/** 위치 P(t) → out[0..2]. */
	public void eval(float t, float[] out) {
		float[] s = seg[segment(t)];
		float t2 = t * t, t3 = t2 * t;
		float w = s[3] * t3 + s[7] * t2 + s[11] * t + s[15];
		for (int k = 0; k < 3; k++) {
			out[k] = (s[k] * t3 + s[4 + k] * t2 + s[8 + k] * t + s[12 + k]) / w;
		}
	}

	/** 접선 dP/dt (w 미분은 무시 — w 는 항상 1 근처). */
	public void tangent(float t, float[] out) {
		float[] s = seg[segment(t)];
		for (int k = 0; k < 3; k++) {
			out[k] = 3 * s[k] * t * t + 2 * s[4 + k] * t + s[8 + k];
		}
	}

	/** 행렬(행벡터 규약, Mat4)로 옮긴 곡선. 상수항은 점, 나머지 계수는 방향으로 변환한다. */
	public NcvCurve transformed(float[] m) {
		float[][] out = new float[seg.length][16];
		float[] r = new float[3];
		for (int i = 0; i < seg.length; i++) {
			float[] s = seg[i];
			for (int row = 0; row < 4; row++) {
				if (row == 3) {
					Mat4.transformPoint(m, s[12] / s[15], s[13] / s[15], s[14] / s[15], r);
				} else {
					Mat4.transformDir(m, s[row * 4], s[row * 4 + 1], s[row * 4 + 2], r);
				}
				out[i][row * 4] = r[0];
				out[i][row * 4 + 1] = r[1];
				out[i][row * 4 + 2] = r[2];
				out[i][row * 4 + 3] = row == 3 ? 1 : 0;
			}
		}
		return new NcvCurve(name, closed, out, knots.clone());
	}
}
