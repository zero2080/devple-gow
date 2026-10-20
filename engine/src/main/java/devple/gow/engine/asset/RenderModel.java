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
	 * @param fx 머티리얼 부가 효과 (애니메이션 프레임 / UV 스크롤 / 추가 레이어, 16·31 문서). null = 없음
	 * @param litColor 동적 라이트 (렌더 플래그 0x800) 경로의 머티리얼 색 = MAT 헤더 색 × 레이어 rgb (VU qw 76, 0x154410, 47 §3.1), [3..5] = 더할 색 qw 77 / 128
	 */
	public record Part(Mesh mesh, Texture texture, Blend blend, float[] tint, boolean lit, MaterialFx fx, float[] litColor) {

		public Part(Mesh mesh, Texture texture, Blend blend, float[] tint, boolean lit, MaterialFx fx) {
			this(mesh, texture, blend, tint, lit, fx, new float[]{tint[0], tint[1], tint[2], 0, 0, 0});
		}

		public Part(Mesh mesh, Texture texture, Blend blend, float[] tint, boolean lit) {
			this(mesh, texture, blend, tint, lit, null);
		}
	}

	@Override
	public void close() {
		parts.forEach(p -> p.mesh().close());
	}
}
