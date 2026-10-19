package devple.gow.engine.asset;

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
		OPAQUE, ALPHA, ADD, SUB;

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

	public record Part(Mesh mesh, Texture texture, Blend blend, float[] tint, boolean lit) {
	}

	@Override
	public void close() {
		parts.forEach(p -> p.mesh().close());
	}
}
