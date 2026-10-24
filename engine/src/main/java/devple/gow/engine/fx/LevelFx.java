package devple.gow.engine.fx;

import devple.gow.engine.asset.WadAssets;
import devple.gow.engine.core.Engine;
import devple.gow.engine.level.LevelScene;
import devple.gow.engine.render.Camera;
import devple.gow.tools.fx.EnvParams;
import devple.gow.tools.level.LevelData;
import org.joml.Vector3f;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.lwjgl.glfw.GLFW.*;

/**
 * 레벨 씬의 환경/이펙트 묶음 (31 문서). {@link LevelScene} 이 호출하는 훅:
 * <pre>
 * enter   new LevelFx(e, assets, data, instances)
 * update  fx.update(e, dt, eye)
 * render  fx.beforeRender(e, cam) → begin3d → fx.beginWorld(e, cam, true) 하늘 → fx.beginWorld(e, cam, false) 지오메트리
 *         → fx.renderEffects(e, cam) (물, 비, 파티클) → FxEnvironment.disableFog
 * </pre>
 * 4 키 = 환경 효과 켜기/끄기 (비교용).
 */
public final class LevelFx implements AutoCloseable {

	private final FxEnvironment env;
	/** 효과용 볼륨 메시 (안개 상자, 비 영역 상자): 원작은 스크립트 대상이라 그리지 않는다. */
	private final Set<String> hiddenInstances = new HashSet<>();
	private boolean keyPrev;
	private final ParticleSystem particles = new ParticleSystem(12000);
	private final WadAssets assets;
	private boolean prewarmed;
	private final java.util.List<WaterSurface> waters = new java.util.ArrayList<>();

	private final java.nio.file.Path wadPath;
	private WadAssets permk;

	public LevelFx(Engine e, WadAssets assets, LevelData data, List<LevelScene.Instance> instances) {
		this(e, assets, data, instances, null);
	}

	/** @param wadPath 레벨 WAD 경로 (같은 폴더의 R_PERMK 에서 상주 날씨 GO 를 찾는다; null 가능) */
	public LevelFx(Engine e, WadAssets assets, LevelData data, List<LevelScene.Instance> instances, java.nio.file.Path wadPath) {
		this(e, assets, data, instances, wadPath, java.util.Set.of());
	}

	/** @param skyOwners 하늘 GO 이름 (소문자) — 그 이미터는 월드 고정이 아니라 하늘 공간에서 돈다 ({@link LevelEmitters#build} 에서 뺀다) */
	public LevelFx(Engine e, WadAssets assets, LevelData data, List<LevelScene.Instance> instances, java.nio.file.Path wadPath,
				   java.util.Set<String> skyOwners) {
		this.wadPath = wadPath;
		env = new FxEnvironment(assets, data);
		EnvParams p = env.params();
		for (EnvParams.Fog f : p.fogs) {
			hiddenInstances.add(f.owner());
		}
		for (EnvParams.LayeredFog f : p.layeredFogs) {
			hiddenInstances.add(f.owner());
		}
		for (EnvParams.Weather w : p.weathers) {
			hiddenInstances.add(w.owner());
		}
		System.out.printf(Locale.ROOT, "  env 숨김(효과 볼륨): %s%n", hiddenInstances);
		this.assets = assets;
		if ("off".equals(System.getenv("GOW_FX"))) {
			env.setEnabled(false);   // 비교 스크린샷용: GOW_FX=off
		}
		for (EnvParams.Water w : p.waters) {
			float[] c = ownerPosition(data, w.owner());
			if (c != null) {
				String t = assets.materialTexture("MAT_" + w.material());
				waters.add(new WaterSurface(w, c, t == null ? null : assets.texture(t)));
			}
		}
		LevelEmitters.build(data, assets, particles, skyOwners);
		for (EnvParams.Weather w : p.weathers) {
			float[] o = ownerPosition(data, w.owner());
			Weather.Built b = Weather.build(w, assets, o, this::permk);
			ParticleSystem.Emitter em = particles.add(b.def(), o == null ? 0 : o[0], o == null ? 0 : o[1], o == null ? 0 : o[2]);
			em.followCamera = true;
			// 원작 0x21CF80: 정육면체 중심 = 카메라 + 시선·(셀×8 − near)
			em.ahead = Weather.cell(w.type()) * 8 - Weather.NEAR;
			em.weather = b.sim();
			System.out.printf(Locale.ROOT, "  env 날씨 %s %s: %s%n", w.owner(), w.typeName(),
					b.sim() == null ? "근사 이미터" : String.format(Locale.ROOT, "%s ×%d, 나이 %.2f, 방향 %s", b.def().name(), b.sim().count,
							b.sim().age, java.util.Arrays.toString(b.sim().dir)));
		}
	}

