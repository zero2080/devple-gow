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
 */
public record MaterialFx(Texture[] frames, MaterialAnim.Anim anim, List<Layer> layers) {

	/**
	 * 추가 레이어.
	 *
	 * @param index   레이어 번호 (1, 2)
	 * @param texture 텍스처 (null = 흰색)
	 * @param frames  레이어 GFX 프레임 (null = 없음)
	 * @param blend   GS ALPHA 바이트 → 블렌드 (0x01 = INV)
	 * @param tint    색 배율 (a = p38 등 반영)
	 * @param env     법선이 있는 메시에서 구면 환경맵 UV 사용 [추정]
	 */
	public record Layer(int index, Texture texture, Texture[] frames, RenderModel.Blend blend, float[] tint, boolean env) {
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
		int f = anim != null ? anim.frame(t) : 0;
		return fr[Math.floorMod(f, fr.length)];
	}

	/** 레이어 L 의 UV 오프셋 (없으면 null). */
	public float[] uv(int layer, float t) {
		return anim == null ? null : anim.uv(layer, t);
	}

	/** 머티리얼 불투명도 배율 (ANM 헤더 스트림 8 = p28, 없으면 1). */
	public float opacity(float t, float base) {
		return anim == null ? base : anim.value("hdr8", t, base);
	}
}
