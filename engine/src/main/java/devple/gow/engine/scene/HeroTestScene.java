package devple.gow.engine.scene;

import devple.gow.engine.anim.Animator;
import devple.gow.engine.anim.HeroController;
import devple.gow.engine.asset.AnimatedModel;
import devple.gow.engine.asset.WadAssets;
import devple.gow.engine.core.Engine;
import devple.gow.engine.game.Tweaks;
import devple.gow.engine.render.Camera;
import devple.gow.engine.render.Mesh;
import devple.gow.engine.render.MeshBuilder;
import devple.gow.engine.render.Shader;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.anim.Skeleton;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

import static org.lwjgl.opengl.GL41C.GL_LINES;

/** 평지에서 크레토스 조작 (이동 컨트롤러 + 추적 카메라). 레벨/전투 연결 전 테스트용. */
public final class HeroTestScene implements Scene {

	private final Path rom;
	private final Camera cam = new Camera();
	private final Matrix4f world = new Matrix4f();
	private WadAssets hero;
	private Shader skinned;
	private AnimatedModel model;
	private HeroController ctl;
	private Mesh grid;
	private float camYaw;
	/** 자동 검증용: 시작 후 강제로 앞으로 달리게 하는 시간 (초). */
	private float autoRun;

	public HeroTestScene(Path rom, float autoRun) {
		this.rom = rom;
		this.autoRun = autoRun;
	}

	@Override
	public void enter(Engine e) {
		try {
			hero = new WadAssets(rom.resolve("R_HERO0.WAD"));
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		Tweaks tw = new Tweaks();
		tw.load(hero.wad());
		Skeleton sk = Skeleton.loadAll(hero.wad()).stream().filter(s -> s.name.equals("hero")).findFirst().orElseThrow();
		skinned = new Shader("skinned.vert", "basic.frag");
		model = new AnimatedModel(hero, hero.gowModel("MDL_hero"), sk, skinned);
		ctl = new HeroController(new Animator(sk, AnimationSet.load(hero.wad(), "ANM_hero"), tw, "/Animation/goHero/"));
		MeshBuilder g = new MeshBuilder();
		for (int i = -40; i <= 40; i++) {
			float c = i == 0 ? 0.6f : 0.25f;
			g.line(i * 20, 0, -800, i * 20, 0, 800, c, c, c);
			g.line(-800, 0, i * 20, 800, 0, i * 20, c, c, c);
		}
		grid = g.build(GL_LINES);
	}

	@Override
	public void update(Engine e, float dt) {
		camYaw -= e.input().rightX() * dt * 2;
		if (autoRun > 0) {
			autoRun -= dt;
			ctl.update(new AutoInput(e), camYaw, dt);
		} else {
			ctl.update(e.input(), camYaw, dt);
		}
	}

	@Override
	public void render(Engine e, float alpha) {
		ctl.world(world, alpha);
		Vector3f p = world.getTranslation(new Vector3f());
		cam.near = 1;
		cam.far = 8000;
		cam.target.set(p.x, p.y + 25, p.z);
		cam.eye.set(p.x + (float) Math.sin(camYaw) * 110, p.y + 60, p.z + (float) Math.cos(camYaw) * 110);
		e.renderer().begin3d(cam);
		e.renderer().draw(grid, new Matrix4f(), false);
		model.draw(cam, e.window().aspect(), world, ctl.animator().pose(), e.renderer().white());
	}

	@Override
	public void exit(Engine e) {
		model.close();
		skinned.close();
		grid.close();
		hero.close();
	}

	/** 스틱을 오른쪽 앞으로 가득 민 입력 (스크린샷 검증용). */
	private static final class AutoInput extends devple.gow.engine.input.Input {
		AutoInput(Engine e) {
			super(e.window().handle());
		}

		@Override
		public float leftX() {
			return 0.7f;
		}

		@Override
		public float leftY() {
			return 0.7f;
		}
	}
}
