package devple.gow.engine.scene;

import devple.gow.engine.asset.AnimatedModel;
import devple.gow.engine.asset.WadAssets;
import devple.gow.engine.core.Engine;
import devple.gow.engine.input.PadButton;
import devple.gow.engine.render.Camera;
import devple.gow.engine.render.Shader;
import devple.gow.tools.anim.Animation;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.anim.Pose;
import devple.gow.tools.anim.Skeleton;
import org.joml.Matrix4f;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 애니메이션 뷰어: R_HERO 등에서 스켈레톤 + ANM 세트를 읽어 GPU 스키닝으로 재생.
 * □/△ = 이전/다음 애니메이션, 좌스틱 = 회전/줌.
 */
public final class AnimViewerScene implements Scene {

	private final Path wadPath;
	private final String mdlName;
	private final String skelName;
	private final String anmName;
	private final String startAnim;
	private final Camera cam = new Camera();
	private final Matrix4f world = new Matrix4f();
	private WadAssets assets;
	private AnimatedModel model;
	private Shader shader;
	private List<Animation> anims;
	private int index;
	private float time;
	private float yaw = 0.5f;
	private float dist = 0.7f;

	public AnimViewerScene(Path wadPath, String mdlName, String skelName, String anmName, String startAnim) {
		this.wadPath = wadPath;
		this.mdlName = mdlName;
		this.skelName = skelName;
		this.anmName = anmName;
		this.startAnim = startAnim;
	}

	@Override
	public void enter(Engine e) {
		try {
			assets = new WadAssets(wadPath);
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		Skeleton sk = Skeleton.loadAll(assets.wad()).stream().filter(s -> s.name.equals(skelName)).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("스켈레톤 없음: " + skelName));
		shader = new Shader("skinned.vert", "basic.frag");
		model = new AnimatedModel(assets, assets.requireModel(mdlName), sk, shader);
		AnimationSet set = AnimationSet.load(assets.wad(), anmName);
		anims = set.groups.stream().flatMap(g -> g.acts.stream()).filter(Animation::hasSkeletalData).toList();
		for (int i = 0; i < anims.size(); i++) {
			if (anims.get(i).name.equals(startAnim)) {
				index = i;
			}
		}
		System.out.println("애니메이션 " + anims.size() + "개, 시작: " + anims.get(index).name);
	}

	@Override
	public void update(Engine e, float dt) {
		time += dt;
		if (e.input().pressed(PadButton.TRIANGLE)) {
			index = (index + 1) % anims.size();
			time = 0;
			System.out.println(anims.get(index).name);
		}
		if (e.input().pressed(PadButton.SQUARE)) {
			index = (index + anims.size() - 1) % anims.size();
			time = 0;
			System.out.println(anims.get(index).name);
		}
		yaw += e.input().leftX() * dt * 2;
		dist = Math.max(0.3f, dist - e.input().leftY() * dt);
	}

	@Override
	public void render(Engine e, float alpha) {
		Animation a = anims.get(index);
		float t = a.duration > 0 ? time % a.duration : time;
		Pose pose = a.sample(model.skeleton(), t, true);
		model.hiddenJoints = a.hiddenJoints(model.skeleton(), t); // u8 가시성 트랙 (41 문서 §2)
		float h = 40f;
		cam.near = 0.5f;
		cam.far = 5000;
		cam.target.set(0, h * 0.5f, 0);
		cam.eye.set((float) Math.sin(yaw) * h * 2 * dist, h * 0.7f, (float) Math.cos(yaw) * h * 2 * dist);
		model.draw(cam, e.window().aspect(), world.identity(), pose, e.renderer().white());
		model.hiddenJoints = null;
	}

	@Override
	public void exit(Engine e) {
		model.close();
		shader.close();
		assets.close();
	}
}
