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

	public void beginFrame() {
		glViewport(0, 0, engine.window().width(), engine.window().height());
		glClearColor(0.05f, 0.04f, 0.04f, 1f);
		glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
	}

	/** 3D 드로우 공통 설정. */
	public void begin3d(Camera cam) {
		basic.use();
		white.bind(0);
		basic.set("uTex", 0);
		basic.set("uViewProj", cam.viewProj(engine.window().aspect()));
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
				Texture tex = fx == null ? p.texture() : fx.frame(p.texture(), fxTime);
				float[] sc = fx == null ? null : fx.uv(0, fxTime);
				float[] tint = p.tint();
				if (fx != null && fx.anim() != null) {
					tint = tint.clone();
					tint[3] *= fx.opacity(fxTime, 1f);
				}
				drawLayer(p, tex, mode, tint, sc, false, true);
				if (fx != null && !fx.layers().isEmpty()) {
					glDepthFunc(GL_LEQUAL);
					for (MaterialFx.Layer l : fx.layers()) {
						RenderModel.Blend lb = l.blend() == RenderModel.Blend.OPAQUE || l.blend() == RenderModel.Blend.CUTOUT
								? RenderModel.Blend.ALPHA : l.blend();
						drawLayer(p, fx.frame(l, fxTime), lb, l.tint(), fx.uv(l.index(), fxTime), l.env() && p.lit(), false);
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
				if (pass == 0 && !solid || pass == 1 && b == RenderModel.Blend.OPAQUE) {
					continue;
				}
				MaterialFx fx = p.fx();
				Texture tex = fx == null ? p.texture() : fx.frame(p.texture(), fxTime);
				float[] sc = fx == null ? null : fx.uv(0, fxTime);
				float[] tint = p.tint();
				if (fx != null && fx.anim() != null) {
					tint = tint.clone();
					tint[3] *= fx.opacity(fxTime, 1f);
				}
				if (b == RenderModel.Blend.CUTOUT) {
					if (pass == 0) {
						basic.set("uAlphaRef", 0.75f);
						drawLayer(p, tex, RenderModel.Blend.OPAQUE, tint, sc, false, true);
						basic.set("uAlphaRef", 0f);
					} else {
						basic.set("uAlphaMax", 0.75f);
						drawLayer(p, tex, RenderModel.Blend.ALPHA, tint, sc, false, true);
						basic.set("uAlphaMax", 0f);
						continue;   // 레이어는 패스 0 에서 이미 그렸다
					}
				} else {
					drawLayer(p, tex, b, tint, sc, false, true);
				}
				if (fx != null && !fx.layers().isEmpty()) {
					glDepthFunc(GL_LEQUAL);
					for (MaterialFx.Layer l : fx.layers()) {
						RenderModel.Blend lb = l.blend() == RenderModel.Blend.OPAQUE || l.blend() == RenderModel.Blend.CUTOUT
								? RenderModel.Blend.ALPHA : l.blend();
						drawLayer(p, fx.frame(l, fxTime), lb, l.tint(), fx.uv(l.index(), fxTime), l.env() && p.lit(), false);
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

	/** @param base 머티리얼 첫 레이어 (노멀맵은 이것에만, 44 문서) */
	private void drawLayer(RenderModel.Part p, Texture tex, RenderModel.Blend blend, float[] t, float[] scroll, boolean env, boolean base) {
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

	@Override
	public void close() {
		fullscreen.close();
		white.close();
		basic.close();
	}
}
