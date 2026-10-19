package devple.gow.engine.asset;

import devple.gow.engine.render.Camera;
import devple.gow.engine.render.Shader;
import devple.gow.engine.render.Texture;
import devple.gow.tools.anim.Pose;
import devple.gow.tools.anim.Skeleton;
import org.joml.Matrix4f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 혼돈의 블레이드 두 자루 렌더 (R_WPN&lt;costume&gt;_&lt;level&gt;.WAD 의 {@code maiblade} + {@code MDL_MAIBlade}).
 * 블레이드 오브젝트 루트 = 히어로 로케이터 {@code lWeapOH}/{@code rWeapOH} 월드 행렬 (05 문서 2.8, 34 문서).
 */
public final class BladeModels implements AutoCloseable {

	private final WadAssets assets;
	private final Skeleton skeleton;
	private final AnimatedModel model;
	private final Pose idle;

	private BladeModels(WadAssets assets, Skeleton sk, AnimatedModel model) {
		this.assets = assets;
		this.skeleton = sk;
		this.model = model;
		this.idle = Pose.idle(sk);
	}

	/** 없으면 null. */
	public static BladeModels load(Path rom, int costume, int level, Shader skinned) throws IOException {
		Path p = rom.resolve("R_WPN" + costume + "_" + Math.max(0, Math.min(5, level)) + ".WAD");
		if (!Files.exists(p)) {
			return null;
		}
		WadAssets a = new WadAssets(p);
		Skeleton sk = Skeleton.loadAll(a.wad()).stream().filter(s -> s.name.equalsIgnoreCase("maiblade")).findFirst().orElse(null);
		if (sk == null || a.gowModel("MDL_MAIBlade") == null) {
			a.close();
			return null;
		}
		return new BladeModels(a, sk, new AnimatedModel(a, a.requireModel("MDL_MAIBlade"), sk, skinned));
	}

	/**
	 * @param heroWorld 히어로 오브젝트 렌더 행렬
	 * @param heroJoints 히어로 관절 월드 행렬 (행벡터, 모델 공간)
	 */
	public void draw(Camera cam, float aspect, Matrix4f heroWorld, Skeleton heroSk, float[][] heroJoints, Texture white) {
		for (String j : new String[]{"lWeapOH", "rWeapOH"}) {
			int i = heroSk.indexOf(j);
			if (i < 0) {
				continue;
			}
			// 행벡터 행 우선 float[16] 을 JOML(열 우선)로 읽으면 열벡터 규약 행렬이 된다
			Matrix4f w = new Matrix4f(heroWorld).mul(new Matrix4f().set(heroJoints[i]));
			model.draw(cam, aspect, w, idle, white);
		}
	}

	public Skeleton skeleton() {
		return skeleton;
	}

	@Override
	public void close() {
		model.close();
		assets.close();
	}
}
