package devple.gow.engine.asset;

import devple.gow.tools.content.Content;
import devple.gow.engine.render.Camera;
import devple.gow.engine.render.Shader;
import devple.gow.engine.render.Texture;
import devple.gow.tools.anim.Pose;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.model.GowModel;
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
		// 층 2 (43 §31): 블레이드 머티리얼은 모두 flags2 0x100 (영웅과 같은 반투명 단계 = 그림자 합성 뒤, DrawStage.lateMaterial bit 40) 이고 안개를 받는다.
		// Blades_Init 0x1FA010 의 0x150738(…, 2) → 층 3 은 블레이드 GO 가 아니라 0x23EB58 로 만든 사슬 띠 (MAT_chainlink/chainglow) 의 인스턴스다
		// [확실: 0x1F9F28 – 0x1FA010 · 데이터] — 예전 47 §7.4.2 의 "블레이드 = 층 3" 은 그 인스턴스를 모델로 읽은 것
		this.idle = Pose.idle(sk);
	}

	/** 없으면 null. */
	public static BladeModels load(Path rom, int costume, int level, Shader skinned) throws IOException {
		Path p = Content.file(rom, "R_WPN" + costume + "_" + Math.max(0, Math.min(5, level)) + ".WAD");
		if (!Files.exists(p)) {
			return null;
		}
		WadAssets a = new WadAssets(p);
		Skeleton sk = bladeSkeleton(a);
		GowModel mdl = bladeModel(a);
		if (sk == null || mdl == null) {
			a.close();
			return null;
		}
		return new BladeModels(a, sk, new AnimatedModel(a, mdl, sk, skinned));
	}

	/** 무기 WAD 의 블레이드 오브젝트 ({@code maiblade}, 대소문자 무시). 없으면 null. */
	static Skeleton bladeSkeleton(WadAssets a) {
		return Skeleton.loadAll(a.wad()).stream().filter(s -> s.name.equalsIgnoreCase("maiblade")).findFirst().orElse(null);
	}

	/**
	 * 무기 WAD 의 블레이드 모델. 기본 코스튬 0 과 코스튬 5 의 레벨 1–5 (R_WPN0_1–5, R_WPN5_1–5 — 36 개 중 10 개) 는 {@code MDL_MAIblade}
	 * (CDV·SHG·메시 MAIblade_0 도 같은 소문자 b), 나머지는 {@code MDL_MAIBlade} 라 대소문자를 가리지 않는다 [확실: 데이터 전수, 영상 5372 이후
	 * 레벨 2 블레이드]. 원작 자원 조회가 대문자 해시이기 때문으로 본다 (머티리얼 0x17A7B8 과 같은 계열, 46 문서) [추정: 모델 조회 경로].
	 * 예전 엔진은 정확히 같은 이름만 찾아 기본 코스튬 블레이드 레벨 ≥ 1 이면 load 가 null → 다음 레벨부터 블레이드·사슬·블레이드 라이트가
	 * 사라졌다 (43 §17).
	 */
	static GowModel bladeModel(WadAssets a) {
		return a.gowModelIgnoreCase("MDL_MAIBlade");
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

	/** 블레이드 하나를 주어진 월드 행렬에 (사슬이 정한 블레이드 행렬, 45 문서 §3). */
	public void drawAt(Camera cam, float aspect, Matrix4f world, Texture white) {
		model.draw(cam, aspect, world, idle, white);
	}

	/** 블레이드 그림자 (SHG_MAIBlade) 의 부모 GO 모델 (영웅) — 방향·받는 평면·모드를 부모 것으로 (47 문서 §7). */
	public void setShadowParent(Object parent) {
		model.shadowParent = parent;
	}

	/** 무기 WAD (사슬·궤적 머티리얼). */
	public WadAssets assets() {
		return assets;
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