	/** 상주 R_PERMK (날씨 GO gosnow/goplankton/godust/goembers/gogroundfog) — 처음 필요할 때 읽는다. */
	private WadAssets permk() {
		if (permk == null && wadPath != null) {
			java.nio.file.Path pk = devple.gow.tools.content.Content.sibling(wadPath, devple.gow.tools.content.Region.current().perm());
			if (java.nio.file.Files.exists(pk)) {
				try {
					permk = new WadAssets(pk);
				} catch (java.io.IOException ex) {
					System.out.println("  R_PERMK 읽기 실패: " + ex.getMessage());
				}
			}
		}
		return permk;
	}

	/** 소유 GO 의 관절 0 월드 위치 (없으면 인스턴스 위치, 그것도 없으면 null). */
	static float[] ownerPosition(LevelData data, String owner) {
		for (devple.gow.tools.level.LevelLayout.Placement pl : data.layout.placements()) {
			if (pl.instance().name().equals(owner)) {
				float[] j = pl.jointWorld()[0];
				return new float[]{j[12], j[13], j[14]};
			}
		}
		return data.layout.instances.stream().filter(g -> g.name().equals(owner)).map(g -> g.pos()).findFirst().orElse(null);
	}

	/**
	 * 가시 칸 컨텍스트로 숨은 인스턴스의 배치 이미터를 끈다 (원작 0x134980 은 PRT 0x13 항목의 뷰 마스크를 끈다 — 여기서는 생성만 멈춘다 [근사]).
	 */
	public void setContextHidden(Set<String> hidden) {
		for (ParticleSystem.Emitter em : particles.emitters()) {
			if (em.owner != null) {
				em.active = !hidden.contains(em.owner);
			}
		}
	}

	public FxEnvironment env() {
		return env;
	}

	public boolean hidden(LevelScene.Instance i) {
		return env.enabled() && hiddenInstances.contains(i.name());
	}

	private boolean updatedSinceRender;
	private long lastRenderNanos;

	public void update(Engine e, float dt, Vector3f eye) {
		updatedSinceRender = true;
		boolean down = glfwGetKey(e.window().handle(), GLFW_KEY_4) == GLFW_PRESS;
		if (down && !keyPrev) {
			env.setEnabled(!env.enabled());
			System.out.println("환경 효과 " + (env.enabled() ? "켬" : "끔"));
		}
		keyPrev = down;
		env.update(dt, eye);
		if (!prewarmed) {
			particles.prewarm(2f, eye);
			prewarmed = true;
		}
		particles.update(dt, eye);
		for (WaterSurface w : waters) {
			w.update(dt, eye);
		}
	}

	public ParticleSystem particles() {
		return particles;
	}

	/** begin3d 전에: FarClip 반영, 머티리얼 애니메이션 시간. */
	public void beforeRender(Engine e, Camera cam) {
		// 통합 게임 씬은 LevelScene.update 를 부르지 않는다 → 렌더 간격으로 스스로 진행
		long now = System.nanoTime();
		if (!updatedSinceRender && lastRenderNanos != 0) {
			update(e, Engine.LOCKSTEP ? Engine.TICK : (now - lastRenderNanos) / 1e9f, cam.eye); // 검증 lockstep 이면 고정 간격
		}
		updatedSinceRender = false;
		lastRenderNanos = now;
		if (env.enabled()) {
			env.applyCamera(cam);
		}
		e.renderer().setFxTime(env.time());
	}

	/** 배경 사각형 (NDC 전체, 정점색 = 화면 지움 색). */
	private devple.gow.engine.render.Mesh bgQuad;

	/**
	 * 지오메트리가 없는 배경 화소에 안개색 (원작 안개색 스프라이트 0x127000 은 Z 가 지워진 화소에도 그린다, 16 문서 §1.2).
	 * DEST01 모래 폭풍 (Fog_A 1) 처럼 하늘이 없는 레벨의 먼 배경이 검게 남지 않는다. 하늘·지오메트리 전에 부른다.
	 */
	public void drawBackground(Engine e, Camera cam) {
		float k = env.backgroundFactor();
		if (k <= 0 || NO_BG) {
			return;
		}
		if (bgQuad == null) {
			float r = devple.gow.engine.render.Renderer.CLEAR_R, g = devple.gow.engine.render.Renderer.CLEAR_G,
					b = devple.gow.engine.render.Renderer.CLEAR_B; // 원작 지움 색 (검정, 0x29CF64)
			float[] v = new float[4 * devple.gow.engine.render.Mesh.STRIDE];
			float[][] xy = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
			for (int i = 0; i < 4; i++) {
				int o = i * devple.gow.engine.render.Mesh.STRIDE;
				v[o] = xy[i][0];
				v[o + 1] = xy[i][1];
				v[o + 2] = 0.999f;
				v[o + 4] = 1;
				v[o + 8] = r;
				v[o + 9] = g;
				v[o + 10] = b;
				v[o + 11] = 1;
			}
			bgQuad = new devple.gow.engine.render.Mesh(v, new int[]{0, 1, 2, 0, 2, 3}, org.lwjgl.opengl.GL41C.GL_TRIANGLES);
		}
		var s = e.renderer().basic();
		s.use();
		env.setFramebufferWidth(e.window().width());
		env.applyBackgroundFog(s, cam, e.window().height(), k);
		org.joml.Matrix4f id = new org.joml.Matrix4f();
		s.set("uViewProj", id);
		s.set("uModel", id);
		s.set("uTint", new org.joml.Vector4f(1, 1, 1, 1));
		s.set("uUseTex", 0);
		s.set("uLit", 0);
		s.set("uAlphaRef", 0f);
		s.set("uAlphaMax", 0f);
		s.set("uFogAdd", 0);
		org.lwjgl.opengl.GL41C.glDisable(org.lwjgl.opengl.GL41C.GL_DEPTH_TEST);
		org.lwjgl.opengl.GL41C.glDepthMask(false);
		boolean blend = org.lwjgl.opengl.GL41C.glIsEnabled(org.lwjgl.opengl.GL41C.GL_BLEND);
		org.lwjgl.opengl.GL41C.glDisable(org.lwjgl.opengl.GL41C.GL_BLEND);
		bgQuad.draw();
		if (blend) {
			org.lwjgl.opengl.GL41C.glEnable(org.lwjgl.opengl.GL41C.GL_BLEND);
		}
		org.lwjgl.opengl.GL41C.glDepthMask(true);
		org.lwjgl.opengl.GL41C.glEnable(org.lwjgl.opengl.GL41C.GL_DEPTH_TEST);
		s.set("uViewProj", cam.viewProj(e.window().aspect()));
	}

