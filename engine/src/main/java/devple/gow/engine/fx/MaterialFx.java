package devple.gow.engine.fx;

import devple.gow.engine.asset.RenderModel;
import devple.gow.engine.render.Texture;
import devple.gow.tools.fx.MaterialAnim;

import java.util.List;

/**
 * 머티리얼 부가 효과 (16 문서 §3, 31 문서 §3): 플립북 프레임, UV 스크롤, 불투명도 트랙, 2·3번째 레이어.
 * {@link RenderModel.Part#fx()} 가 null 이면 기존(첫 레이어만) 동작.
 *
 * @param frames 첫 레이어 GFX 의 프레임별 텍스처 (frames &gt; 1 일 때, 아니면 null)
 * @param anim   머티리얼 ANM (없으면 null): 프레임 = anim.frame(t), UV = anim.uv(L, t), 불투명도 = hdr8 (p28)
 * @param layers 추가 레이어 (같은 정점/UV 로 덧그림, 레이어 순서)
 * @param env0   첫 레이어가 환경맵 (레이어 rf bit 1 = VU 플래그 2, 법선 있는 메시) — 메시 UV 대신 법선 구면 맵 UV ({@link #ENV_FLAG})
 * @param header MAT 헤더 색 (파일 0x08, rgb) — 색 트랙 "hdr0..2" 의 기본값. null = 색 트랙 없음
 * @param layerRgba 레이어마다 MAT 레이어 rgba (파일 레이어 +0x28) — 색 트랙 "L{n}s0..3" 의 기본값
 */
public record MaterialFx(Texture[] frames, MaterialAnim.Anim anim, List<Layer> layers, boolean env0, float[] header, float[][] layerRgba) {

	public MaterialFx(Texture[] frames, MaterialAnim.Anim anim, List<Layer> layers, boolean env0) {
		this(frames, anim, layers, env0, null, null);
	}

	/**
	 * 머티리얼 ANM 색 트랙 (타입 3 — p1 = 0: 헤더 0x08 의 스트림 0–2 rgb, p1 = L + 1: 레이어 L 0x28 의 스트림 0–3 rgba, 16 §3.1) 을 반영한 레이어 색 배율
	 * [확실: 트랙 대상 / 근사: 조명 없는 경로 환산 = 빌드 때와 같은 header·layer/0.8]. 트랙이 없거나 t &lt; 0 (스크립트 0x42 재질을 아직 틀지 않음) 이면 base 그대로.
	 * 예 PAND00C 해 문 글자 MAT_M01–M14sfxLightRayCore: 레이어 rgba 0 (가산 = 안 보임) 에 트랙 1 → 0 (10 초) — 스크립트가 틀면 밝게 빛나다 꺼진다
	 * (영상 3634–3646, 43 §33). 예전 엔진은 색 트랙을 버려 글자가 끝내 빛나지 않았다.
	 *
	 * @param layer        레이어 번호 (0 = 첫 레이어)
	 * @param base         빌드 때 색 배율 (rgba)
	 * @param headerScaled 빌드 때 rgb = 헤더 × 레이어 / 0.8 이었는가 (텍스처 없는 레이어, {@code WadAssets.layerTint}) — 아니면 레이어 rgb 그대로
	 */
	public float[] tint(int layer, float t, float[] base, boolean headerScaled) {
		return colorTint(anim, header, layerRgba, layer, t, base, headerScaled);
	}

	/**
	 * 동적 라이트 경로의 머티리얼 색 (qw 76 = 헤더 rgb × 레이어 rgb, {@code WadAssets.litColor}) 에 같은 색 트랙을 건 것 — [3..5] 더할 색은 그대로.
	 * 트랙이 없거나 t &lt; 0 이면 base 그대로.
	 */
	public float[] litColor(int layer, float t, float[] base) {
		if (anim == null || t < 0 || header == null || layerRgba == null || layer < 0 || layer >= layerRgba.length || anim.floats().isEmpty()) {
			return base;
		}
		String lk = "L" + layer + "s";
		float[] out = null;
		for (int k = 0; k < 3; k++) {
			if (!anim.floats().containsKey(lk + k) && !anim.floats().containsKey("hdr" + k)) {
				continue;
			}
			if (out == null) {
				out = base.clone();
			}
			out[k] = anim.value("hdr" + k, t, header[k]) * anim.value(lk + k, t, layerRgba[layer][k]);
		}
		return out != null ? out : base;
	}

