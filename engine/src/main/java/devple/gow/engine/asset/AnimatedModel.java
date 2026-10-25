package devple.gow.engine.asset;

import devple.gow.engine.light.ShadowCasters;
import devple.gow.engine.light.ShadowGeometry;
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

	/**
	 * @param litColor 동적 라이트 경로의 머티리얼 색 (MAT 헤더 색 × 레이어 rgb, VU qw 76 — {@link WadAssets#litColor})
	 * @param src     원본 스킨 파트 — 혼합 노드 팔레트 ({@link SkinnedModel.Part#palette}, 05 문서 "범위 밖 슬롯")
	 * @param postFog 안개 뒤 층 (3·4) 머티리얼 ({@link WadAssets#postFogLayer}) — 안개 없음 (모든 모델, 43 §31)
	 * @param fx      추가 레이어 · 첫 레이어 환경맵 (구운 레벨 메시와 같은 {@link devple.gow.engine.fx.MaterialFx}, null = 없음) — 자세 GO 도 정적일 때와 같이 그린다
	 */
	private record Part(SkinnedMesh mesh, Texture texture, RenderModel.Blend blend, float[] tint, boolean lit,
						boolean jointLocal, devple.gow.tools.fx.MaterialAnim.Anim matAnim, int[] joints, float[] litColor, boolean late,
						SkinnedModel.Part src, boolean postFog, devple.gow.engine.fx.MaterialFx fx) {

		/** 파트가 쓰는 관절이 모두 숨김이면 그리지 않는다 (ANM u8 가시성, 41 문서 §2). */
		boolean hiddenBy(boolean[] hidden) {
			if (hidden == null || joints.length == 0) {
				return false;
			}
			for (int j : joints) {
				if (j >= hidden.length || !hidden[j]) {
					return false;
				}
			}
			return true;
		}
	}

	/**
	 * 다음 draw 한 번에 쓸 관절 숨김 표 ({@link devple.gow.tools.anim.Animation#hiddenJoints}). 모델은 이펙트 인스턴스끼리 공유되므로
	 * 호출자가 그리기 직전에 넣고 바로 뒤 null 로 되돌린다.
	 */
	public boolean[] hiddenJoints;

	/**
	 * 다음 draw 한 번에 쓸 스크립트 재질 애니 시간 (재질 이름 → 시간, {@code nav.Platforms.MatClock}): 그 재질의 UV·불투명도는 fxTime 대신 이 시간으로.
	 * {@link #hiddenJoints} 처럼 호출자가 넣고 바로 뒤 null 로.
	 */
	public java.util.Map<String, Float> matTimes;

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
		ShadowGeometry g = null;
		if (System.getProperty("gow.noshadow") == null) {
			try {
				g = ShadowGeometry.forModel(assets.wad(), model.name); // 같은 WAD 의 SHG_<모델> (47 문서 §7)
			} catch (RuntimeException ex) {
				System.out.println("그림자 지오메트리 해석 실패 " + model.name + ": " + ex.getMessage());
			}
		}
		shadow = g;
	}

	/** 그림자 지오메트리 SHG_ (없으면 null) — 그리기 때 {@link ShadowCasters} 가 모으고 있으면 넘긴다. */
	public final ShadowGeometry shadow;
	/** 그림자 모드 (renShadowServer +0x14C): 0 밀어내기 (기본), 1 받는 면 판 (AI 생물), 2 받는 면까지 (영웅). */
	public int shadowMode = devple.gow.engine.light.ShadowVolume.MODE_EXTRUDE;
	/** 부모 GO 의 모델 (블레이드 → 영웅): 방향·평면·모드를 부모 것으로 (0x1628E0 부모 사슬, 0x1BF994). */
	public Object shadowParent;
	/** 스크립트 이름 (Vis 슬롯 2 / SCR_NoShadow 로 끄기). */
	public String shadowKey;
	/**
	 * 그리기 층 3 (0x150738(인스턴스, 2) → 머티리얼 bit 35) — 층 2 가 다 끝난 뒤라 모든 파트가 그림자 합성 뒤
	 * ({@link devple.gow.engine.render.DrawStage#LATE}) 이고 안개 노드 (층 2 칸 5) 뒤라 안개를 받지 않는다. 기본 false = 층 2 (파트별 머티리얼 단계).
	 * 원작에서 이 호출을 받는 것은 사슬·궤적 띠 (0x23EB58 인스턴스: Blades_Init · 0x12DE70 · 0x2192B8) 와 하늘 (1) · 거울 (0) 뿐이라
	 * 블레이드·아르테미스의 검 모델은 층 2 다 (43 §31).
	 */
	public boolean layer3;
	/**
	 * 하늘 모델 (SCR_Sky 소유 GO, 0x218778): 동적 조명 플래그 0x800 을 끄고 층 1 로 — 라이트 묶음·그림자를 모으지 않는다 [확실: 0x218778 이 렌더 플래그 &amp;= ~0x800].
	 */
	public boolean sky;
	/**
	 * 이펙트 GO (EffectSystem): 안개 앞 반투명 패스로 그린다 ({@link devple.gow.engine.fx.SceneFog}, 16 §1.2) — 호출자가 셰이더에 장면 안개를 걸고,
	 * 파트마다 불투명 = 조각 깊이 (지오메트리와 같음), 반투명 = 장면 깊이 사본 (uFogScene, 원작 안개 패스는 깊이를 쓰지 않는 화소에 뒤 장면 Z 의
	 * 계수), 가산·뺄셈 = (1 − F) 배 (uFogAdd), 층 3·4 머티리얼 = 안개 없음 (uFogOff). false (캐릭터·레벨 GO) 면 반투명 파트도 조각 깊이
	 * (가산·뺄셈 (1 − F) · 층 3·4 머티리얼 · {@link #layer3} 안개 없음은 같다, 43 §31).
	 */
	public boolean sceneFog;

	/** 그림자 합성 뒤 단계 ({@link devple.gow.engine.render.DrawStage#LATE}) 에 그릴 파트가 있는가. */
	public boolean drawsLate() {
		return layer3 || hasLate(parts);
	}

	private boolean hasLate(List<Part> ps) {
		for (Part p : ps) {
			if (p.late()) {
				return true;
			}
		}
		return false;
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
				// 관절 수 이상 슬롯 = 메시 혼합 노드 (GowMesh.blendJoints, 팔레트 뒤쪽) — 표가 없는 범위 밖 번호만 짝 관절로 대체
				int lim = palSize(p);
				int ja = j[0] < lim ? j[0] : j[1];
				int jb = j[1] < lim ? j[1] : ja;
				v[o + 12] = ja < lim ? ja : 0;
				v[o + 13] = jb < lim ? jb : 0;
				v[o + 14] = p.weights.get(i);
			}
			java.util.TreeSet<Integer> used = new java.util.TreeSet<>();
			for (int i = 0; i < n; i++) {
				usedJoints(p, (int) v[i * SkinnedMesh.STRIDE + 12], used);
				if (v[i * SkinnedMesh.STRIDE + 14] < 1f) {
					usedJoints(p, (int) v[i * SkinnedMesh.STRIDE + 13], used);
				}
			}
			int[] idx = new int[p.tris.size() * 3];
			for (int i = 0; i < p.tris.size(); i++) {
				System.arraycopy(p.tris.get(i), 0, idx, i * 3, 3);
			}
			MaterialResource mat = assets.material(p.material);
			if (WadAssets.stencilOnly(mat)) {
				continue; // 그림자 볼륨 전용 (색으로 그리지 않음)
			}
			boolean late = WadAssets.lateStage(mat);
			Texture tex = null;
			RenderModel.Blend blend = RenderModel.Blend.OPAQUE;
			float[] tint = {1, 1, 1, 1};
			float[] litColor = {1, 1, 1, 0, 0, 0};
			devple.gow.engine.fx.MaterialFx fx = null;
			if (mat != null && !mat.layers().isEmpty()) {
				MaterialResource.Layer l = mat.layers().getFirst();
				litColor = WadAssets.litColor(mat, l);
				tex = p.hasUv ? assets.texture(l.texture()) : null;
				// 레벨과 같은 분류 (31 문서): GS 0x44 대부분은 반투명이 아니라 불투명 + 알파 테스트 0.75 (CUTOUT)
				blend = assets.classify(mat, tex != null);
				tint = l.rgba();
				// 추가 레이어 · 첫 레이어 환경맵 (구운 메시와 같은 해석) — 첫 레이어 플립북·UV 는 matAnim 이 맡는다
				fx = assets.materialFx(mat, p.hasUv, p.hasNormal, model.renderFlags);
			}
			parts.add(new Part(new SkinnedMesh(v, idx), tex, blend, tint, p.hasNormal, p.jointLocal(skeleton), assets.materialAnim(p.material),
					used.stream().mapToInt(Integer::intValue).toArray(), litColor, late, p, WadAssets.postFogLayer(mat), fx));
		}
	}

	/** 파트 정점이 가리킬 수 있는 팔레트 크기: 관절 수, 혼합 노드가 있으면 그 뒤까지 (셰이더 uJoints 128 안). */
	private int palSize(SkinnedModel.Part p) {
		int n = p.hasBlend() ? Math.max(skeleton.size(), p.blendBase + p.blendJoints.length) : skeleton.size();
		return Math.min(n, MAX_JOINTS);
	}

	/** 숨김 판정용 실제 관절: 혼합 노드는 그것을 이루는 관절들. */
	private void usedJoints(SkinnedModel.Part p, int slot, java.util.Set<Integer> out) {
		int b = slot - p.blendBase;
		if (p.hasBlend() && slot >= p.blendBase && b < p.blendJoints.length) {
			for (int j : p.blendJoints[b]) {
				out.add(j);
			}
		} else {
			out.add(slot);
		}
	}

	public Skeleton skeleton() {
		return skeleton;
	}

	public void draw(Camera cam, float aspect, Matrix4f world, Pose pose, Texture white) {
		draw(cam, aspect, world, pose, white, -1f, 1f);
	}

	/**
	 * 인스턴스 색 RGB (원작 메시 인스턴스 +0xB0 xyz, w = 불투명도 0x1504B0; sub_001E03C0 이 xyz 만 바꾼다) — 파트 색에 곱한다
	 * [추정: 곱]. 기본 (1, 1, 1). 신들의 분노 틴트·포세이돈 단계 틴트.
	 */
	public void setColorScale(float r, float g, float b) {
		colorScale[0] = r;
		colorScale[1] = g;
		colorScale[2] = b;
	}

	private final float[] colorScale = {1, 1, 1};
	/** 환경맵 UV 뷰 회전 (그리기마다 다시, 할당 없이). */
	private final org.joml.Matrix3f envView = new org.joml.Matrix3f();
	private float[][] skinOut, skinRows;

	/** 마지막 draw 의 라이트 묶음 (없으면 null) — 디버그 출력용. */
	public devple.gow.engine.light.LightRig lastLights;

	/**
	 * 머티리얼 ANM 을 적용해 그린다 (이펙트 GO, 40 문서 §5): fxTime ≥ 0 이면 파트 머티리얼의 UV 스크롤(레이어 0)과
	 * 불투명도(hdr8 = p28)를 시간 fxTime 으로, alpha 는 전체 불투명도 배율 (페이드 아웃).
	 */
	public void draw(Camera cam, float aspect, Matrix4f world, Pose pose, Texture white, float fxTime, float alpha) {
		draw(cam, aspect, world, pose.world(skeleton), white, fxTime, alpha);
	}

	/** 관절 월드 행렬을 직접 받아 그린다 (레벨 오브젝트 Animator 애니: 관절 = 애니 월드 × 인스턴스 행렬, world = 단위). */
	public void draw(Camera cam, float aspect, Matrix4f world, float[][] w, Texture white, float fxTime, float alpha) {
		if (skinOut == null) {
			skinOut = new float[skeleton.size()][];
			skinRows = new float[skeleton.size()][];
		}
		ShadowCasters sc = shadow != null && !sky ? ShadowCasters.current() : null;
		if (sc != null && sc.collecting()) {
			sc.submit(this, shadowParent, shadow, w, hiddenJoints, world, devple.gow.engine.light.LightServer.jointSphere(w, world, new org.joml.Vector4f()),
					shadowMode, shadowKey);
		}
		// 그림자 합성 앞/뒤 단계 (47 §7.4.2): 이 단계에 그릴 파트가 없으면 스키닝·라이트도 건너뛴다 (그림자는 위에서 이미 넘겼다)
		int stage = devple.gow.engine.render.DrawStage.current();
		if (stage == devple.gow.engine.render.DrawStage.EARLY && layer3
				|| stage == devple.gow.engine.render.DrawStage.LATE && !layer3 && !hasLate(parts)) {
			return;
		}
		float[][] skin = skeleton.skin(w, skinOut, skinRows); // 그리기 안에서만 쓰는 작업 공간 (48 문서 §9)
		shader.use();
		shader.set("uViewProj", cam.viewProj(aspect));
		float[] cp = cam.clipPlane;
		shader.set("uClipPlane", cp != null ? cp[0] : 0f, cp != null ? cp[1] : 0f, cp != null ? cp[2] : 0f, cp != null ? cp[3] : 0f);
		shader.set("uModel", world);
		shader.set("uTex", 0);
		shader.set("uEnvView", devple.gow.engine.render.Renderer.envView(cam, envView));
		// 동적 라이트 (47 문서): 렌더 플래그 0x800 모델만 (원작 0x15986C), 구 = 관절 AABB [근사]
		if (!sky && (model.renderFlags & devple.gow.engine.light.LightUniforms.LIT) != 0 && devple.gow.engine.light.LightServer.current() != null) {
			org.joml.Vector4f sph = devple.gow.engine.light.LightServer.jointSphere(w, world, new org.joml.Vector4f());
			lastLights = devple.gow.engine.light.LightUniforms.apply(shader, model.renderFlags, new org.joml.Vector3f(sph.x, sph.y, sph.z), sph.w);
		} else {
			devple.gow.engine.light.LightUniforms.off(shader);
			lastLights = null;
		}
		// 패스 0 = OPAQUE + CUTOUT(알파 ≥ 0.75, 깊이 쓰기), 패스 1 = CUTOUT 가장자리 + ALPHA/ADD/SUB (깊이 쓰기 끔) — Renderer.draw 와 동일
		for (int step = 0; step < 4; step++) {
			int pass = step == 0 ? 0 : 1, rank = Math.max(0, step - 1); // 단계 0 = 패스 0, 1..3 = 패스 1 의 순위 0..2
			Boolean lastLocal = null;
			int[][] lastBlend = null;
			for (Part p : parts) {
				RenderModel.Blend b = p.blend();
				boolean solid = b == RenderModel.Blend.OPAQUE || b == RenderModel.Blend.CUTOUT;
				// 패스 1 은 반투명 단계 안 원작 순서로 세 번: 알파·가장자리 → 뺄셈 → 가산 (RenderModel.translucentRank, 43 §33)
				if (pass == 1 && RenderModel.translucentRank(b) != rank) {
					continue;
				}
				if (pass == 0 && !solid || pass == 1 && b == RenderModel.Blend.OPAQUE || p.hiddenBy(hiddenJoints)
						|| !layer3 && devple.gow.engine.render.DrawStage.skip(p.late())) {
					continue;
				}
				if (lastLocal == null || lastLocal != p.jointLocal() || lastBlend != p.src().blendJoints) {
					float[][] pal = p.src().palette(p.jointLocal() ? w : skin); // 관절 뒤에 혼합 노드 (GowMesh.blendJoints)
					upload(pal.length > MAX_JOINTS ? java.util.Arrays.copyOf(pal, MAX_JOINTS) : pal);
					lastLocal = p.jointLocal();
					lastBlend = p.src().blendJoints;
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
				shader.set("uEnvUv", p.fx() != null && p.fx().env0() && p.lit() ? 1 : 0);
				float[] t = p.tint();
				float op = alpha;
				boolean scrolled = false;
				float ft = fxTime;
				if (matTimes != null && p.matAnim() != null) { // 스크립트 재질 애니 ("$", nav.Platforms.MatClock)
					Float mt = matTimes.get(p.matAnim().material());
					if (mt != null) {
						ft = mt;
					}
				}
				float ct = Float.isNaN(ft) ? -1f : ft; // 색 트랙 시간 (아직 틀지 않은 0x42 = NaN → 걸지 않음, 43 §33)
				ft = Float.isNaN(ft) ? 0f : ft;
				if (p.fx() != null && p.matAnim() != null) { // 색 트랙 (레이어 0 rgba — 첫 레이어 색 = 레이어 rgba 그대로)
					t = devple.gow.engine.fx.MaterialFx.colorTint(p.matAnim(), p.fx().header(), p.fx().layerRgba(), 0, ct, t, false);
				}
				if (ft >= 0 && p.matAnim() != null) {
					float[] uv = p.matAnim().uv(0, ft);
					if (uv != null) {
						shader.set("uUvOffset", uv[0], uv[1]);
						scrolled = true;
					}
					op *= p.matAnim().value("hdr8", ft, 1f);
				}
				shader.set("uTint", new org.joml.Vector4f(t[0] * colorScale[0], t[1] * colorScale[1], t[2] * colorScale[2], t[3] * op));
				// 동적 라이트 경로: M = 머티리얼 색 × 물체 색 (qw 76 = 0x154410 의 헤더 × 레이어 × 물체 +0xB0, 47 §3.1)
				float[] lc = p.fx() != null && p.matAnim() == p.fx().anim() ? p.fx().litColor(0, ct, p.litColor()) : p.litColor();
				shader.set("uLgtMat", lc[0] * colorScale[0], lc[1] * colorScale[1], lc[2] * colorScale[2]);
				shader.set("uLgtAdd", lc[3], lc[4], lc[5]);
				// 노멀맵 (원작에 없음, 44 문서): 첫 레이어 텍스처, 불투명·알파 블렌드만 (환경맵 첫 레이어 제외 — Renderer 와 같음)
				boolean nm = (mode == RenderModel.Blend.OPAQUE || mode == RenderModel.Blend.ALPHA) && !(p.fx() != null && p.fx().env0())
						&& devple.gow.engine.render.Renderer.bindNormalMap(shader, p.texture());
				// 안개 (16 §1.2, 43 §31): 원작 안개 노드는 층 2 칸 5 의 후처리라 층 2 캐릭터·자세 GO 도 Z 로 받는다 — 가산·뺄셈 파트는 (1 − F) 배
				// (Renderer 의 구운 메시와 같음), 층 3 (블레이드) · 층 3·4 머티리얼은 안개 뒤. 장면 깊이 사본은 이펙트 GO 반투명 파트만 (sceneFog)
				shader.set("uFogScene", sceneFog && !opaque ? 1 : 0);
				shader.set("uFogAdd", mode == RenderModel.Blend.ADD || mode == RenderModel.Blend.SUB ? 1 : 0);
				shader.set("uFogOff", layer3 || p.postFog() ? 1 : 0);
				p.mesh().draw();
				shader.set("uFogScene", 0);
				shader.set("uFogAdd", 0);
				shader.set("uFogOff", 0);
				if (nm) {
					shader.set("uNmOn", 0);
				}
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
				// 추가 레이어: CUTOUT 은 패스 0 (불투명 화소) 뒤에만 — 구운 메시 (Renderer.drawPasses) 와 같은 규칙
				if (p.fx() != null && !p.fx().layers().isEmpty() && (b != RenderModel.Blend.CUTOUT || pass == 0)) {
					drawLayers(p, ft, white);
				}
			}
		}
		shader.set("uEnvUv", 0);
	}

	/**
	 * 머티리얼 2·3 번째 레이어를 같은 메시에 LEQUAL 로 레이어 순서대로 덧그린다 (16 문서 §3.3 — 원작은 레이어마다 같은 정점·UV·정점색으로 추가 패스).
	 * 블렌드·색·환경맵 UV 는 구운 레벨 메시와 같은 {@link devple.gow.engine.fx.MaterialFx.Layer} — 예전에는 자세 GO·캐릭터가 첫 레이어만 그려
	 * 밀린 발리스타 틀 (MAT_M02MetalTrim01b: 환경맵 + 0x01 레이어) 이 정적일 때와 달랐다 (43 §25).
	 */
	private void drawLayers(Part p, float ft, Texture white) {
		devple.gow.engine.fx.MaterialFx fx = p.fx();
		glDepthFunc(GL_LEQUAL);
		glEnable(GL_BLEND);
		glDepthMask(false);
		for (devple.gow.engine.fx.MaterialFx.Layer l : fx.layers()) {
			RenderModel.Blend lb = l.blend();
			switch (lb) {
				case ADD -> {
					glBlendEquation(GL_FUNC_ADD);
					glBlendFunc(GL_SRC_ALPHA, GL_ONE);
				}
				case SUB -> {
					glBlendEquation(GL_FUNC_REVERSE_SUBTRACT);
					glBlendFunc(GL_SRC_ALPHA, GL_ONE);
				}
				case INV -> {
					// GS 0x01: (Cd − Cs)·As + Cs
					glBlendEquation(GL_FUNC_ADD);
					glBlendFunc(GL_ONE_MINUS_SRC_ALPHA, GL_SRC_ALPHA);
				}
				default -> {
					glBlendEquation(GL_FUNC_ADD);
					glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
				}
			}
			Texture tex = fx.frame(l, Math.max(ft, 0f));
			(tex != null ? tex : white).bind(0);
			shader.set("uUseTex", tex != null ? 1 : 0);
			float[] t = fx.tint(l.index(), ft, l.tint(), l.texture() == null); // 레이어 색 트랙 (43 §33)
			shader.set("uTint", new org.joml.Vector4f(t[0] * colorScale[0], t[1] * colorScale[1], t[2] * colorScale[2], t[3]));
			float[] lc = l.litColor();
			shader.set("uLgtMat", lc[0] * colorScale[0], lc[1] * colorScale[1], lc[2] * colorScale[2]);
			shader.set("uLgtAdd", lc[3], lc[4], lc[5]);
			shader.set("uEnvUv", l.env() && p.lit() ? 1 : 0);
			float[] uv = ft >= 0 ? fx.uv(l.index(), ft) : null;
			shader.set("uUvOffset", uv != null ? uv[0] : 0f, uv != null ? uv[1] : 0f);
			shader.set("uFogScene", sceneFog ? 1 : 0);
			shader.set("uFogAdd", lb == RenderModel.Blend.ADD || lb == RenderModel.Blend.SUB ? 1 : 0);
			shader.set("uFogOff", layer3 || p.postFog() ? 1 : 0);
			p.mesh().draw();
		}
		shader.set("uFogScene", 0);
		shader.set("uFogAdd", 0);
		shader.set("uFogOff", 0);
		shader.set("uEnvUv", 0);
		shader.set("uUvOffset", 0f, 0f);
		glDisable(GL_BLEND);
		glDepthMask(true);
		glBlendEquation(GL_FUNC_ADD);
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
		glDepthFunc(GL_LESS);
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
