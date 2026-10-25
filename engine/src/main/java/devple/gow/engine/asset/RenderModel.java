package devple.gow.engine.asset;

import devple.gow.engine.fx.MaterialFx;
import devple.gow.engine.render.Mesh;
import devple.gow.engine.render.Texture;

import java.util.List;

/**
 * GPU 에 올라간 모델. 원본 메시 오브젝트(서브메시)마다 머티리얼 첫 레이어의 텍스처/블렌드를 갖는다.
 *
 * @param min/max 바인드 포즈 AABB (카메라 프레이밍용)
 */
public record RenderModel(String name, List<Part> parts, float[] min, float[] max) implements AutoCloseable {

	public enum Blend {
		/**
		 * CUTOUT = GS 0x44 이지만 정렬 대상이 아닌 일반 머티리얼: 알파 ≥ 0.75 는 깊이 쓰기 불투명, 나머지는 반투명 가장자리 (TEST 0x5360B, 16 문서 §3.3).
		 * INV = GS 0x01 (Cd−Cs)·As+Cs (2·3번째 레이어).
		 */
		OPAQUE, ALPHA, ADD, SUB, CUTOUT, INV;

		/** MaterialResource.Layer#blendName (GS ALPHA 레지스터) → 블렌드. */
		public static Blend of(String gsBlend) {
			return switch (gsBlend) {
				case "add" -> ADD;
				case "sub" -> SUB;
				case "blend", "invblend" -> ALPHA;
				default -> OPAQUE;
			};
		}
	}

	/**
	 * 반투명 단계 안 그리기 순위 (작은 것부터) [확실: 키 배정 0x155C40 → 0x155B50 — 인자 a3 비트가 선 머티리얼은 구간 위 (키 큰 쪽) 로, t0 비트는 아래로]:
	 * 층마다 0x8005≪25 (뺄셈 A=2 · 가산 B=2 · flags2 0x100) 로 반투명 단계를 가른 뒤 그 안을 0x0A000400 → 0x0A000000/0x400 → 0x08000000 (B=2, 0x48 가산) /
	 * 0x02000000 (A=2, 0x42 뺄셈) 로 나눠, 키 순서가 알파 블렌드 (0x44 · flags2 0x100) &lt; 뺄셈 &lt; 가산 이다. 그래서 PAND01A 아르테미스 얼굴 GO 는 어둡게 하는
	 * 뺄셈 파트 (SubScrollTex2/3 · darkenMatFade) 위에 가산 얼굴·불꽃 (M01ArtemisFront01 · M01DoorFade2) 이 빛난다 (영상 3998–4001). 예전 엔진은 파트
	 * 순서 (얼굴 → 뺄셈) 로 그려 얼굴이 지워졌다 (43 §33). 0 = 알파·가장자리·INV, 1 = 뺄셈, 2 = 가산.
	 */
	public static int translucentRank(Blend b) {
		return b == Blend.SUB ? 1 : b == Blend.ADD ? 2 : 0;
	}

	/**
	 * @param fx 머티리얼 부가 효과 (애니메이션 프레임 / UV 스크롤 / 추가 레이어, 16·31 문서). null = 없음
	 * @param litColor 동적 라이트 (렌더 플래그 0x800) 경로의 머티리얼 색 = MAT 헤더 색 × 레이어 rgb (VU qw 76, 0x154410, 47 §3.1), [3..5] = 더할 색 qw 77 / 128
	 * @param late     그림자 합성 뒤 (층 2 반투명 단계 · 층 3) 머티리얼 ({@link devple.gow.engine.render.DrawStage#lateMaterial})
	 */
	public record Part(Mesh mesh, Texture texture, Blend blend, float[] tint, boolean lit, MaterialFx fx, float[] litColor, boolean late) {

		public Part(Mesh mesh, Texture texture, Blend blend, float[] tint, boolean lit, MaterialFx fx, float[] litColor) {
			this(mesh, texture, blend, tint, lit, fx, litColor, false);
		}

		public Part(Mesh mesh, Texture texture, Blend blend, float[] tint, boolean lit, MaterialFx fx) {
			this(mesh, texture, blend, tint, lit, fx, new float[]{tint[0], tint[1], tint[2], 0, 0, 0});
		}

		public Part(Mesh mesh, Texture texture, Blend blend, float[] tint, boolean lit) {
			this(mesh, texture, blend, tint, lit, null);
		}
	}

	/** 합성 뒤 단계 파트가 하나라도 있는가 ({@link devple.gow.engine.render.DrawStage#LATE} 에서 빨리 건너뛰기). */
	public boolean hasLate() {
		for (Part p : parts) {
			if (p.late()) {
				return true;
			}
		}
		return false;
	}

	@Override
	public void close() {
		parts.forEach(p -> p.mesh().close());
	}
}
