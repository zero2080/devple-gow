package devple.gow.engine.render;

import devple.gow.engine.asset.RenderModel;
import devple.gow.engine.core.Engine;
import devple.gow.engine.fx.MaterialFx;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import static org.lwjgl.opengl.GL41C.*;

/** 프레임 시작/끝, 공용 셰이더, 화면 페이드. */
public final class Renderer implements AutoCloseable {

	private final Engine engine;
	private final Shader basic;
	private final Mesh fullscreen;
	private final Matrix4f identity = new Matrix4f();
	private final Texture white;
	private float fxTime;

	public Renderer(Engine engine) {
		this.engine = engine;
		this.basic = new Shader("basic.vert", "basic.frag");
		this.white = Texture.white();
		this.fullscreen = new MeshBuilder()
				.vertex(-1, -1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1)
				.vertex(1, -1, 0, 0, 0, 1, 1, 0, 0, 0, 0, 1)
				.vertex(1, 1, 0, 0, 0, 1, 1, 1, 0, 0, 0, 1)
				.vertex(-1, -1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1)
				.vertex(1, 1, 0, 0, 0, 1, 1, 1, 0, 0, 0, 1)
				.vertex(-1, 1, 0, 0, 0, 1, 0, 1, 0, 0, 0, 1)
				.build(GL_TRIANGLES);
	}

	public Texture white() {
		return white;
	}

	public Shader basic() {
		return basic;
	}

	/**
	 * 원작 프레임 지움 색 (43 문서 §13.4) [확실]: 0x161A80 이 프레임 맨 처음 0x29CF64 사각형 노드 (클래스 0x2BE240, 그리기 0x121128) 를 그린다 —
	 * 0x15F638 이 만든 값 그대로 색 (0, 0, 0, 0), Z 0, TEST 0x30000 (ALWAYS), FBMSK 0, ALPHA 0, ZMSK 0. 다른 쓰기 없음 → 언제나 검정.
	 * 레벨·안개·컨텍스트 값이 아니다 (배경 안개는 그 위에 따로 칠한다, 16 문서 §1.2).
	 */
	public static final float CLEAR_R = 0f, CLEAR_G = 0f, CLEAR_B = 0f;

	public void beginFrame() {
		devple.gow.engine.fx.SceneFog.end(); // 지난 프레임의 장면 깊이 안개를 넘기지 않는다
		glViewport(0, 0, engine.window().width(), engine.window().height());
		glClearColor(CLEAR_R, CLEAR_G, CLEAR_B, 1f);
		glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
	}

	/** 3D 드로우 공통 설정. */
	public void begin3d(Camera cam) {
		basic.use();
		white.bind(0);
		basic.set("uTex", 0);
		basic.set("uViewProj", cam.viewProj(engine.window().aspect()));
		basic.set("uEnvView", envView(cam, new org.joml.Matrix3f()));
		basic.set("uTint", new Vector4f(1, 1, 1, 1));
		basic.set("uUseTex", 0);
		basic.set("uLit", 1);
	}

	public void draw(Mesh m, Matrix4f model, boolean lit) {
		basic.set("uModel", model);
		basic.set("uLit", lit ? 1 : 0);
		m.draw();
	}

	/** 머티리얼 애니메이션(프레임/UV 스크롤) 시간 (초). 레벨 씬이 매 프레임 설정한다 (31 문서). */
	public void setFxTime(float t) {
		fxTime = t;
	}

	/**
	 * 다음 그리기 (한 인스턴스) 의 스크립트 재질 애니 시간 (재질 이름 → 시간, {@code nav.Platforms.MatClock}) — 그 재질은 레벨 시간 대신 이 시간으로.
	 * null = 없음. 그린 뒤 null 로 되돌린다.
	 */
	public void setMaterialTimes(java.util.Map<String, Float> times) {
		matTimes = times;
	}

	private java.util.Map<String, Float> matTimes;

	private float partTime(MaterialFx fx) {
		if (matTimes != null && fx != null && fx.anim() != null) {
			Float t = matTimes.get(fx.anim().material());
			if (t != null) {
				return t;
			}
		}
		return fxTime;
	}

	public float fxTime() {
		return fxTime;
	}

