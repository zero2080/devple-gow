package devple.gow.engine.fx;

import devple.gow.engine.game.Tweaks;

/**
 * 카메라 필터 — {@code /TweakTemplates/CameraFilter/NNN/} 템플릿 (45 문서 §1) [확실: 코드].
 * <ul>
 *   <li>템플릿 생성자 0x1F4B90 (목록 0x337B74): RGBA 3개 (해시 0x0014E3D5 → +0x20 위, 0xB7EB5695 → +0x30 가운데, 0x7947B8AB → +0x40 아래;
 *       성분 키 = Hash127(":R"/":G"/":B"/":A", 필드 해시), 0x1823F8), Toppoint +0x50, Midpoint +0x54, BottomPoint +0x58 (f32),
 *       Blend Mode 열거 +0x5C (하위 바이트, 0x181EA0).</li>
 *   <li>카메라 관리자 렌더 0x1EF620 의 0x1EFA14: 카메라 +0x910 ≥ 0 이면 그 번호 템플릿, 아니면 현재 카메라 상태 (+0x180) 의 색
 *       (+0xA0..+0xE0, 카메라 활성화 0x1F3A38 이 CAM 0xB0 번 템플릿에서 복사 — 보간 없음) 을 0x162008 로 필터 노드 (0x29CF6C) 에 넣는다.
 *       블렌드 = ALPHA 표 0x29F140 [Blend Mode].</li>
 *   <li>그리기 0x121128 (노드 vtable 0x2BE240 +0x14): 세 색이 같으면 화면 전체 스프라이트 (0x120800), 아니면 0x120D80 → 0x1208C8 의
 *       세로 그라데이션. GS: TEST ZTE=1 ZTST=ALWAYS, ALPHA = 표 값, RGBAQ = (R·255, G·255, B·255, A·128) (A 128 = 1.0).</li>
 *   <li>그라데이션 좌표 u (0x1208C8): u = 광선이 <b>월드 위쪽과 이루는 각 / π</b> (0 = 바로 위, 0.5 = 수평, 1 = 바로 아래).
 *       화면 위 끝 u = 1 − (acos(카메라 행렬 3행 y) + 반세로화각)/π, 아래 끝 = 1 − (acos(..) − 반세로화각)/π (acos = 0x183F98,
 *       반세로화각 = 카메라 +0xF8 / 화면비 0x330A30 · 0.5), 화면 세로로 선형. Toppoint 위는 위 색, BottomPoint 아래는 아래 색,
 *       사이는 선형 보간 (32 화소 열 Gouraud 띠).</li>
 * </ul>
 */
public record CameraFilter(String name, float[][] colors, float[] points, int blendMode) {

	/** Blend Mode → GS ALPHA (0x29F140): 0 블렌드 (Cs−Cd)·As+Cd, 1 가산 Cs·As+Cd, 2 감산 Cd−Cs·As. */
	public static final int BLEND = 0, ADD = 1, SUB = 2;
	public static final int[] GS_ALPHA = {0x44, 0x48, 0x42};

	/** RGBA 필드 해시 (+0x20 위 / +0x30 가운데 / +0x40 아래). */
	static final int[] COLOR_FIELDS = {0x0014E3D5, 0xB7EB5695, 0x7947B8AB};

	/** Hash127 (0x1758A0): h = h·127 + c (부호 있는 바이트). */
	static int hash127(String s, int seed) {
		int h = seed;
		for (byte b : s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)) {
			h = h * 127 + b;
		}
		return h;
	}

	/** RGBA 필드의 성분 키 (0x1823F8: Hash127(":" + 성분, 필드 해시)). */
	static int componentKey(int field, char c) {
		return hash127(":" + c, field);
	}

	/** 템플릿 번호 n 을 읽는다 (없으면 null). */
	public static CameraFilter load(Tweaks tw, int n) {
		if (tw == null || n < 0) {
			return null;
		}
		String node = String.format("/TweakTemplates/CameraFilter/%03d/", n);
		if (tw.raw(node, "Toppoint").isEmpty() && tw.rawHash(node, componentKey(COLOR_FIELDS[0], 'R')).isEmpty()) {
			return null;
		}
		float[][] c = new float[3][4];
		String comps = "RGBA";
		for (int i = 0; i < 3; i++) {
			for (int k = 0; k < 4; k++) {
				var r = tw.rawHash(node, componentKey(COLOR_FIELDS[i], comps.charAt(k)));
				c[i][k] = r.isPresent() ? Float.intBitsToFloat(r.getAsInt()) : 0;
			}
		}
		float[] p = {tw.f(node, "Toppoint", 0), tw.f(node, "Midpoint", 0.5f), tw.f(node, "BottomPoint", 1)};
		int mode = tw.i(node, "Blend Mode", 0) & 0xFF; // 열거: 하위 바이트만 유효 (1.0 = 0x3F800000 → 0, 1.0000001 → 1)
		return new CameraFilter(tw.s(node, "Instance Name", node), c, p, Math.min(mode, 2));
	}

	/** 세 색이 같은지 (0x121170: 같으면 단색 스프라이트 0x120800). */
	public boolean uniform() {
		return java.util.Arrays.equals(colors[0], colors[1]) && java.util.Arrays.equals(colors[0], colors[2]);
	}

	/** 화면에 변화가 있는지 (블렌드·가산·감산 모두 As·Cs 항이 0 이면 그대로). */
	public boolean visible() {
		for (float[] c : colors) {
			if (c[3] > 0 && (blendMode == BLEND || c[0] > 0 || c[1] > 0 || c[2] > 0)) {
				return true;
			}
		}
		return false;
	}

	/** u 에서의 색 (Toppoint 위 = 위 색, 사이 선형, BottomPoint 아래 = 아래 색). */
	public float[] colorAt(float u) {
		float[] out = new float[4];
		float[] p = points;
		if (u <= p[0]) {
			System.arraycopy(colors[0], 0, out, 0, 4);
		} else if (u < p[1]) {
			lerp(colors[0], colors[1], (u - p[0]) / Math.max(1e-6f, p[1] - p[0]), out);
		} else if (u < p[2]) {
			lerp(colors[1], colors[2], (u - p[1]) / Math.max(1e-6f, p[2] - p[1]), out);
		} else {
			System.arraycopy(colors[2], 0, out, 0, 4);
		}
		return out;
	}

	private static void lerp(float[] a, float[] b, float t, float[] out) {
		for (int i = 0; i < 4; i++) {
			out[i] = a[i] + (b[i] - a[i]) * t;
		}
	}

	/**
	 * 화면 위·아래 끝의 u (0x1208C8).
	 *
	 * @param forwardY 카메라 정면 단위벡터의 y (원작은 행렬 3행 = 뒤쪽 벡터의 y 를 acos)
	 * @param vFovRad  세로 화각 (라디안)
	 * @return {u 위, u 아래}
	 */
	public static float[] screenRange(float forwardY, float vFovRad) {
		float a = (float) Math.acos(Math.max(-1, Math.min(1, -forwardY))); // 뒤쪽 벡터와 위쪽의 각
		float h = vFovRad * 0.5f;
		return new float[]{1 - (a + h) / (float) Math.PI, 1 - (a - h) / (float) Math.PI};
	}

	/**
	 * 이번 프레임에 쓸 템플릿 번호 (0x1EFA14): 대체 번호 (+0x910) ≥ 0 이면 그것, 아니면 현재 카메라의 CAM 0xB0 번.
	 */
	public static int select(int override, int cameraFilter) {
		return override >= 0 ? override : cameraFilter;
	}
}
