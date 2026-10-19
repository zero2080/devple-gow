package devple.gow.engine.asset;

import devple.gow.engine.render.Camera;
import devple.gow.engine.render.Shader;
import devple.gow.engine.render.SkinnedMesh;
import devple.gow.engine.render.Texture;
import devple.gow.tools.anim.Pose;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.anim.SkinnedModel;
import devple.gow.tools.model.GowModel;
import devple.gow.tools.texture.MaterialResource;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL41C.*;

/**
 * 스켈레톤 애니메이션 모델 (05 문서). 파트마다 행렬 팔레트가 다르다:
 * 모델 공간 파트 = skin(invBind·world), 관절 로컬 파트(jointRefs[0] != 0) = world.
 */
public final class AnimatedModel implements AutoCloseable {

	public static final int MAX_JOINTS = 128;

	private record Part(SkinnedMesh mesh, Texture texture, RenderModel.Blend blend, float[] tint, boolean lit,
						boolean jointLocal) {
	}

	private final Skeleton skeleton;
	private final List<Part> parts = new ArrayList<>();
	private final Shader shader;

	public AnimatedModel(WadAssets assets, GowModel model, Skeleton skeleton, Shader skinnedShader) {
		if (skeleton.size() > MAX_JOINTS) {
			throw new IllegalArgumentException("관절 수 초과: " + skeleton.size());
		}
		this.skeleton = skeleton;
		this.shader = skinnedShader;
		SkinnedModel sm = new SkinnedModel(model, 0, 0);
		for (SkinnedModel.Part p : sm.parts) {
			int n = p.pos.size();
			float[] v = new float[n * SkinnedMesh.STRIDE];
			for (int i = 0; i < n; i++) {
				int o = i * SkinnedMesh.STRIDE;
				float[] pos = p.pos.get(i);
				float[] nm = p.normal.get(i);
				float[] uv = p.uv.get(i);
				int[] c = p.color.get(i);
				int[] j = p.joints.get(i);
				System.arraycopy(pos, 0, v, o, 3);
				System.arraycopy(nm, 0, v, o + 3, 3);
				v[o + 6] = uv[0];
				v[o + 7] = uv[1];
				for (int k = 0; k < 4; k++) {
					v[o + 8 + k] = Math.min(c[k] / 128f, 2f);
				}
				// 스켈레톤 범위 밖 슬롯은 짝 관절로 대체 (05 문서 미해결 항목)
				int ja = j[0] < skeleton.size() ? j[0] : j[1];
				int jb = j[1] < skeleton.size() ? j[1] : ja;
				v[o + 12] = ja < skeleton.size() ? ja : 0;
				v[o + 13] = jb < skeleton.size() ? jb : 0;
				v[o + 14] = p.weights.get(i);
			}
			int[] idx = new int[p.tris.size() * 3];
			for (int i = 0; i < p.tris.size(); i++) {
				System.arraycopy(p.tris.get(i), 0, idx, i * 3, 3);
			}
			MaterialResource mat = assets.material(p.material);
			Texture tex = null;
			RenderModel.Blend blend = RenderModel.Blend.OPAQUE;
			float[] tint = {1, 1, 1, 1};
			if (mat != null && !mat.layers().isEmpty()) {
				MaterialResource.Layer l = mat.layers().getFirst();
				tex = p.hasUv ? assets.texture(l.texture()) : null;
				blend = RenderModel.Blend.of(l.blendName());
				tint = l.rgba();
			}
			parts.add(new Part(new SkinnedMesh(v, idx), tex, blend, tint, p.hasNormal, p.jointLocal()));
		}
	}

	public Skeleton skeleton() {
		return skeleton;
	}

	public void draw(Camera cam, float aspect, Matrix4f world, Pose pose, Texture white) {
		float[][] w = pose.world(skeleton);
		float[][] skin = skeleton.skin(w);
		shader.use();
		shader.set("uViewProj", cam.viewProj(aspect));
		shader.set("uModel", world);
		shader.set("uTex", 0);
		for (int pass = 0; pass < 2; pass++) {
			Boolean lastLocal = null;
			for (Part p : parts) {
				boolean opaque = p.blend() == RenderModel.Blend.OPAQUE;
				if (opaque != (pass == 0)) {
					continue;
				}
				if (lastLocal == null || lastLocal != p.jointLocal()) {
					upload(p.jointLocal() ? w : skin);
					lastLocal = p.jointLocal();
				}
				if (!opaque) {
					glEnable(GL_BLEND);
					glDepthMask(false);
					glBlendFunc(GL_SRC_ALPHA, p.blend() == RenderModel.Blend.ALPHA ? GL_ONE_MINUS_SRC_ALPHA : GL_ONE);
					glBlendEquation(p.blend() == RenderModel.Blend.SUB ? GL_FUNC_REVERSE_SUBTRACT : GL_FUNC_ADD);
				}
				(p.texture() != null ? p.texture() : white).bind(0);
				shader.set("uUseTex", p.texture() != null ? 1 : 0);
				shader.set("uLit", p.lit() ? 1 : 0);
				float[] t = p.tint();
				shader.set("uTint", new org.joml.Vector4f(t[0], t[1], t[2], t[3]));
				p.mesh().draw();
				if (!opaque) {
					glDisable(GL_BLEND);
					glDepthMask(true);
					glBlendEquation(GL_FUNC_ADD);
				}
			}
		}
	}

	private void upload(float[][] mats) {
		try (MemoryStack st = MemoryStack.stackPush()) {
			FloatBuffer fb = st.mallocFloat(mats.length * 16);
			for (float[] m : mats) {
				fb.put(m);
			}
			fb.flip();
			glUniformMatrix4fv(shader.location("uJoints"), false, fb);
		}
	}

	@Override
	public void close() {
		parts.forEach(p -> p.mesh().close());
	}
}
