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
 */
public record MaterialFx(Texture[] frames, MaterialAnim.Anim anim, List<Layer> layers, boolean env0) {

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
