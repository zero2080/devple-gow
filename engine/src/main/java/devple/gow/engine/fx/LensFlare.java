package devple.gow.engine.fx;

import devple.gow.tools.fx.EnvParams;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * SCR_LensFlare (등록 0x21D328, 생성 0x2469B0 → 0x20E728, 렌더 0x20E990) 의 GL 무관 계산 (46 문서).
 * <ul>
 *   <li>값 (인스턴스 32 바이트): +0 f32 스프라이트 크기 (화면 화소, 전체 폭) → 객체 +0x24, +4 f32 가림 판정 상자 (화소) → +0x28,
 *       +8 char[24] 머티리얼 이름 ({@code "MAT_%s"} 0x2B93B8 로 조회, 0x17A7B8) [확실: 0x20E728].</li>
 *   <li>위치 = 소유 GO 의 관절 월드 위치 (0x20E948: 스켈레톤이 있으면 엔티티 +0x46 관절, 없으면 GO +0x50) [확실].</li>
 *   <li>보기 공간 깊이가 렌더 문맥 +0x384..+0x388 (근/원 평면) 밖이면 그리지 않는다 (0x20E9D8) [확실].</li>
 *   <li>스프라이트: 투영점 중심, 반폭 = int(크기·0.5) 화소, 텍스처 전체 (UV 0..TW/TH). 사각형이 화면 밖이면 생략 (0x20EB4C..0x20EB88) [확실].</li>
 *   <li>가림: 반폭 = int(상자·0.5) 화소 상자 (화면에 자름) 를 알파 0 으로 지우고, 빛의 Z 로 깊이 시험 (GS ZTST GEQUAL) 한 흰 사각형을
 *       알파 0xC0·(자른 상자의 짧은 변 / 전체 변) 으로 그린 뒤, 64→32→…→1 화소로 쌍선형 축소 (0x20EF94..0x20F5A8) → 평균 알파
 *       = 1.5 · 보이는 비율 · 자름 비율. 이 알파를 플레어 텍스처 RGB 와 합쳐 화면에 Cs·As + Cd (ALPHA 0x48) 로 더한다 [확실: GS 패킷,
 *       추정: 축소 단계의 정확한 표본 위치].</li>
 *   <li>그와 별도로 첫 패스 (0x20EC00): 같은 스프라이트를 RGBAQ 0x40 (= ½) 변조, ALPHA 고정 가산 (A=Cs, C=FIX 0x80, D=Cd), 빛의 Z 로
 *       화소 단위 깊이 시험 → 가려진 화소는 빠진 반 밝기 플레어 [추정: 첫 패스가 화면 버퍼에 남는다 — FRAME_1 = 화면, FBMSK 0].</li>
 * </ul>
 */
public final class LensFlare {

	private LensFlare() {
	}

	/** 원작 화면 (GS 프레임) 높이 [추정: 640×448 NTSC]. 엔진 창 높이로 비례 환산한다. */
	public static final float PS2_HEIGHT = 448f;
	/** 가림 사각형 알파 0xC0 / GS 1.0 (0x80). */
	public static final float OCCLUSION_ALPHA = 0xC0 / 128f;
	/** 첫 패스 RGBAQ 0x40 / 0x80. */
	public static final float DIRECT_SCALE = 0x40 / 128f;

	/** SCR_LensFlare 한 개. owner = 소유 GO 인스턴스 이름. */
	public record Def(String owner, float size, float occlusion, String material) {
	}

	/** 환경 스크립트 값에서 (인스턴스 값만). */
	public static List<Def> defs(EnvParams env) {
		List<Def> out = new ArrayList<>();
		for (EnvParams.Raw r : env.others) {
			if (r.target().equals("SCR_LensFlare") && r.script().instance()) {
				Def d = parse(r.owner(), r.script().values());
				if (d != null) {
					out.add(d);
				}
			}
		}
		return out;
	}

	static Def parse(String owner, ByteBuffer v) {
		if (v.limit() < 8) {
			return null;
		}
		int len = 0;
		while (8 + len < v.limit() && len < 24 && v.get(8 + len) != 0) {
			len++;
		}
		byte[] b = new byte[len];
		v.get(8, b);
		return new Def(owner, v.getFloat(0), v.getFloat(4), "MAT_" + new String(b, StandardCharsets.ISO_8859_1));
	}

	/**
	 * 화면에 자른 상자 (원작 0x20EA88..0x20EB48: 화면 [0, w−1] × [0, h−1] 로 자름).
	 *
	 * @return {x0, y0, x1, y1} (자른 결과, 비면 x1 ≤ x0)
	 */
	public static float[] clip(float cx, float cy, float half, float w, float h) {
		return new float[]{Math.max(0, cx - half), Math.max(0, cy - half), Math.min(w - 1, cx + half), Math.min(h - 1, cy + half)};
	}

	/** 스프라이트가 화면에 조금이라도 걸치는지 (0x20EB4C..0x20EB88). */
	public static boolean onScreen(float cx, float cy, float half, float w, float h) {
		return cx + half >= 0 && cx - half <= w - 1 && cy + half >= 0 && cy - half <= h - 1;
	}

	/** 자름 비율 = 자른 상자의 짧은 변 / 전체 변 (0x20ED5C: min(Δx, Δy), 0x20ED8C: 0xC0·n / (반폭·32)). */
	public static float clipRatio(float[] box, float half) {
		if (half <= 0) {
			return 0;
		}
		float e = Math.min(box[2] - box[0], box[3] - box[1]);
		return Math.max(0, Math.min(1, e / (2 * half)));
	}

	/** 보이는 비율: 장면 깊이 ≥ 빛 깊이 (GL 0..1, 작을수록 가까움) 인 표본 비율. GS ZTST GEQUAL 과 같은 판정. */
	public static float visibleFraction(float[] sceneDepth, int n, float lightDepth) {
		if (n <= 0) {
			return 0;
		}
		int vis = 0;
		for (int i = 0; i < n; i++) {
			if (lightDepth <= sceneDepth[i] + 1e-6f) {
				vis++;
			}
		}
		return vis / (float) n;
	}

	/** 마지막 패스의 As (축소한 평균 알파): 1.5 · 보이는 비율 · 자름 비율, 8비트 알파 상한 255/128. */
	public static float intensity(float visible, float clipRatio) {
		return Math.min(255f / 128f, OCCLUSION_ALPHA * visible * clipRatio);
	}
}
