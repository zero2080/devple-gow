package devple.gow.tools.anim;

/**
 * 4×4 행렬 유틸 (float[16], 행 우선 저장, <b>행벡터</b> 규약 — PS2 VU/게임 데이터와 동일).
 * <pre>
 * p' = p · M          (x' = x·m0 + y·m4 + z·m8 + m12)
 * world = local · parentWorld
 * </pre>
 */
public final class Mat4 {

	private Mat4() {
	}

	public static float[] identity() {
		float[] m = new float[16];
		m[0] = m[5] = m[10] = m[15] = 1;
		return m;
	}

	/** a · b */
	public static float[] mul(float[] a, float[] b) {
		float[] r = new float[16];
		mulInto(a, b, r);
		return r;
	}

	/** r = a · b ({@link #mul} 와 같은 계산 순서). r 은 a·b 와 겹치면 안 된다. */
	public static void mulInto(float[] a, float[] b, float[] r) {
		for (int i = 0; i < 4; i++) {
			for (int j = 0; j < 4; j++) {
				float s = 0;
				for (int k = 0; k < 4; k++) {
					s += a[i * 4 + k] * b[k * 4 + j];
				}
				r[i * 4 + j] = s;
			}
		}
	}

	/** m = 단위 행렬. */
	public static void setIdentity(float[] m) {
		java.util.Arrays.fill(m, 0, 16, 0f);
		m[0] = m[5] = m[10] = m[15] = 1;
	}

	/** 점 변환 (w=1). */
	public static void transformPoint(float[] m, float x, float y, float z, float[] out) {
		out[0] = x * m[0] + y * m[4] + z * m[8] + m[12];
		out[1] = x * m[1] + y * m[5] + z * m[9] + m[13];
		out[2] = x * m[2] + y * m[6] + z * m[10] + m[14];
	}

	/** 방향 변환 (w=0). */
	public static void transformDir(float[] m, float x, float y, float z, float[] out) {
		out[0] = x * m[0] + y * m[4] + z * m[8];
		out[1] = x * m[1] + y * m[5] + z * m[9];
		out[2] = x * m[2] + y * m[6] + z * m[10];
	}

	/** 정규화된 쿼터니언 (x,y,z,w) → 3×3 회전 (행벡터 규약). */
	public static float[] fromQuat(float x, float y, float z, float w) {
		float[] m = new float[16];
		fromQuatInto(x, y, z, w, m);
		return m;
	}

	/** {@link #fromQuat} 를 m 에 (할당 없음, 같은 값). */
	public static void fromQuatInto(float x, float y, float z, float w, float[] m) {
		setIdentity(m);
		float n = (float) Math.sqrt(x * x + y * y + z * z + w * w);
		if (n == 0) {
			return;
		}
		x /= n;
		y /= n;
		z /= n;
		w /= n;
		m[0] = 1 - 2 * (y * y + z * z);
		m[1] = 2 * (x * y + z * w);
		m[2] = 2 * (x * z - y * w);
		m[4] = 2 * (x * y - z * w);
		m[5] = 1 - 2 * (x * x + z * z);
		m[6] = 2 * (y * z + x * w);
		m[8] = 2 * (x * z + y * w);
		m[9] = 2 * (y * z - x * w);
		m[10] = 1 - 2 * (x * x + y * y);
	}

	/** 오일러 각(라디안) → Rx · Ry · Rz (행벡터: X 먼저 적용). CXT 로컬 행렬과 일치 검증됨. */
	public static float[] fromEuler(float ax, float ay, float az) {
		float[] m = new float[16];
		fromEulerInto(ax, ay, az, m, new float[16], new float[16], new float[16], new float[16]);
		return m;
	}

	/**
	 * {@link #fromEuler} 를 out 에 (같은 행렬 곱 순서 = 같은 값). rx·ry·rz·t 는 작업 공간 (out 과 겹치면 안 됨).
	 */
	public static void fromEulerInto(float ax, float ay, float az, float[] out, float[] rx, float[] ry, float[] rz, float[] t) {
		float cx = (float) Math.cos(ax), sx = (float) Math.sin(ax);
		float cy = (float) Math.cos(ay), sy = (float) Math.sin(ay);
		float cz = (float) Math.cos(az), sz = (float) Math.sin(az);
		setIdentity(rx);
		rx[5] = cx;
		rx[6] = sx;
		rx[9] = -sx;
		rx[10] = cx;
		setIdentity(ry);
		ry[0] = cy;
		ry[2] = -sy;
		ry[8] = sy;
		ry[10] = cy;
		setIdentity(rz);
		rz[0] = cz;
		rz[1] = sz;
		rz[4] = -sz;
		rz[5] = cz;
		mulInto(rx, ry, t);
		mulInto(t, rz, out);
	}

	/** 위쪽 3×3 끼리의 곱 a·b (이동 성분 무시, 결과 이동 0). */
	public static float[] mul3(float[] a, float[] b) {
		float[] r = new float[16];
		mul3Into(a, b, r);
		return r;
	}

	/** {@link #mul3} 를 r 에 (r 은 a·b 와 겹치면 안 된다). */
	public static void mul3Into(float[] a, float[] b, float[] r) {
		setIdentity(r);
		for (int i = 0; i < 3; i++) {
			for (int j = 0; j < 3; j++) {
				r[i * 4 + j] = a[i * 4] * b[j] + a[i * 4 + 1] * b[4 + j] + a[i * 4 + 2] * b[8 + j];
			}
		}
	}

	/** 아핀 역행렬 (마지막 열 = 0,0,0,1 가정). */
	public static float[] invertAffine(float[] m) {
		float a = m[0], b = m[1], c = m[2], d = m[4], e = m[5], f = m[6], g = m[8], h = m[9], i = m[10];
		float A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
		float det = a * A + b * B + c * C;
		float inv = det == 0 ? 0 : 1 / det;
		float[] r = identity();
		r[0] = A * inv;
		r[1] = -(b * i - c * h) * inv;
		r[2] = (b * f - c * e) * inv;
		r[4] = B * inv;
		r[5] = (a * i - c * g) * inv;
		r[6] = -(a * f - c * d) * inv;
		r[8] = C * inv;
		r[9] = -(a * h - b * g) * inv;
		r[10] = (a * e - b * d) * inv;
		float tx = m[12], ty = m[13], tz = m[14];
		r[12] = -(tx * r[0] + ty * r[4] + tz * r[8]);
		r[13] = -(tx * r[1] + ty * r[5] + tz * r[9]);
		r[14] = -(tx * r[2] + ty * r[6] + tz * r[10]);
		return r;
	}

	/** 두 행렬의 원소별 절대차 합 (검증용). */
	public static float diff(float[] a, float[] b) {
		float s = 0;
		for (int i = 0; i < 16; i++) {
			s += Math.abs(a[i] - b[i]);
		}
		return s;
	}
}