	/** {@link #tint} 의 규칙 (자세 GO {@code AnimatedModel} 처럼 MaterialFx 밖에서 ANM 을 쥔 경우). */
	public static float[] colorTint(MaterialAnim.Anim anim, float[] header, float[][] layerRgba, int layer, float t, float[] base, boolean headerScaled) {
		if (anim == null || t < 0 || header == null || layerRgba == null || layer < 0 || layer >= layerRgba.length || anim.floats().isEmpty()) {
			return base;
		}
		String lk = "L" + layer + "s";
		float[] out = null;
		for (int k = 0; k < 3; k++) {
			boolean lt = anim.floats().containsKey(lk + k), ht = headerScaled && anim.floats().containsKey("hdr" + k);
			if (!lt && !ht) {
				continue;
			}
			if (out == null) {
				out = base.clone();
			}
			float l = anim.value(lk + k, t, layerRgba[layer][k]);
			out[k] = headerScaled ? anim.value("hdr" + k, t, header[k]) * l / 0.8f : l;
		}
		if (anim.floats().containsKey(lk + 3)) {
			if (out == null) {
				out = base.clone();
			}
			float b = layerRgba[layer][3], a = anim.value(lk + 3, t, b);
			out[3] = b != 0 ? base[3] * a / b : a;
		}
		return out != null ? out : base;
	}

	/**
	 * 레이어 렌더 플래그 bit 1 (0x02) = 메시 VU1 환경맵 경로: 마이크로코드 (0x2ABB00 오버레이) 0x0D9–0x0E3 이 VU 플래그 2 (vi10 bit 9) 일 때 법선을
	 * 모델·뷰 행렬 행 (배율 제거) 으로 옮겨 UV = 0.5 − 0.5·n.xy 로 쓴다 (ST 출력 vf16–19) [확실: 마이크로코드 구조 / 근사: 축 부호].
	 * 레이어마다 그 레이어 플래그로 같은 VU 프로그램을 돌린다 (16 §3.3 "모든 레이어가 같은 정점·UV 로 추가 패스"). bit 2 (0x04) 는 머티리얼 색 (qw76) 이라 환경맵이 아니다.
	 */
	public static final int ENV_FLAG = 0x02;

	/**
	 * 추가 레이어.
	 *
	 * @param index   레이어 번호 (1, 2)
	 * @param texture 텍스처 (null = 흰색)
	 * @param frames  레이어 GFX 프레임 (null = 없음)
	 * @param blend   GS ALPHA 바이트 → 블렌드 (0x01 = INV)
	 * @param tint    색 배율 (a = p38 등 반영)
	 * @param env     법선이 있는 메시에서 구면 환경맵 UV 사용 (레이어 rf &amp; {@link #ENV_FLAG})
	 * @param litColor 동적 라이트 경로의 머티리얼 색 (MAT 헤더 색 × 레이어 rgb, 47 §3.1)
	 */
	public record Layer(int index, Texture texture, Texture[] frames, RenderModel.Blend blend, float[] tint, boolean env, float[] litColor) {

		public Layer(int index, Texture texture, Texture[] frames, RenderModel.Blend blend, float[] tint, boolean env) {
			this(index, texture, frames, blend, tint, env, new float[]{tint[0], tint[1], tint[2], 0, 0, 0});
		}
	}

	/** 시간 t(초)의 첫 레이어 텍스처. */
	public Texture frame(Texture base, float t) {
		return pick(frames, base, t);
	}

	public Texture frame(Layer l, float t) {
		return pick(l.frames(), l.texture(), t);
	}

	private Texture pick(Texture[] fr, Texture base, float t) {
		if (fr == null || fr.length <= 1) {
			return base;
		}
		int f = anim != null ? anim.frame(Math.max(t, 0f)) : 0;
		return fr[Math.floorMod(f, fr.length)];
	}

	/** 레이어 L 의 UV 오프셋 (없으면 null). */
	public float[] uv(int layer, float t) {
		return anim == null ? null : anim.uv(layer, Math.max(t, 0f));
	}

	/** 머티리얼 불투명도 배율 (ANM 헤더 스트림 8 = p28, 없으면 1). */
	public float opacity(float t, float base) {
		return anim == null ? base : anim.value("hdr8", Math.max(t, 0f), base);
	}

	/**
	 * 스크립트 재질 시계 값 (nav.Platforms.MatClock) 이 "아직 틀지 않은 0x42 재질" 이면 NaN — 그 재질은 색 트랙을 걸지 않고 (MAT 기본 색) 프레임·UV·불투명도는
	 * 시간 0 으로 [근사: 원작 0x42 액트는 스크립트가 틀기 전 재질 값을 쓰지 않는다 — 해 문 글자 (기본 레이어 rgba 0 · 트랙 1 → 0) 가 영상 3610–3626 에서
	 * 어둡다. 불투명도는 예전처럼 시간 0 (ATHN02D 문 막 MAT_doorSub 기본 p28 = 트랙 첫 값 0.5, 감산 레이어는 빌드 때 p28 을 곱하지 않으므로)].
	 * @return {프레임·UV·불투명도 시간, 색 트랙 시간 (−1 = 걸지 않음)}
	 */
	public static float[] times(float t) {
		return Float.isNaN(t) ? new float[]{0f, -1f} : new float[]{t, t};
	}
}
