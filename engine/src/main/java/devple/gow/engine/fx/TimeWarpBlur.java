package devple.gow.engine.fx;

import devple.gow.engine.game.Tweaks;

/**
 * 시간 왜곡 흐림 (TimeWarpBlur, 43 §47.2) — 사망 화면의 붉은 소용돌이 "DeathFade" (영상 4화 1129–1130 · 1138–1139, 1화 7049 · 8209, 3화 6380).
 * <p>
 * 원작 [확실: 디스어셈블]: 사망 0x1D6CF0 → 0x21D770 (상태 0xE) → 0x220830 이 TweakTemplate "TimeWarpBlur" (0x1F5000 이 만듦, 0x337B84) 에서 번호 999
 * (TWK_TimeWarpBlur_999 "DeathFade", R_PERMK) 를 찾아 (0x182D50) 0x142DE0 으로 0x60 바이트를 0x2CD380 에 옮기고 상태 0x29CCA0 = 2, 경과 0x29CC88 = 0,
 * 남은 시간 0x29CCA4 = 시간 0 + 1 + 2. 매 프레임 0x143048 (그리기 0x140988 안, 게임 상태와 무관하게 1/60 초씩) 이 0x142E40 으로
 * 키 0→1 (시간 0), 1→2 (시간 1 "Hold Time"), 2→3 (시간 2) 을 선형 보간해 색 (R·G·B × 255, A × 128 → 0x29CC8C–0x29CC98) 과
 * 확대 (정수, 1/16 화소 → 0x29CC9C) 를 정하고, 0x142A28 이 앞 프레임 버퍼를 텍스처로 (TEX1 선형) 확대한 띠 스프라이트를 그린다:
 * RGBAQ = (R, G, B, 0x80) MODULATE, ALPHA_2 = 0x21 (A = Cd, B = Cs, C = FIX, D = Cs) FIX = A × 128 → 결과 = Cs + (Cd − Cs) · FIX / 128,
 * 즉 지금 장면 (Cd) 과 확대된 앞 화면 × 색 (Cs) 을 A 로 섞는다. UV 는 가장자리에서 확대 값만큼 (1/16 화소) 안으로.
 * DeathFade: 키 색 (0.52, 0.4, 0.3, A) — A = 1 → 0.1 → 0 → 키 3 (0, 0, 0, 1); 시간 1.0 · 1.5 · 1.0; 확대 128 · 128 · 256 · 64.
 * 색이 R 1.04 · G 0.80 · B 0.60 배 (MODULATE 0x80 = 1) 라 되먹임마다 초록 · 파랑이 줄어 붉은 소용돌이가 된다.
 * 성분 키 = Hash127(":" + R/G/B/A, 필드 해시) (0x1823F8, {@link CameraFilter#componentKey}), 필드 0x153DA042 · 0x3DBFF41C · 0x3DBFF41D · 0x0011323B.
 */
public final class TimeWarpBlur {

	/** 키 색 필드 해시 (0x1F5040 – 0x1F510C 등록 순서 = 런타임 +0x00 · +0x10 · +0x20 · +0x30). */
	static final int[] COLOR_FIELDS = {0x153DA042, 0x3DBFF41C, 0x3DBFF41D, 0x0011323B};
	/** 시간 필드 (+0x40 · +0x44 "Hold Time" · +0x48) 와 확대 필드 (+0x4C "Zoom Start" · +0x50 · +0x54 · +0x58 "Zoom End") 해시. */
	static final int[] TIME_FIELDS = {0x74623D84, 0x8777A60E, 0x34C726E3};
	static final int[] ZOOM_FIELDS = {0xF93F06B5, 0x79F7A78F, 0x79F7A790};

	/** 사망 흐림 템플릿 번호 (0x220830: a1 = 0x3E7). */
	public static final int DEATH_FADE = 999;

	final float[][] colors;
	final float[] times;
	final int[] zooms;
	private float elapsed;

	TimeWarpBlur(float[][] colors, float[] times, int[] zooms) {
		this.colors = colors;
		this.times = times;
		this.zooms = zooms;
	}

	/** 템플릿 n (없으면 null). */
	public static TimeWarpBlur load(Tweaks tw, int n) {
		if (tw == null) {
			return null;
		}
		String node = String.format("/TweakTemplates/TimeWarpBlur/%03d/", n);
		if (tw.rawHash(node, CameraFilter.componentKey(COLOR_FIELDS[0], 'A')).isEmpty()) {
			return null;
		}
		float[][] c = new float[4][4];
		for (int i = 0; i < 4; i++) {
			for (int k = 0; k < 4; k++) {
				var r = tw.rawHash(node, CameraFilter.componentKey(COLOR_FIELDS[i], "RGBA".charAt(k)));
				c[i][k] = r.isPresent() ? Float.intBitsToFloat(r.getAsInt()) : 0;
			}
		}
		float[] t = new float[3];
		for (int i = 0; i < 3; i++) {
			var r = tw.rawHash(node, TIME_FIELDS[i]);
			t[i] = r.isPresent() ? Float.intBitsToFloat(r.getAsInt()) : 0;
		}
		int[] z = new int[4];
		int zEnd = devple.gow.tools.tweak.TweakFile.hash("Zoom End");
		for (int i = 0; i < 4; i++) {
			var r = tw.rawHash(node, i < 3 ? ZOOM_FIELDS[i] : zEnd);
			z[i] = r.isPresent() ? r.getAsInt() : 0;
		}
		return new TimeWarpBlur(c, t, z);
	}

	/** 전체 길이 (0x142DE0: 시간 0 + 1 + 2). */
	public float duration() {
		return times[0] + times[1] + times[2];
	}

	public boolean active() {
		return elapsed < duration();
	}

	/** 한 프레임 (원작은 게임 상태와 무관하게 그리기 프레임마다 1/60 초). */
	public void advance(float dt) {
		elapsed += dt;
	}

	public float elapsed() {
		return elapsed;
	}

	/**
	 * 지금 값 (0x142E40): {R, G, B, A, 확대 (1/16 화소)}. 구간 길이가 0 이면 그 구간을 건너뛰고, 끝나면 키 3.
	 */
	public float[] sample() {
		return sample(elapsed);
	}

	float[] sample(float e) {
		float t = e;
		for (int i = 0; i < 3; i++) {
			if (times[i] > 0 && t < times[i]) {
				float f = t / times[i];
				float[] a = colors[i], b = colors[i + 1];
				return new float[]{a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f, a[3] + (b[3] - a[3]) * f,
						zooms[i] + (zooms[i + 1] - zooms[i]) * f};
			}
			t -= times[i];
		}
		float[] k = colors[3];
		return new float[]{k[0], k[1], k[2], k[3], zooms[3]};
	}
}
