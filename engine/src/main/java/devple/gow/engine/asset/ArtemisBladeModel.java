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
 * 아르테미스의 검 렌더 ({@code R_BRSRK<단계>.WAD} 의 {@code berserkblade} 스켈레톤 + {@code MDL_berserkBlade}).
 * 오브젝트 루트 = 히어로 오른손 무기 로케이터 {@code rWeapOH} (BER* 애니가 이 관절로 검을 움직인다 [추정]).
 */
public final class ArtemisBladeModel implements AutoCloseable {

	private final WadAssets assets;
	private final AnimatedModel model;
	private final Pose idle;

	private ArtemisBladeModel(WadAssets assets, Skeleton sk, AnimatedModel model) {
		this.assets = assets;
		this.model = model;
		model.layer3 = true; // 무기 궤적 객체 0x12DE70 도 0x150738(모델, 2) → 층 3 [추정: 아르테미스의 검이 이 경로]
		this.idle = Pose.idle(sk);
	}

	/** 없으면 null. */
	public static ArtemisBladeModel load(Path rom, int stage, Shader skinned) throws IOException {
		Path p = rom.resolve("R_BRSRK" + Math.max(0, Math.min(2, stage)) + ".WAD");
		if (!Files.exists(p)) {
			return null;
		}
		WadAssets a = new WadAssets(p);
		Skeleton sk = Skeleton.loadAll(a.wad()).stream().filter(s -> s.name.equalsIgnoreCase("berserkblade")).findFirst().orElse(null);
		if (sk == null || a.gowModel("MDL_berserkBlade") == null) {
			a.close();
			return null;
		}
		return new ArtemisBladeModel(a, sk, new AnimatedModel(a, a.requireModel("MDL_berserkBlade"), sk, skinned));
	}

	public void draw(Camera cam, float aspect, Matrix4f heroWorld, Skeleton heroSk, float[][] heroJoints, Texture white) {
		int i = heroSk.indexOf("rWeapOH");
		if (i < 0) {
			return;
		}
		Matrix4f w = new Matrix4f(heroWorld).mul(new Matrix4f().set(heroJoints[i]));
		model.draw(cam, aspect, w, idle, white);
	}

	@Override
	public void close() {
		model.close();
		assets.close();
	}
}
