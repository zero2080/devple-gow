package devple.gow.engine.fx;

import devple.gow.engine.asset.RenderModel;
import devple.gow.engine.render.Texture;
import devple.gow.tools.fx.FxResources;

/**
 * 이미터 한 종류의 파라미터 (31 문서 §4). PTC_/SEM_ 해석값이나 SCR_Weather 에서 만든다.
 *
 * @param name       디버그 이름
 * @param rate       초당 생성 수
 * @param life       수명 (초), lifeJitter = ± 비율
 * @param box        생성 상자 반폭 (x, y, z)
 * @param velocity   초기 속도 (월드/초), spread = ± 범위
 * @param accel      가속 (중력 등, 월드/초²)
 * @param drag       항력 (1/초)
 * @param size0      시작 크기 / size1 끝 크기 (월드)
 * @param color0     시작 RGBA / color1 끝 RGBA
 * @param fadeIn     페이드 인 시간 (초)
 * @param stretch    &gt; 0 이면 속도 방향 줄기 길이 (비), 0 = 빌보드
 * @param spin       회전 속도 (라디안/초)
 * @param texture    텍스처 (null = 원형 부드러운 점)
 * @param frames     세로 스트립 프레임 수 (수명에 따라 진행)
 * @param blend      ALPHA / ADD
 * @param cone       &gt;= 0 이면 FXC 원뿔 생성: 각 = rand·cone·π/2, 방향 = 이미터 dir (box/velocity 대신), −1 = 상자 모드
 * @param speed      원뿔 모드 속력 (월드/초), speedRnd = 폭: v = speed − r/2 + rand·r
 * @param fadeCurve  0 = 선형 color0→color1, 1 = 알파 4u(1−u), 2 = 알파 (A·u + B·u²)/128 (PTC VU 상수 추정)
 * @param alphaA     fadeCurve 2 계수 A, alphaB = B
 * @param ptc        원작 PTC_ (null 아니면 색·크기·위치·회전·블렌드를 VU 블록 식 {@link devple.gow.tools.fx.PtcVu} 로 계산, 16 문서 §2.4)
 */
public record ParticleDef(String name, float rate, float life, float lifeJitter, float[] box, float[] velocity, float[] spread,
						  float[] accel, float drag, float size0, float size1, float[] color0, float[] color1, float fadeIn,
						  float stretch, float spin, Texture texture, int frames, RenderModel.Blend blend,
						  float cone, float speed, float speedRnd, int fadeCurve, float alphaA, float alphaB, FxResources.Shape ptc) {

	/** 근사 정의 (VU 식 없음). */
	public ParticleDef(String name, float rate, float life, float lifeJitter, float[] box, float[] velocity, float[] spread,
					   float[] accel, float drag, float size0, float size1, float[] color0, float[] color1, float fadeIn,
					   float stretch, float spin, Texture texture, int frames, RenderModel.Blend blend,
					   float cone, float speed, float speedRnd, int fadeCurve, float alphaA, float alphaB) {
		this(name, rate, life, lifeJitter, box, velocity, spread, accel, drag, size0, size1, color0, color1, fadeIn, stretch, spin,
				texture, frames, blend, cone, speed, speedRnd, fadeCurve, alphaA, alphaB, null);
	}

	/** 같은 정의에 원작 PTC VU 식을 붙인다 (수명 흔들림 0: 원작 색 곡선이 수명에 맞춰져 있다). */
	public ParticleDef withPtc(FxResources.Shape shape) {
		return new ParticleDef(name, rate, life, 0, box, velocity, spread, accel, drag, size0, size1, color0, color1, fadeIn, stretch,
				spin, shape.textured() ? texture : null, frames, blend, cone, speed, speedRnd, fadeCurve, alphaA, alphaB, shape);
	}

	/** VU 식으로 그리는가. */
	public boolean vu() {
		return ptc != null && ptc.vu() != null && !VU_OFF;
	}

	/** 비교용: 환경 변수 GOW_PTCVU=off 면 이전 근사 렌더. */
	private static final boolean VU_OFF = "off".equals(System.getenv("GOW_PTCVU"));

	/** 상자 모드 (비/눈 등). */
	public ParticleDef(String name, float rate, float life, float lifeJitter, float[] box, float[] velocity, float[] spread,
					   float[] accel, float drag, float size0, float size1, float[] color0, float[] color1, float fadeIn,
					   float stretch, float spin, Texture texture, int frames, RenderModel.Blend blend) {
		this(name, rate, life, lifeJitter, box, velocity, spread, accel, drag, size0, size1, color0, color1, fadeIn, stretch, spin,
				texture, frames, blend, -1, 0, 0, 0, 0, 0, null);
	}
}
