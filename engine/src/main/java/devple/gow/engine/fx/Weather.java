package devple.gow.engine.fx;

import devple.gow.engine.asset.RenderModel;
import devple.gow.engine.asset.WadAssets;
import devple.gow.engine.render.Texture;
import devple.gow.tools.fx.EnvParams;

/**
 * SCR_Weather → 카메라 상자 이미터 (31 문서 §4.2). 종류별 기본 모양은 원작 표 0x29F388 의 오브젝트(gorain …)를 따른다.
 */
public final class Weather {

	private Weather() {
	}

	/** 스크립트 값의 거리 단위 ×16 (SCR_FarClip 과 같음). 상자 = box ×16 ×0.5 (생성자 0x21CBE0). */
	public static ParticleDef def(EnvParams.Weather w, WadAssets assets) {
		// 원작: 종류별 셀 크기(표 0x29F388 +0x10, 비 20) ×16 정육면체를 카메라 앞 (셀/2 − near) 에 두고 감싼다 (0x21CF80)
		float cell = (w.type() == 2 ? 14 : w.type() == 5 ? 10 : 20) * 16 / 2f;
		float[] box = {cell, cell, cell};
		Texture tex = texture(assets, "MAT_nalRain_M01sfxRain3");
		return switch (w.type()) {
			case 1 -> new ParticleDef("snow", 600, 6, 0.3f, box, new float[]{w.vec()[0], -60, w.vec()[2]}, new float[]{20, 10, 20},
					new float[3], 0, 3, 3, new float[]{1, 1, 1, 0.8f}, new float[]{1, 1, 1, 0.8f}, 0.3f, 0, 1, null, 1, RenderModel.Blend.ALPHA);
			case 4 -> new ParticleDef("embers", 200, 4, 0.4f, box, new float[]{0, 40, 0}, new float[]{20, 20, 20},
					new float[3], 0.2f, 3, 1, new float[]{1, 0.6f, 0.2f, 1}, new float[]{1, 0.2f, 0, 0}, 0.2f, 0, 0, null, 1, RenderModel.Blend.ADD);
			default -> rain(w, box, tex);
		};
	}

	/**
	 * 비: 줄기 방향 = normalize(vec + (0,−10,0)) (0x21AFD8) 중 수평 성분만 바람으로 쓰고 낙하 속도는 고정 [추정].
	 * 수 = PTC_rain +0x6C (1000) [추정], 가산 블렌드 MAT_nalRain (64×64 가운데 줄기 하나 → 사각형이 줄기보다 넓다, 색 = 레이어 0.42×2 MODULATE 에 맞춘 밝기).
	 */
	static ParticleDef rain(EnvParams.Weather w, float[] box, Texture tex) {
		float fall = 900f;
		float[] v = {w.vec()[0] * 16, -fall, w.vec()[2] * 16};
		float life = 0.6f;
		return new ParticleDef("rain", 1000 / life, life, 0.2f, box, v, new float[]{8, 60, 8},
				new float[3], 0, 6f, 6f, new float[]{1.7f, 1.7f, 1.8f, 1f}, new float[]{1.7f, 1.7f, 1.8f, 1f},
				0.05f, 48f, 0, tex, 1, RenderModel.Blend.ADD);
	}

	static Texture texture(WadAssets assets, String mat) {
		String t = assets.materialTexture(mat);
		return t == null ? null : assets.texture(t);
	}
}