	/**
	 * 원작 머티리얼 블렌드 재현 (16 문서 §3.3):
	 * 패스 0 = OPAQUE + CUTOUT(알파 ≥ 0.75, 깊이 쓰기), 패스 1 = CUTOUT 가장자리(알파 &lt; 0.75) + ALPHA/ADD/SUB (깊이 쓰기 끔).
	 * 추가 레이어는 기본 레이어 직후 같은 지오메트리에 LEQUAL 로 덧그린다.
	 */
	public void draw(RenderModel model, Matrix4f world) {
		drawPasses(model, world, 0, 1);
	}

	/**
	 * 한 패스만 (0 = 불투명·알파 테스트, 1 = 반투명). 레벨처럼 여러 모델을 그릴 때는 모든 모델의 패스 0 뒤에 패스 1 을 그린다:
	 * 원작은 정렬(반투명) 플래그 0x8000 머티리얼을 불투명 뒤 따로 그린다 (16 문서 §3.3, 43 문서 — ATHN01D 갑판 판자 p28 0.967 이
	 * 0.4 아래 구름 반사 평면에 덮이던 문제).
	 */
	public void drawPass(RenderModel model, Matrix4f world, int pass) {
		drawPasses(model, world, pass, pass);
	}

	/**
	 * 그리기 층 0 (SCR_Mirror 소유 모델, 0x129820 → 0x150738(모델, 0) = 머티리얼 층 bit 32) 의 원작 그리기 (43 문서 §12):
	 * 머티리얼 렌더 0x165998 은 "주 패스 ∧ 층 0" 이면 TEST = 0x50000 (알파 테스트 없음, Z GEQUAL, Z 씀) — 불투명·알파 테스트 분류 없이
	 * 모든 파트를 GS 식 그대로 (0x44 = (Cs−Cd)·As+Cd, As = 텍스처 α × 정점 α × 색) 아래 (거울 뷰 반사) 위에 블렌드하고 깊이를 쓴다.
	 */
	public void drawLayer0(RenderModel model, Matrix4f world) {
		layer0 = true;
		try {
			basic.set("uModel", world);
			for (RenderModel.Part p : model.parts()) {
				RenderModel.Blend b = p.blend();
				RenderModel.Blend mode = b == RenderModel.Blend.OPAQUE || b == RenderModel.Blend.CUTOUT ? RenderModel.Blend.ALPHA : b;
				MaterialFx fx = p.fx();
				float ft = partTime(fx);
				Texture tex = fx == null ? p.texture() : fx.frame(p.texture(), ft);
				float[] sc = fx == null ? null : fx.uv(0, ft);
				float[] tint = p.tint();
				if (fx != null && fx.anim() != null) {
					tint = tint.clone();
					tint[3] *= fx.opacity(ft, 1f);
				}
				drawLayer(p, tex, mode, tint, sc, env0(p), true, p.litColor());
				if (fx != null && !fx.layers().isEmpty()) {
					glDepthFunc(GL_LEQUAL);
					for (MaterialFx.Layer l : fx.layers()) {
						RenderModel.Blend lb = l.blend() == RenderModel.Blend.OPAQUE || l.blend() == RenderModel.Blend.CUTOUT
								? RenderModel.Blend.ALPHA : l.blend();
						drawLayer(p, fx.frame(l, ft), lb, l.tint(), fx.uv(l.index(), ft), l.env() && p.lit(), false, l.litColor());
					}
					glDepthFunc(GL_LESS);
				}
			}
		} finally {
			layer0 = false;
			basic.set("uTint", new Vector4f(1, 1, 1, 1));
			basic.set("uUvOffset", 0f, 0f);
			basic.set("uFogAdd", 0);
			basic.set("uEnvUv", 0);
		}
	}

	/** {@link #drawLayer0} 중: 반투명 파트도 깊이를 쓴다 (층 0 TEST 0x50000, ZMSK 없음). */
	private boolean layer0;

	/** 모양만 (상태·텍스처·블렌드 변경 없이 모든 파트 메시) — 스텐실 표시·깊이 쓰기용 (SCR_Mirror 반사, Mirrors). */
	public void drawShape(RenderModel model, Matrix4f world) {
		basic.set("uModel", world);
		for (RenderModel.Part p : model.parts()) {
			p.mesh().draw();
		}
	}

