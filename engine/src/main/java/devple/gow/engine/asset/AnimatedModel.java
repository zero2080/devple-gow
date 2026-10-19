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
						boolean jointLocal, devple.gow.tools.fx.MaterialAnim.Anim matAnim) {
	}

	private final Skeleton skeleton;
	private List<Part> parts = new ArrayList<>();
	private final List<Part> base;
	private final Shader shader;
	private final WadAssets assets;
	private final GowModel model;
	/** 모델 변형 (원작 모델 컴포넌트 +0x120 강제 LOD) 별 파트, 처음 필요할 때 만든다. −1 = 기본 (거리 LOD 0). */
	private final java.util.Map<Integer, List<Part>> variants = new java.util.HashMap<>();
	private int variant = -1;

	public AnimatedModel(WadAssets assets, GowModel model, Skeleton skeleton, Shader skinnedShader) {
		if (skeleton.size() > MAX_JOINTS) {
			throw new IllegalArgumentException("관절 수 초과: " + skeleton.size());
		}
		this.skeleton = skeleton;
		this.shader = skinnedShader;
		this.assets = assets;
		this.model = model;
		build(new SkinnedModel(model, 0, 0), parts);
		base = parts;
	}

	/**
	 * 모델 변형 고르기 (GL 스레드): v ≥ 0 이면 파트마다 그룹 v (비면 그룹 0) — 원작이 클래스 코드로 모델 컴포넌트 +0x120 을 정하는 경우
	 * (BMI 0x1C1AA8: 단계 0 → 2, 1 → 1, 2+ → 0). −1 = 기본.
	 */
	public void setVariant(int v) {
		if (v == variant) {
			return;
		}
		variant = v;
		if (v < 0) {
			parts = base;
			return;
		}
		parts = variants.computeIfAbsent(v, k -> {
			List<Part> out = new ArrayList<>();
			build(new SkinnedModel(model, k, 0, true), out);
			return out;
		});
	}

	private void build(SkinnedModel sm, List<Part> parts) {
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
				// 레벨과 같은 분류 (31 문서): GS 0x44 대부분은 반투명이 아니라 불투명 + 알파 테스트 0.75 (CUTOUT)
				blend = assets.classify(mat, tex != null);
				tint = l.rgba();
			}
			parts.add(new Part(new SkinnedMesh(v, idx), tex, blend, tint, p.hasNormal, p.jointLocal(), assets.materialAnim(p.material)));
		}
	}

	public Skeleton skeleton() {
		return skeleton;
	}

	public void draw(Camera cam, float aspect, Matrix4f world, Pose pose, Texture white) {
		draw(cam, aspect, world, pose, white, -1f, 1f);
	}

	/**
	 * 머티리얼 ANM 을 적용해 그린다 (이펙트 GO, 40 문서 §5): fxTime ≥ 0 이면 파트 머티리얼의 UV 스크롤(레이어 0)과
	 * 불투명도(hdr8 = p28)를 시간 fxTime 으로, alpha 는 전체 불투명도 배율 (페이드 아웃).
	 */
	public void draw(Camera cam, float aspect, Matrix4f world, Pose pose, Texture white, float fxTime, float alpha) {
		draw(cam, aspect, world, pose.world(skeleton), white, fxTime, alpha);
	}

	/** 관절 월드 행렬을 직접 받아 그린다 (레벨 오브젝트 Animator 애니: 관절 = 애니 월드 × 인스턴스 행렬, world = 단위). */
	public void draw(Camera cam, float aspect, Matrix4f world, float[][] w, Texture white, float fxTime, float alpha) {
		float[][] skin = skeleton.skin(w);
		shader.use();
		shader.set("uViewProj", cam.viewProj(aspect));
		shader.set("uModel", world);
		shader.set("uTex", 0);
		// 패스 0 = OPAQUE + CUTOUT(알파 ≥ 0.75, 깊이 쓰기), 패스 1 = CUTOUT 가장자리 + ALPHA/ADD/SUB (깊이 쓰기 끔) — Renderer.draw 와 동일
		for (int pass = 0; pass < 2; pass++) {
			Boolean lastLocal = null;
			for (Part p : parts) {
				RenderModel.Blend b = p.blend();
				boolean solid = b == RenderModel.Blend.OPAQUE || b == RenderModel.Blend.CUTOUT;
				if (pass == 0 && !solid || pass == 1 && b == RenderModel.Blend.OPAQUE) {
					continue;
				}
				if (lastLocal == null || lastLocal != p.jointLocal()) {
					upload(p.jointLocal() ? w : skin);
					lastLocal = p.jointLocal();
				}
				RenderModel.Blend mode = b;
				if (b == RenderModel.Blend.CUTOUT) {
					mode = pass == 0 ? RenderModel.Blend.OPAQUE : RenderModel.Blend.ALPHA;
					shader.set(pass == 0 ? "uAlphaRef" : "uAlphaMax", 0.75f);
				}
				boolean opaque = mode == RenderModel.Blend.OPAQUE;
				if (!opaque) {
					glEnable(GL_BLEND);
					glDepthMask(false);
					switch (mode) {
						case ADD, SUB -> glBlendFunc(GL_SRC_ALPHA, GL_ONE);
						case INV -> glBlendFunc(GL_ONE_MINUS_SRC_ALPHA, GL_SRC_ALPHA);
						default -> glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
					}
					glBlendEquation(mode == RenderModel.Blend.SUB ? GL_FUNC_REVERSE_SUBTRACT : GL_FUNC_ADD);
				}
				(p.texture() != null ? p.texture() : white).bind(0);
				shader.set("uUseTex", p.texture() != null ? 1 : 0);
				shader.set("uLit", p.lit() ? 1 : 0);
				float[] t = p.tint();
				float op = alpha;
				boolean scrolled = false;
				if (fxTime >= 0 && p.matAnim() != null) {
					float[] uv = p.matAnim().uv(0, fxTime);
					if (uv != null) {
						shader.set("uUvOffset", uv[0], uv[1]);
						scrolled = true;
					}
					op *= p.matAnim().value("hdr8", fxTime, 1f);
				}
				shader.set("uTint", new org.joml.Vector4f(t[0], t[1], t[2], t[3] * op));
				p.mesh().draw();
				if (scrolled) {
					shader.set("uUvOffset", 0f, 0f);
				}
				if (!opaque) {
					glDisable(GL_BLEND);
					glDepthMask(true);
					glBlendEquation(GL_FUNC_ADD);
				}
				if (b == RenderModel.Blend.CUTOUT) {
					shader.set("uAlphaRef", 0f);
					shader.set("uAlphaMax", 0f);
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
		base.forEach(p -> p.mesh().close());
		variants.values().forEach(l -> l.forEach(p -> p.mesh().close()));
	}
}
