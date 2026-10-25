package devple.gow.engine.asset;

import devple.gow.tools.content.Content;
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
 * 오브젝트 루트 = 히어로 오른손 관절 {@code rWeapIH} — 원작 장착 0x19C240 이 검 GO 를 Hash127Upper("RWEAPIH") 관절에 붙인다
 * (0x19C388 → 0x138360 관절 찾기 → 0x131460 부착) [확실]. ~~rWeapOH~~ 는 블레이드 사슬 로케이터라 BER* 자세에서 손과 5–15 · 축 40° 넘게 어긋났다 (43 §34).
 */
public final class ArtemisBladeModel implements AutoCloseable {

	private final WadAssets assets;
	private final AnimatedModel model;
	private final Pose idle;

	private ArtemisBladeModel(WadAssets assets, Skeleton sk, AnimatedModel model) {
		this.assets = assets;
		this.model = model;
		// 층 2 (43 §31): R_BRSRK 머티리얼은 모두 flags2 0x100 (반투명 단계, 그림자 합성 뒤) — 무기 궤적 0x12DE70 의 0x150738(…, 2) → 층 3 은
		// 0x23EB58 로 만든 궤적 띠 인스턴스 (0x12E118 – 0x12E1F8) 라 검 모델이 아니다 [확실: 디스어셈블 · 데이터]
		this.idle = Pose.idle(sk);
	}

	/** 없으면 null. */
	public static ArtemisBladeModel load(Path rom, int stage, Shader skinned) throws IOException {
		Path p = Content.file(rom, "R_BRSRK" + Math.max(0, Math.min(2, stage)) + ".WAD");
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
		int i = heroSk.indexOf("rWeapIH");
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