	private void drawPasses(RenderModel model, Matrix4f world, int first, int last) {
		basic.set("uModel", world);
		for (int pass = first; pass <= last; pass++) {
			for (RenderModel.Part p : model.parts()) {
				RenderModel.Blend b = p.blend();
				boolean solid = b == RenderModel.Blend.OPAQUE || b == RenderModel.Blend.CUTOUT;
				if (pass == 0 && !solid || pass == 1 && b == RenderModel.Blend.OPAQUE || DrawStage.skip(p.late())) {
					continue;
				}
				MaterialFx fx = p.fx();
				float ft = partTime(fx);
				Texture tex = fx == null ? p.texture() : fx.frame(p.texture(), ft);
				float[] sc = fx == null ? null : fx.uv(0, ft);
				float[] tint = p.tint();
				if (fx != null && fx.anim() != null) {
					tint = tint.clone();
					tint[3] *= fx.opacity(ft, 1f);
				}
				if (b == RenderModel.Blend.CUTOUT) {
					if (pass == 0) {
						basic.set("uAlphaRef", 0.75f);
						drawLayer(p, tex, RenderModel.Blend.OPAQUE, tint, sc, env0(p), true, p.litColor());
						basic.set("uAlphaRef", 0f);
					} else {
						basic.set("uAlphaMax", 0.75f);
						drawLayer(p, tex, RenderModel.Blend.ALPHA, tint, sc, env0(p), true, p.litColor());
						basic.set("uAlphaMax", 0f);
						continue;   // 레이어는 패스 0 에서 이미 그렸다
					}
				} else {
					drawLayer(p, tex, b, tint, sc, env0(p), true, p.litColor());
				}
				if (fx != null && !fx.layers().isEmpty()) {
					glDepthFunc(GL_LEQUAL);
					for (MaterialFx.Layer l : fx.layers()) {
						RenderModel.Blend lb = l.blend() == RenderModel.Blend.OPAQUE || l.blend() == RenderModel.Blend.CUTOUT
								? RenderModel.Blend.ALPHA : l.blend();
						drawLayer(p, fx.frame(l, ft), lb, l.tint(), fx.uv(l.index(), ft), l.env() && p.lit(), false, l.litColor());
					}
					glDepthFunc(GL_LESS);
				}
			}
		}
		basic.set("uTint", new Vector4f(1, 1, 1, 1));
		basic.set("uUvOffset", 0f, 0f);
		basic.set("uFogAdd", 0);
		basic.set("uEnvUv", 0);
	}

	/** 첫 레이어 환경맵 (VU 플래그 2, 법선 있는 파트) — {@link MaterialFx#env0}. */
	private static boolean env0(RenderModel.Part p) {
		return p.fx() != null && p.fx().env0() && p.lit();
	}

	/**
	 * 환경맵 UV 의 뷰 회전 (월드 → 카메라, 3×3): 원작 VU 0x0D9 는 모델·뷰 행렬 행으로 법선을 옮긴다. 셰이더 uEnvView (basic.vert / skinned.vert).
	 * 거울 패스는 반사 행렬을 곱한 뷰.
	 */
	public static org.joml.Matrix3f envView(Camera cam, org.joml.Matrix3f out) {
		Matrix4f v = new Matrix4f().lookAt(cam.eye, cam.target, cam.up);
		if (cam.mirror != null) {
			v.mul(cam.mirror);
		}
		return v.get3x3(out);
	}

