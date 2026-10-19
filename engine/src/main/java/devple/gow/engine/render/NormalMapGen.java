package devple.gow.engine.render;

/**
 * 색 텍스처 → 탄젠트 공간 노멀맵 (CPU, 44 문서). 원작에 없는 선택 기능.
 * <pre>
 * 높이 h = 휘도 (0.299 R + 0.587 G + 0.114 B) / 255, 3×3 Sobel 로 ∂h/∂s, ∂h/∂t (텍셀 단위) →
 * n = normalize(−strength·∂h/∂s, −strength·∂h/∂t, 1).
 * 축: +X = 텍스처 열 증가 (+u), +Y = 행 증가 (+v, GL 업로드 행 0 = t 0 이라 이미지 아래쪽) — "DirectX(Y−)" 규약과 같다.
 * 가장자리: wrap = 반복 주소 (GL_REPEAT) 면 감싸고, 아니면 가장자리 텍셀 복제 (clamp).
 * 알파: alpha &lt; {@link #ALPHA_CUT} 인 텍셀은 잘린 픽셀 → 자기 법선은 평평 (0,0,1), 이웃 계산에선 중심 텍셀 높이로 대체
 *       (잘린 테두리가 가짜 경사를 만들지 않음).
 * </pre>
 * 출력은 ARGB (A = 255, R/G/B = n·0.5 + 0.5 를 0..255 로).
 */
public final class NormalMapGen {

	/** 이 알파 미만 텍셀은 잘린 것으로 본다 (CUTOUT 알파 테스트 0.75 보다 낮게 — 반투명 가장자리는 살림). */
	public static final int ALPHA_CUT = 64;

	private NormalMapGen() {
	}

	/** 평평한 법선 (0,0,1) 의 ARGB. */
	public static final int FLAT = 0xFF8080FF;

	/**
	 * @param argb     w×h ARGB (행 우선, 행 0 = 위)
	 * @param wrap     true = 반복 주소 (감싸기), false = clamp
	 * @param strength 경사 배율 (높이 0..1 기준)
	 */
	public static int[] generate(int[] argb, int w, int h, boolean wrap, float strength) {
		float[] height = new float[w * h];
		boolean[] solid = new boolean[w * h];
		for (int i = 0; i < w * h; i++) {
			int c = argb[i];
			solid[i] = (c >>> 24) >= ALPHA_CUT;
			height[i] = (0.299f * ((c >> 16) & 255) + 0.587f * ((c >> 8) & 255) + 0.114f * (c & 255)) / 255f;
		}
		int[] out = new int[w * h];
		float[] k = new float[9];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int ci = y * w + x;
				if (!solid[ci]) {
					out[ci] = FLAT;
					continue;
				}
				float hc = height[ci];
				for (int dy = -1; dy <= 1; dy++) {
					int yy = coord(y + dy, h, wrap);
					for (int dx = -1; dx <= 1; dx++) {
						int xx = coord(x + dx, w, wrap);
						int ni = yy * w + xx;
						k[(dy + 1) * 3 + dx + 1] = solid[ni] ? height[ni] : hc;
					}
				}
				// Sobel (정규화 1/8 → 텍셀당 높이 차)
				float gx = ((k[2] + 2 * k[5] + k[8]) - (k[0] + 2 * k[3] + k[6])) / 8f;
				float gy = ((k[6] + 2 * k[7] + k[8]) - (k[0] + 2 * k[1] + k[2])) / 8f;
				out[ci] = pack(-strength * gx, -strength * gy, 1f);
			}
		}
		return out;
	}

	private static int coord(int v, int n, boolean wrap) {
		if (wrap) {
			return Math.floorMod(v, n);
		}
		return Math.clamp(v, 0, n - 1);
	}

	/** 정규화해 ARGB 로. */
	static int pack(float x, float y, float z) {
		float l = (float) Math.sqrt(x * x + y * y + z * z);
		x /= l;
		y /= l;
		z /= l;
		int r = Math.round((x * 0.5f + 0.5f) * 255f);
		int g = Math.round((y * 0.5f + 0.5f) * 255f);
		int b = Math.round((z * 0.5f + 0.5f) * 255f);
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	/** ARGB → 법선 (테스트·디버그용). */
	public static float[] unpack(int argb) {
		return new float[]{((argb >> 16) & 255) / 255f * 2 - 1, ((argb >> 8) & 255) / 255f * 2 - 1, (argb & 255) / 255f * 2 - 1};
	}
}
