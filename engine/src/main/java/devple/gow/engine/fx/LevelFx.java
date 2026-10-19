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

	public LevelFx(Engine e, WadAssets assets, LevelData data, List<LevelScene.Instance> instances) {
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
		LevelEmitters.build(data, assets, particles);
		for (EnvParams.Weather w : p.weathers) {
			ParticleSystem.Emitter em = particles.add(Weather.def(w, assets), 0, 0, 0);
			em.followCamera = true;
			em.ahead = em.def.box()[0] - 10;
		}
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
			update(e, (now - lastRenderNanos) / 1e9f, cam.eye);
		}
		updatedSinceRender = false;
		lastRenderNanos = now;
		if (env.enabled()) {
			env.applyCamera(cam);
		}
		e.renderer().setFxTime(env.time());
	}

	/** 하늘(sky=true) / 지오메트리 그리기 직전: 안개 유니폼. */
	public void beginWorld(Engine e, Camera cam, boolean sky) {
		env.setFramebufferWidth(e.window().width());
		env.applyFog(e.renderer().basic(), cam, e.window().height());
		env.setBackground(e.renderer().basic(), sky, e.window().height());
	}

	/** 반투명 효과 패스 (물, 비, 파티클). */
	public void renderEffects(Engine e, Camera cam) {
		particles.setForward(new Vector3f(cam.target).sub(cam.eye));
		if (env.enabled()) {
			for (WaterSurface w : waters) {
				w.render(e.renderer().basic(), e.renderer().white());
			}
			particles.render(cam, e.window().aspect());
			e.renderer().basic().use();
		}
	}

	@Override
	public void close() {
		particles.close();
		waters.forEach(WaterSurface::close);
	}
}