	/** @param base 머티리얼 첫 레이어 (노멀맵은 이것에만, 44 문서) */
	private void drawLayer(RenderModel.Part p, Texture tex, RenderModel.Blend blend, float[] t, float[] scroll, boolean env, boolean base,
						   float[] litColor) {
		// 동적 라이트 경로의 머티리얼 색 M (qw 76, 47 §3.1) — uLgtOn = 0 이면 셰이더가 쓰지 않는다
		basic.set("uLgtMat", litColor[0], litColor[1], litColor[2]);
		basic.set("uLgtAdd", litColor[3], litColor[4], litColor[5]);
		boolean opaque = blend == RenderModel.Blend.OPAQUE;
		if (!opaque) {
			glEnable(GL_BLEND);
			glDepthMask(layer0);
			switch (blend) {
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
		}
		if (tex != null) {
			tex.bind(0);
			basic.set("uUseTex", 1);
		} else {
			white.bind(0);
			basic.set("uUseTex", 0);
		}
		basic.set("uTint", new Vector4f(t[0], t[1], t[2], t[3]));
		basic.set("uLit", p.lit() ? 1 : 0);
		basic.set("uFogAdd", blend == RenderModel.Blend.ADD || blend == RenderModel.Blend.SUB ? 1 : 0);
		basic.set("uEnvUv", env ? 1 : 0);
		if (scroll != null) {
			basic.set("uUvOffset", scroll[0], scroll[1]);
		} else {
			basic.set("uUvOffset", 0f, 0f);
		}
		boolean nm = base && !env && tex != null && (opaque || blend == RenderModel.Blend.ALPHA) && bindNormalMap(basic, tex);
		p.mesh().draw();
		if (nm) {
			basic.set("uNmOn", 0);
		}
		if (!opaque) {
			glDisable(GL_BLEND);
			glDepthMask(true);
			glBlendEquation(GL_FUNC_ADD);
		}
	}

	/**
	 * 노멀맵 켬이면 tex 의 노멀맵을 유닛 2 에 묶고 uNmOn = 1 (44 문서). 꺼져 있거나 맵이 없으면 아무것도 안 하고 false —
	 * 꺼짐 상태의 GL 호출·유니폼은 기능 추가 전과 같다. 호출한 쪽이 그린 뒤 uNmOn 을 0 으로 돌린다.
	 */
	public static boolean bindNormalMap(Shader sh, Texture tex) {
		if (!NormalMaps.enabled() || tex == null) {
			return false;
		}
		Texture nm = tex.normalMap();
		if (nm == null) {
			return false;
		}
		nm.bind(NormalMaps.UNIT);
		glActiveTexture(GL_TEXTURE0);
		sh.set("uNormalMap", NormalMaps.UNIT);
		sh.set("uNmDetail", NormalMaps.DETAIL_K, NormalMaps.DETAIL_LO, NormalMaps.DETAIL_HI);
		sh.set("uNmOn", 1);
		return true;
	}

	/**
	 * 화면 전체를 색 (r, g, b) · 불투명도 a 로 덮는다 — 스크립트 FadeOut/FadeIn (원작 0x2C8F50 색 사각형 노드 0x121688, 43 §16).
	 * 정점색이 검정인 {@code fullscreen} 대신 흰 정점 사각형에 색을 곱한다.
	 */
	public void overlay(float r, float g, float b, float a) {
		if (a <= 0f) {
			return;
		}
		if (fullscreenWhite == null) {
			fullscreenWhite = new MeshBuilder()
					.vertex(-1, -1, 0, 0, 0, 1, 0, 0, 1, 1, 1, 1)
					.vertex(1, -1, 0, 0, 0, 1, 1, 0, 1, 1, 1, 1)
					.vertex(1, 1, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1)
					.vertex(-1, -1, 0, 0, 0, 1, 0, 0, 1, 1, 1, 1)
					.vertex(1, 1, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1)
					.vertex(-1, 1, 0, 0, 0, 1, 0, 1, 1, 1, 1, 1)
					.build(GL_TRIANGLES);
		}
		glDisable(GL_DEPTH_TEST);
		glEnable(GL_BLEND);
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
		basic.use();
		basic.set("uViewProj", identity);
		basic.set("uModel", identity);
		basic.set("uUseTex", 0);
		basic.set("uLit", 0);
		basic.set("uFogMode", 0);
		basic.set("uTint", new Vector4f(r, g, b, Math.min(a, 1f)));
		fullscreenWhite.draw();
		basic.set("uTint", new Vector4f(1, 1, 1, 1));
		glDisable(GL_BLEND);
		glEnable(GL_DEPTH_TEST);
	}

	private Mesh fullscreenWhite;

	public void endFrame(float fade) {
		if (fade <= 0f) {
			return;
		}
		glDisable(GL_DEPTH_TEST);
		glEnable(GL_BLEND);
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
		basic.use();
		basic.set("uViewProj", identity);
		basic.set("uModel", identity);
		basic.set("uUseTex", 0);
		basic.set("uLit", 0);
		basic.set("uTint", new Vector4f(1, 1, 1, fade));
		fullscreen.draw();
		glDisable(GL_BLEND);
		glEnable(GL_DEPTH_TEST);
	}

	private GpuWarmup warmup;

	/** 페이드 동안 새 레벨을 한 번씩 그려 둘 1×1 화면 밖 FBO (48 문서 §9.7). */
	public GpuWarmup warmup() {
		if (warmup == null) {
			warmup = new GpuWarmup();
		}
		return warmup;
	}

	@Override
	public void close() {
		if (warmup != null) {
			warmup.close();
		}
		fullscreen.close();
		if (fullscreenWhite != null) {
			fullscreenWhite.close();
		}
		white.close();
		basic.close();
	}
}