	/** 비교용: GOW_FOGBG=off 면 배경 안개를 그리지 않는다 (이전 동작). */
	private static final boolean NO_BG = "off".equals(System.getenv("GOW_FOGBG"));

	/** 하늘(sky=true) / 지오메트리 그리기 직전: 안개 유니폼. */
	public void beginWorld(Engine e, Camera cam, boolean sky) {
		env.setFramebufferWidth(e.window().width());
		env.applyFog(e.renderer().basic(), cam, e.window().height());
		env.setBackground(e.renderer().basic(), sky, e.window().height());
	}

	private long lastNeighbourNanos;

	/**
	 * 다른 슬롯 레벨로 그릴 때 ({@code LevelScene.renderNeighbourEffects}): 그 레벨 GO 의 FXC 이미터 파티클만 렌더 간격으로 진행하고 그린다 — 안개·FarClip·
	 * 재질 시간은 현재 레벨 것 그대로. 원작은 두 슬롯 GO 가 한 세계에서 돈다 (ATHN05B 에서 본 05BB 홀 끝 횃불 불꽃, 43 §22) [확실: 두 슬롯 상주].
	 */
	public void renderNeighbourParticles(Engine e, Camera cam) {
		long now = System.nanoTime();
		float dt = lastNeighbourNanos == 0 ? 0 : Engine.LOCKSTEP ? Engine.TICK : Math.min(0.1f, (now - lastNeighbourNanos) / 1e9f);
		lastNeighbourNanos = now;
		if (!prewarmed) {
			particles.prewarm(2f, cam.eye);
			prewarmed = true;
		}
		particles.update(dt, cam.eye);
		if (env.enabled()) {
			particles.setForward(new Vector3f(cam.target).sub(cam.eye));
			particles.render(cam, e.window().aspect());
			e.renderer().basic().use();
		}
	}

	/**
	 * 반투명 효과 패스 (물, 비, 파티클). 둘 다 원작 안개 앞 (층 2) — 장면 깊이 안개 ({@link SceneFog}). 물은 원작 SCR_Water 가 층 2 · 층 3 모델을
	 * 하나씩 만든다 (0x219548 +0xD4 = 2, 0x2197C0 = 3) — 엔진 반사 수면은 층 2 쪽으로 본다 [근사]. 예전엔 게임 경로에서 안개가 꺼진 뒤라 안개 없음.
	 */
	public void renderEffects(Engine e, Camera cam) {
		particles.setForward(new Vector3f(cam.target).sub(cam.eye));
		if (env.enabled()) {
			if (!waters.isEmpty()) {
				devple.gow.engine.render.Shader basic = e.renderer().basic();
				basic.use(); // 유니폼은 지금 프로그램에 (뷰어 경로는 앞의 스키닝 그리기가 다른 프로그램을 남긴다)
				SceneFog sf = SceneFog.current();
				boolean fog = sf != null && sf.applyModel(basic);
				basic.set("uFogScene", fog ? 1 : 0);
				for (WaterSurface w : waters) {
					w.render(basic, e.renderer().white());
				}
				basic.set("uFogScene", 0);
				if (fog) {
					FxEnvironment.disableFog(basic);
				}
			}
			particles.render(cam, e.window().aspect());
			e.renderer().basic().use();
		}
	}

	@Override
	public void close() {
		particles.close();
		waters.forEach(WaterSurface::close);
		if (bgQuad != null) {
			bgQuad.close();
		}
		if (permk != null) {
			permk.close();
		}
	}
}
