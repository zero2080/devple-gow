package devple.gow.engine.level;

import devple.gow.engine.asset.RenderModel;
import devple.gow.engine.asset.WadAssets;
import devple.gow.engine.core.Engine;
import devple.gow.engine.fx.FxEnvironment;
import devple.gow.engine.fx.LevelFx;
import devple.gow.engine.input.PadButton;
import devple.gow.engine.render.Camera;
import devple.gow.engine.render.Mesh;
import devple.gow.engine.render.MeshBuilder;
import devple.gow.engine.scene.Scene;
import devple.gow.tools.anim.Mat4;
import devple.gow.tools.level.GoInstance;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.level.LevelLayout;
import devple.gow.tools.level.PlacedMesh;
import devple.gow.tools.model.GowModel;
import devple.gow.tools.model.TriMesh;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL41C.*;

/**
 * 레벨 WAD 하나를 배치 그대로 그리는 씬 (12 / 23 문서).
 * <pre>
 * 조작  WASD 이동, 방향키 시선, Q/E 하강/상승, Shift 빠르게
 *       1 = 충돌 시트 와이어 (초록 바닥 / 빨강 벽)   2 = 볼륨 와이어 (CMV 파랑, ENV 노랑, SEV 보라, CDV 주황)
 *       Tab(SELECT) = 카메라 모드 순환: 자유 → 레벨 카메라 0..n-1      3 = 시작 위치 기준 CMV 자동 카메라
 * </pre>
 * 하늘(이름/텍스처에 "sky")은 원작 SCR_Sky 처럼 카메라 위치를 따라가게 그린다.
 */
public final class LevelScene implements Scene {

	/** 배치된 렌더 인스턴스. sky 면 카메라를 따라간다 (offset = 카메라에 놓일 점 = 인스턴스 위치). */
	public record Instance(String name, RenderModel model, boolean sky, Vector3f offset) {
	}

	private final Path wadPath;
	private final int startCam;
	private boolean showCollision;
	private boolean showVolumes;
	private WadAssets assets;
	private LevelData data;
	private LevelCollision collision;
	private final List<Instance> instances = new ArrayList<>();
	private Mesh collisionLines;
	private Mesh volumeLines;
	private final Camera cam = new Camera();
	private final Vector3f pos = new Vector3f();
	private float yaw;
	private float pitch = -0.25f;
	/** -1 = 자유 카메라, 0.. = 레벨 카메라 번호, -2 = CMV 자동. */
	private int camMode;
	private final boolean[] keyPrev = new boolean[3];
	private final Matrix4f tmp = new Matrix4f();
	/** true 면 파티클/물 등 이펙트를 render() 에서 그리지 않음 → 게임 씬이 캐릭터 뒤에 {@link #renderEffects} 호출. */
	private boolean deferEffects;

	public void setDeferEffects(boolean b) {
		deferEffects = b;
	}

	/** 이펙트 패스 (캐릭터를 그린 뒤 호출해야 반투명 이펙트가 캐릭터를 올바르게 덮는다). */
	public void renderEffects(Engine e, Camera c) {
		e.renderer().begin3d(c);
		fx.renderEffects(e, c);
		FxEnvironment.disableFog(e.renderer().basic());
		lensFlares.render(c, e.window().aspect());
	}

	/** SCR_LensFlare (46 문서): 캐릭터·이펙트 뒤 (깊이 버퍼 완성 후). */
	private devple.gow.engine.fx.LensFlares lensFlares;

	/** 게임 씬이 지정하는 카메라 (null 이면 뷰어 자체 카메라). */
	private Camera external;
	/** 환경/이펙트 (안개, 비, 파티클, 물 — 31 문서). */
	private LevelFx fx;

	/**
	 * @param startCam    -1 자유, -2 CMV 자동, 0.. 레벨 카메라 번호
	 * @param collisionOn 충돌 와이어 처음부터 표시
	 */
	public LevelScene(Path wadPath, int startCam, boolean collisionOn) {
		this.wadPath = wadPath;
		this.startCam = startCam;
		this.showCollision = collisionOn;
		this.showVolumes = collisionOn;
	}

	/** 레벨 WAD 자산 (이펙트 GO 등록용, 40 문서). enter 전이면 null. */
	public WadAssets assets() {
		return assets;
	}

	public LevelData data() {
		return data;
	}

	public LevelCollision collision() {
		return collision;
	}

	public List<Instance> instances() {
		return instances;
	}

	/** 환경/이펙트 (안개 유니폼을 다른 셰이더에도 적용할 때: {@code fx().env().applyFog(shader, cam, h)}). */
	public LevelFx fx() {
		return fx;
	}

	/** 숨긴 인스턴스 (부서진 물체 등, 인스턴스 이름 소문자). */
	private final java.util.Set<String> removed = new java.util.HashSet<>();
	/** 스크립트 Animator 로 움직인 오브젝트 (이름 → 관절 월드) — 구운 메시 대신 스키닝 모델로 그린다. */
	private final Map<String, float[][]> posed = new HashMap<>();
	/** posed 오브젝트의 관절 숨김 (u8 가시성 트랙, 41 §1.3) — 없으면 null. */
	private final Map<String, boolean[]> posedHidden = new HashMap<>();

	/**
	 * 시작 act 가 반복 act (flags bit0) 인 배치: 시작 시간부터 계속 재생 (돛, 흔들리는 그릇, 나무, PAND03C 굴러가는 바위 …).
	 * [확실: 시작 act/시간 = GO 리셋 0x1309C0] [추정: 계속 재생 여부 = act 반복 비트 — 1 회 act (부서지는 물체, DMC 결계, 발판) 는
	 * 스크립트·비헤이비어가 재생할 때까지 시작 자세. 데이터: 시작 시간을 엇갈려 둔 배치(ATHN08A fellergroup, PAND03C rock)는 모두 반복 act].
	 */
	private record Ambient(String key, devple.gow.tools.anim.Skeleton skeleton, devple.gow.tools.anim.Animation act, float[] inst,
						   float start) {
	}

	private final List<Ambient> ambient = new ArrayList<>();
	/** SCR_Mirror 반사 평면 (Mirrors). */
	private final List<Mirrors.Mirror> mirrors = new ArrayList<>();
	private int mirrorQuery;
	private final java.util.Set<String> mirrorKeys = new java.util.HashSet<>();
	/**
	 * 인스턴스 뷰 마스크 (원작 모델 +0xD8, {@link Mirrors#modelMask}): 일반 뷰 0x41 / 거울 뷰 0x82 (값 [4] ≠ 0 이면 0x02) 와 겹칠 때만 그 뷰에 그린다.
	 * 없으면 레벨 기본 0x01 (일반만).
	 */
	private final Map<String, Integer> viewMasks = new HashMap<>();
	/** 반사 패스에 영웅 무리 (영웅·블레이드) 를 그리는 콜백 — GameScene 이 건다 (원작 0x1DAEC0 bit 61, 마스크 0x40). */
	private java.util.function.Consumer<Camera> mirrorActors;
	private final Camera mirrorCam = new Camera();
	/** 0x1DF1E0 으로 bit 61 + 마스크 0x40 을 받는 비헤이비어 (PushPull 0x20FB98, IO_TandF 0x213C08, IO_CSM 0x2154F8). */
	private static final java.util.Set<String> HERO_GROUP_BEHAVIORS = java.util.Set.of("PushPull", "IO_TandF", "IO_CSM");

	/** 반사 패스에서 영웅 (값 [4] = 0 인 거울) 을 그릴 콜백: 인자 = 반사 카메라 ({@link Camera#mirror}, {@link Camera#clipPlane}). */
	public void setMirrorActors(java.util.function.Consumer<Camera> f) {
		mirrorActors = f;
	}

	/** 일반 뷰에 그리는가 (반사 전용 대리 메시 bit 57 은 아님). GOW_MIRROR=off 면 전부 (이전 동작). */
	private boolean inMainView(String key) {
		return !MIRRORS || (viewMasks.getOrDefault(key, Mirrors.LEVEL_MODEL) & Mirrors.MAIN_VIEW) != 0;
	}

	/** SCR_Mirror 반사 평면 수 (테스트·로그). */
	public int mirrorCount() {
		return mirrors.size();
	}
	/** 시작 자세 (act 를 시작 시간에) 로 구운 배치 수 / 반복 재생 배치 수 — 로그·테스트용. */
	private int startPosed;
	private final Map<String, devple.gow.engine.asset.AnimatedModel> posedModels = new HashMap<>();
	/** 렌더 플래그 0x800 (동적 조명) 정적 인스턴스의 구 중심 (오브젝트 위치)·플래그 (47 문서). */
	private final Map<String, Vector3f> litCenters = new HashMap<>();
	private final Map<String, Long> litFlags = new HashMap<>();
	private devple.gow.engine.render.Shader skinned;

	/**
	 * 오브젝트 자세 (관절 월드 = 애니 월드 × 인스턴스 행렬, {@code nav.Platforms.Body#joints}). 원작은 배치 오브젝트도 관절 애니로 그린다 —
	 * 엔진은 불러올 때 정지 자세로 정점을 구워 두므로, 애니가 한 번이라도 돈 오브젝트만 스키닝 모델로 바꿔 그린다.
	 */
	public void poseInstance(String name, float[][] jointWorld) {
		poseInstance(name, jointWorld, null);
	}

	/** @param hidden 관절 숨김 ({@link devple.gow.tools.anim.Animation#hiddenJoints}, 없으면 null) — DMC 결계 dogIn/dogOut 등 */
	public void poseInstance(String name, float[][] jointWorld, boolean[] hidden) {
		String key = name.toLowerCase(Locale.ROOT);
		posed.put(key, jointWorld);
		if (hidden != null) {
			posedHidden.put(key, hidden);
		} else {
			posedHidden.remove(key);
		}
	}

	/** 인스턴스를 더 그리지 않는다 (부서지는 물체 파괴, 스크립트 Vis 숨김). */
	public void hideInstance(String name) {
		removed.add(name.toLowerCase(Locale.ROOT));
	}

	/** 숨긴 인스턴스를 다시 그린다 (스크립트 Vis Visible 1). */
	public void showInstance(String name) {
		removed.remove(name.toLowerCase(Locale.ROOT));
	}

	/** 통합 게임 씬용: 이 카메라로 렌더 (자유/레벨 카메라 처리 생략). */
	public void setExternalCamera(Camera c) {
		external = c;
	}

	@Override
	public void enter(Engine e) {
		try {
			assets = new WadAssets(wadPath);
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		data = new LevelData(assets.wad());
		collision = new LevelCollision(data.sheets);
		List<TriMesh> all = new ArrayList<>();
		devple.gow.tools.fx.EnvParams envParams = new devple.gow.tools.fx.EnvParams(assets.wad());
		java.util.Set<String> skyNames = skyNames(envParams);
		Map<String, int[]> mirrorOwners = Mirrors.owners(envParams);
		lensFlares = new devple.gow.engine.fx.LensFlares(envParams, data, assets); // SCR_LensFlare (46 문서)
		for (LevelLayout.Placement p : data.layout.placements()) {
			String mdl = p.model();
			GowModel gm = mdl == null ? null : assets.gowModel(mdl);
			if (gm == null || p.instance().scale() == 0) {
				continue;
			}
			// 시작 자세 (원작 GO 리셋 0x1309C0: 인스턴스 시작 act 를 시작 시간에) — ANM 이 없으면 바인드 자세 그대로
			devple.gow.tools.level.StartPose sp = devple.gow.tools.level.StartPose.of(p, START_POSE ? animSet(p) : null);
			TriMesh tm = sp.act() == null ? PlacedMesh.build(gm, p.object().skeleton(), p.jointWorld(), 0)
					: PlacedMesh.build(gm, p.object().skeleton(), sp.jointWorld(), 0, sp.hidden());
			Ambient amb = null;
			if (sp.act() != null) {
				startPosed++;
				// 비헤이비어 (PushPull 크랭크·레버, IO_CSM, Breakable …) 가 있는 GO 는 그 클래스가 애니 시간을 정한다 (R1 진행 시간 0x215928,
				// 파괴 0x1DDF70) → 스스로 돌지 않는다 (PAND00C 고리 goring1crank 는 반복 act 지만 크랭크로만 돈다)
				if (!data.behaviors.containsKey(p.instance().name()) && sp.act().looping() && sp.act().duration > 0 && sp.act().hasSkeletalData()
						&& p.object().skeleton().size() <= devple.gow.engine.asset.AnimatedModel.MAX_JOINTS) {
					amb = new Ambient(p.instance().name().toLowerCase(Locale.ROOT), p.object().skeleton(), sp.act(),
							p.instance().matrix(), sp.time());
				}
			}
			if (tm.subs.isEmpty()) {
				continue;
			}
			float[] b = tm.bounds();
			Vector3f off = new Vector3f((b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2);
			// 하늘 = SCR_Sky 가 붙은 오브젝트 (16 문서 §1.4, 31 §2). SCR_Sky 가 없는 레벨만 이전 휴리스틱
			// (이름/텍스처에 sky + 원점 중심(XZ)의 큰 메시; PAND01A 의 "sky" 처럼 레벨 안에 놓인 하늘 조각은 제외).
			float r = Math.max(b[3] - b[0], b[5] - b[2]) / 2;
			boolean sky = skyNames.isEmpty()
					? isSky(p, gm) && r > 500 && Math.hypot(off.x, off.z) < r * 0.1f
					: isScrSky(p, skyNames);
			if (!sky) {
				all.add(tm);
				if (amb != null) {
					ambient.add(amb); // 하늘은 SCR_Sky 경로 (카메라 따라감) 로만 그린다
				}
			}
			if (sky) {
				// SCR_Sky (0x218848): 하늘 오브젝트를 카메라로 옮긴다 → 카메라 = 관절 0 월드 위치 (AABB 중심이 아님, 16 문서 §1.6).
				// ATHN01A nightSky: 관절 0 = (0,448,0), 미니어처 바다면 y=432 → 지평선이 눈높이 바로 아래
				float[] j0 = p.jointWorld()[0];
				off.set(j0[12], j0[13], j0[14]);
			}
			instances.add(new Instance(p.instance().name(), assets.build(p.instance().name(), tm), sky, off));
			if (!sky && (gm.renderFlags & devple.gow.engine.light.LightUniforms.LIT) != 0) {
				// 렌더 플래그 0x800 정적 오브젝트: 원작 0x141EF8 은 구 = (오브젝트 위치, 1) 로 라이트를 모은다 (47 문서)
				float[] j0 = p.jointWorld()[0];
				litCenters.put(p.instance().name().toLowerCase(Locale.ROOT), new Vector3f(j0[12], j0[13], j0[14]));
				litFlags.put(p.instance().name().toLowerCase(Locale.ROOT), gm.renderFlags);
			}
			int[] mab = mirrorOwners.get(p.instance().name().toLowerCase(Locale.ROOT));
			// 뷰 마스크 (0x156820 → 0x1508D0): MDL +0x28 렌더 플래그. 거울 소유자는 bit 61 을 지운다 (0x129820 — 자기 반사 없음)
			long rf = gm.renderFlags;
			int base = Mirrors.LEVEL_MODEL;
			devple.gow.tools.level.GoBehavior bh = data.behaviors.get(p.instance().name());
			if (bh != null && HERO_GROUP_BEHAVIORS.contains(bh.type())) {
				rf |= GowModel.REFLECTED;
				base = Mirrors.HERO_GROUP;
			}
			if (mab != null) {
				rf &= ~GowModel.REFLECTED;
			}
			int vmask = Mirrors.modelMask(rf, base);
			if (vmask != Mirrors.LEVEL_MODEL) {
				viewMasks.put(p.instance().name().toLowerCase(Locale.ROOT), vmask);
			}
			if (mab != null && !sky) {
				Mirrors.Mirror m = Mirrors.fit(p.instance().name().toLowerCase(Locale.ROOT), tm, mab);
				// 평면이 아닌 거울 메시 (ATHN01E goreflfloor, PAND05B oorMirrorGRP) 는 원작 0x12A330 처럼 모델 Y 축 평면으로
				float ext = Math.max(b[3] - b[0], Math.max(b[4] - b[1], b[5] - b[2]));
				if (m != null && m.maxDev() > Math.max(0.5f, ext * 0.01f)) {
					if (Boolean.getBoolean("gow.debug")) {
						System.out.printf(Locale.ROOT, "SCR_Mirror %s: 메시 평면 아님 (이탈 %.2f / 크기 %.1f) → 모델 Y 축 평면%n", p.instance().name(), m.maxDev(), ext);
					}
					m = Mirrors.axisPlane(m, p.jointWorld()[0]);
				}
				if (m != null) {
					mirrors.add(m);
					mirrorKeys.add(m.key());
					if (Boolean.getBoolean("gow.debug")) {
						System.out.printf(Locale.ROOT, "SCR_Mirror %s: 평면 n=(%.2f, %.2f, %.2f) d=%.1f 이탈 %.2f 범위 %s%n", p.instance().name(),
								m.normal().x, m.normal().y, m.normal().z, m.d(), m.maxDev(), java.util.Arrays.toString(b));
					}
				} else if (Boolean.getBoolean("gow.debug")) {
					System.out.printf(Locale.ROOT, "SCR_Mirror %s: 면적 0 — 반사 생략%n", p.instance().name());
				}
			}
		}
		collisionLines = buildCollisionLines();
		volumeLines = buildVolumeLines();
		cam.near = 2f;
		cam.far = 30000f;
		cam.fovDeg = 60f;
		placeStart(all);
		camMode = startCam;
		fx = new LevelFx(e, assets, data, instances, wadPath);
		System.out.printf(Locale.ROOT, "레벨 %s: 렌더 인스턴스 %d (하늘 %d, 시작 act 자세 %d, 반복 재생 %d, 거울 %d), 충돌 삼각형 %d, 볼륨 %d, 카메라 %d%n",
				assets.wad().name(), instances.size(), instances.stream().filter(Instance::sky).count(), startPosed, ambient.size(), mirrors.size(),
				collision.triangles().size(), data.volumes.size(), data.placedCameras.size());
		if (Boolean.getBoolean("gow.debug") && !ambient.isEmpty()) {
			System.out.println("반복 재생 배치: " + ambientNames());
		}
	}

	private final Map<String, devple.gow.tools.anim.AnimationSet> animSets = new HashMap<>();

	/** 배치 오브젝트의 ANM 세트 (그룹 참조 ANM_x, 레벨 WAD 안). 없거나 읽지 못하면 null. */
	private devple.gow.tools.anim.AnimationSet animSet(LevelLayout.Placement p) {
		List<String> anm = p.object().refs("ANM_");
		if (anm.isEmpty() || p.object().skeleton() == null) {
			return null;
		}
		return animSets.computeIfAbsent(anm.getFirst(), n -> {
			try {
				return devple.gow.tools.anim.AnimationSet.load(assets.wad(), n);
			} catch (RuntimeException ex) {
				return null;
			}
		});
	}

	/** 시작 act 자세로 구운 배치 수 (ANM 이 있는 배치). */
	public int startPosedCount() {
		return startPosed;
	}

	/** 반복 재생하는 배치 이름 (소문자). */
	public List<String> ambientNames() {
		return ambient.stream().map(Ambient::key).toList();
	}

	/**
	 * SCR_Sky 대상 이름 (소문자): 템플릿 소유자 MDL_x (모델에 붙은 스크립트) 와 본문 오브젝트 이름.
	 * 원작 0x218848 은 이 스크립트가 붙은 GO 를 매 프레임 카메라로 옮긴다 [확실, 16 §1.4].
	 */
	static java.util.Set<String> skyNames(devple.gow.tools.fx.EnvParams env) {
		java.util.Set<String> out = new java.util.HashSet<>();
		for (devple.gow.tools.fx.EnvParams.Sky s : env.skies) {
			if (s.owner() != null && !s.owner().isBlank()) {
				out.add(s.owner().trim().toLowerCase(Locale.ROOT));
			}
			if (s.object() != null && !s.object().isBlank()) {
				out.add(s.object().trim().toLowerCase(Locale.ROOT));
			}
		}
		return out;
	}

	/** 배치가 SCR_Sky 대상인가: 모델 이름(MDL_x) 또는 오브젝트 이름이 SCR_Sky 이름과 같다. */
	static boolean isScrSky(LevelLayout.Placement p, java.util.Set<String> skyNames) {
		String mdl = p.model();
		return (mdl != null && skyNames.contains(mdl.toLowerCase(Locale.ROOT)))
				|| skyNames.contains(p.object().name().toLowerCase(Locale.ROOT));
	}

	private boolean isSky(LevelLayout.Placement p, GowModel gm) {
		if (p.object().name().toLowerCase(Locale.ROOT).contains("sky")) {
			return true;
		}
		for (String m : gm.materials) {
			String t = assets.materialTexture(m);
			if (t != null && t.toLowerCase(Locale.ROOT).contains("sky")) {
				return true;
			}
		}
		return false;
	}

	/** 자유 카메라 시작: 충돌 시트 AABB 를 비스듬히 내려다보는 위치, 없으면 지오메트리 중심. */
	private void placeStart(List<TriMesh> meshes) {
		if (!data.sheets.isEmpty()) {
			float[] mn = data.sheets.getFirst().min, mx = data.sheets.getFirst().max;
			// 깊은 낙사 바닥(y 가 크게 낮은 면)은 무시하고 바닥 근처 높이를 쓴다
			GoInstance start = data.layout.instances.stream().filter(g -> g.object().startsWith("start")).findFirst().orElse(null);
			float cx = (mn[0] + mx[0]) / 2, cz = (mn[2] + mx[2]) / 2;
			float cy = start != null ? start.pos()[1] : mx[1];
			float ext = Math.min(Math.max(mx[0] - mn[0], mx[2] - mn[2]), 1500);
			pitch = -0.6f;
			yaw = 0;
			pos.set(cx, cy + ext * 0.55f, cz + ext * 0.8f);
		} else if (!meshes.isEmpty()) {
			float[] b = PlacedMesh.bounds(meshes);
			pos.set((b[0] + b[3]) / 2, b[4] + 50, b[5] + 100);
			yaw = 0;
		}
	}

	private Mesh buildCollisionLines() {
		MeshBuilder mb = new MeshBuilder();
		for (LevelCollision.Tri t : collision.triangles()) {
			float r = t.floor() ? 0.2f : 1f, g = t.floor() ? 1f : 0.25f, b = 0.2f;
			mb.line(t.a().x, t.a().y, t.a().z, t.b().x, t.b().y, t.b().z, r, g, b);
			mb.line(t.b().x, t.b().y, t.b().z, t.c().x, t.c().y, t.c().z, r, g, b);
			mb.line(t.c().x, t.c().y, t.c().z, t.a().x, t.a().y, t.a().z, r, g, b);
		}
		return mb.build(GL_LINES);
	}

	private Mesh buildVolumeLines() {
		MeshBuilder mb = new MeshBuilder();
		float[] a = new float[3];
		float[] b = new float[3];
		for (LevelData.PlacedVolume v : data.volumes) {
			float[] c = switch (v.hull().kind) {
				case 0 -> new float[]{1f, 0.55f, 0.1f};
				case 1 -> new float[]{0.3f, 0.5f, 1f};
				case 2 -> new float[]{1f, 1f, 0.2f};
				default -> new float[]{0.8f, 0.3f, 1f};
			};
			if (v.wire() != null) {
				float[][] vs = v.wire().vertices;
				int[] l = v.wire().lines;
				for (int i = 0; i + 1 < l.length; i += 2) {
					if (l[i] >= vs.length || l[i + 1] >= vs.length) {
						continue;
					}
					Mat4.transformPoint(v.world(), vs[l[i]][0], vs[l[i]][1], vs[l[i]][2], a);
					Mat4.transformPoint(v.world(), vs[l[i + 1]][0], vs[l[i + 1]][1], vs[l[i + 1]][2], b);
					mb.line(a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2]);
				}
			}
			for (float[] ball : v.hull().balls) {
				for (int k = 0; k < 3; k++) {
					for (int s = 0; s < 24; s++) {
						double t0 = s * Math.PI / 12, t1 = (s + 1) * Math.PI / 12;
						float[] p0 = circle(ball, k, t0), p1 = circle(ball, k, t1);
						Mat4.transformPoint(v.world(), p0[0], p0[1], p0[2], a);
						Mat4.transformPoint(v.world(), p1[0], p1[1], p1[2], b);
						mb.line(a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2]);
					}
				}
			}
		}
		return mb.build(GL_LINES);
	}

	private static float[] circle(float[] s, int axis, double t) {
		float u = (float) Math.cos(t) * s[3], w = (float) Math.sin(t) * s[3];
		return switch (axis) {
			case 0 -> new float[]{s[0] + u, s[1] + w, s[2]};
			case 1 -> new float[]{s[0] + u, s[1], s[2] + w};
			default -> new float[]{s[0], s[1] + u, s[2] + w};
		};
	}

	private boolean edge(Engine e, int idx, int key) {
		boolean down = glfwGetKey(e.window().handle(), key) == GLFW_PRESS;
		boolean r = down && !keyPrev[idx];
		keyPrev[idx] = down;
		return r;
	}

	@Override
	public void update(Engine e, float dt) {
		fx.update(e, dt, external != null ? external.eye : cam.eye);
		if (edge(e, 0, GLFW_KEY_1)) {
			showCollision = !showCollision;
		}
		if (edge(e, 1, GLFW_KEY_2)) {
			showVolumes = !showVolumes;
		}
		if (edge(e, 2, GLFW_KEY_3)) {
			camMode = camMode == -2 ? -1 : -2;
		}
		if (e.input().pressed(PadButton.SELECT)) {
			camMode = camMode + 1 >= data.placedCameras.size() ? -1 : camMode + 1;
			if (camMode >= 0) {
				System.out.println("레벨 카메라 " + camMode + ": " + data.placedCameras.get(camMode).def().name());
			}
		}
		yaw -= e.input().rightX() * dt * 1.6f;
		pitch = Math.clamp(pitch + e.input().rightY() * dt * 1.2f, -1.5f, 1.5f);
		float speed = (e.input().held(PadButton.L3) ? 600 : 150) * dt;
		Vector3f f = forward();
		Vector3f r = new Vector3f(f).cross(0, 1, 0).normalize();
		pos.fma(e.input().leftY() * speed, f).fma(e.input().leftX() * speed, r);
		if (e.input().held(PadButton.R1)) {
			pos.y += speed;
		}
		if (e.input().held(PadButton.L1)) {
			pos.y -= speed;
		}
	}

	private Vector3f forward() {
		return new Vector3f((float) (-Math.sin(yaw) * Math.cos(pitch)), (float) Math.sin(pitch), (float) (-Math.cos(yaw) * Math.cos(pitch)));
	}

	private void applyCamera() {
		LevelData.PlacedCamera lc = null;
		if (camMode >= 0 && camMode < data.placedCameras.size()) {
			lc = data.placedCameras.get(camMode);
		} else if (camMode == -2) {
			lc = data.activeCamera(pos.x, pos.y, pos.z);
			if (lc == null) {
				GoInstance s = data.layout.instances.stream().filter(g -> g.object().startsWith("start")).findFirst().orElse(null);
				if (s != null) {
					lc = data.activeCamera(s.pos()[0], s.pos()[1] + 10, s.pos()[2]);
				}
			}
			if (lc == null && !data.placedCameras.isEmpty()) {
				lc = data.placedCameras.getFirst();
			}
		}
		String dbg = System.getenv("GOW_LEVEL_CAM"); // 디버그 스크린샷: "ex,ey,ez,tx,ty,tz"
		if (dbg != null && dbg.equals("print") && lc != null) {
			System.out.printf(Locale.ROOT, "cam eye %s fwd %s%n", java.util.Arrays.toString(lc.eye()), java.util.Arrays.toString(lc.forward()));
			dbg = null;
		}
		if (dbg != null) {
			String[] v = dbg.split(",");
			cam.eye.set(Float.parseFloat(v[0]), Float.parseFloat(v[1]), Float.parseFloat(v[2]));
			cam.target.set(Float.parseFloat(v[3]), Float.parseFloat(v[4]), Float.parseFloat(v[5]));
		} else if (lc != null) {
			float[] eye = lc.eye();
			float[] fw = lc.forward();
			cam.eye.set(eye[0], eye[1], eye[2]);
			cam.target.set(eye[0] + fw[0] * 100, eye[1] + fw[1] * 100, eye[2] + fw[2] * 100);
		} else {
			cam.eye.set(pos);
			cam.target.set(pos).add(forward());
		}
	}

	@Override
	public void render(Engine e, float alpha) {
		Camera cam = this.cam;
		if (external != null) {
			cam = external;
		} else {
			applyCamera();
		}
		fx.beforeRender(e, cam);
		e.renderer().begin3d(cam);
		fx.drawBackground(e, cam);
		fx.beginWorld(e, cam, true);
		// 하늘: 카메라 중심, 깊이 쓰기 끔 → 항상 맨 뒤
		glDepthMask(false);
		for (Instance i : instances) {
			if (i.sky() && !dbgHidden("sky")) {
				tmp.translation(cam.eye.x - i.offset().x, cam.eye.y - i.offset().y, cam.eye.z - i.offset().z);
				e.renderer().draw(i.model(), tmp);
			}
		}
		glDepthMask(true);
		fx.beginWorld(e, cam, false);
		tmp.identity();
		// 원작처럼 모든 인스턴스의 불투명 패스 뒤에 반투명 패스 (Renderer.drawPass). GOW_PASSORDER=model 이면 이전 (모델마다 두 패스)
		for (int pass = MODEL_PASSES ? 1 : 0; pass < 2; pass++) {
			if (pass == 1 && !MODEL_PASSES) {
				renderMirrors(e, cam); // 불투명 뒤, 반투명 (거울 바닥 자체 포함) 앞
			}
			for (Instance i : instances) {
				String key = i.name().toLowerCase(Locale.ROOT);
				if (!i.sky() && !fx.hidden(i) && !removed.contains(key) && !posed.containsKey(key) && !ambientKeys.contains(key)
						&& !dbgHidden(key) && !(MIRRORS && !MODEL_PASSES && mirrorKeys.contains(key)) && inMainView(key)) {
					Vector3f lc = litCenters.get(key);
					if (lc != null) {
						devple.gow.engine.light.LightUniforms.apply(e.renderer().basic(), litFlags.get(key), lc, 1f);
					}
					if (MODEL_PASSES) {
						e.renderer().draw(i.model(), tmp);
					} else {
						e.renderer().drawPass(i.model(), tmp, pass);
					}
					if (lc != null) {
						devple.gow.engine.light.LightUniforms.off(e.renderer().basic());
					}
				}
			}
		}
		renderPosed(e, cam, 0);
		if (!deferEffects) {
			fx.renderEffects(e, cam);
			lensFlares.render(cam, e.window().aspect());
		}
		FxEnvironment.disableFog(e.renderer().basic());
		if (showCollision || showVolumes) {
			e.renderer().begin3d(cam);
			glDisable(GL_DEPTH_TEST);
			if (showCollision) {
				e.renderer().draw(collisionLines, tmp.identity(), false);
			}
			if (showVolumes) {
				e.renderer().draw(volumeLines, tmp.identity(), false);
			}
			glEnable(GL_DEPTH_TEST);
		}
	}

	/** 비교용: GOW_MIRROR=off 이면 SCR_Mirror 반사를 그리지 않는다 (이전 동작). */
	private static final boolean MIRRORS = !"off".equals(System.getenv("GOW_MIRROR"));
	/** 디버그: GOW_MIRROR=raw 면 거울 모델을 반사 위에 그리지 않는다 (반사 장면만 확인). */
	private static final boolean MIRROR_RAW = "raw".equals(System.getenv("GOW_MIRROR"));

	/**
	 * SCR_Mirror 반사 (Mirrors 주석, 43 문서 §9): 가장 가까운 거울 하나 (0x12A6C8) 만 반사 — 거울 화소 스텐실 → 그 화소 깊이를 먼 값으로 →
	 * VP·R 로 거울 뷰 마스크에 맞는 것만 (반사 전용 대리 메시 bit 57, bit 61 모델, 값 [4] = 0 이면 영웅) 평면 뒤 자르고 감김 반전해 그림 →
	 * 거울 깊이 복원 → 거울 모델. 거울 화소가 하나도 안 보이면 조건부 렌더로 반사 장면을 건너뛴다. 나머지 거울은 모델만 그린다.
	 */
	private void renderMirrors(Engine e, Camera cam) {
		if (mirrors.isEmpty() || !MIRRORS) {
			return;
		}
		devple.gow.engine.render.Shader basic = e.renderer().basic();
		Matrix4f vp = new Matrix4f(cam.viewProj(e.window().aspect()));
		if (mirrorQuery == 0) {
			mirrorQuery = glGenQueries();
			if (Boolean.getBoolean("gow.debug")) {
				System.out.println("스텐실 비트: " + glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER, GL_STENCIL,
						GL_FRAMEBUFFER_ATTACHMENT_STENCIL_SIZE));
			}
		}
		// 0x12A6C8: 렌더러 중 0x12A330 거리가 가장 작은 거울 하나만 거울 뷰를 켠다
		List<Mirrors.Mirror> live = new ArrayList<>();
		Map<Mirrors.Mirror, Instance> owner = new HashMap<>();
		for (Mirrors.Mirror m : mirrors) {
			Instance mi = null;
			for (Instance i : instances) {
				if (i.name().equalsIgnoreCase(m.key())) {
					mi = i;
					break;
				}
			}
			if (mi == null || removed.contains(m.key()) || fx.hidden(mi) || dbgHidden(m.key())) {
				continue;
			}
			live.add(m);
			owner.put(m, mi);
		}
		Mirrors.Mirror chosen = Mirrors.nearest(live, cam.eye);
		for (Mirrors.Mirror m : live) {
			Instance mi = owner.get(m);
			if (m != chosen) {
				// 거울 뷰가 고르지 않은 거울: 모델만 (원작도 일반 뷰에는 그대로 그린다, bit 61 만 꺼짐)
				e.renderer().drawPass(mi.model(), tmp.identity(), 0);
				e.renderer().drawPass(mi.model(), tmp.identity(), 1);
				continue;
			}
			basic.use();
			basic.set("uViewProj", vp);
			// 1) 거울 화소 표시 (보이는 곳만: 깊이 검사, 색·깊이 쓰기 끔)
			glClearStencil(0);
			glClear(GL_STENCIL_BUFFER_BIT);
			glEnable(GL_STENCIL_TEST);
			glStencilMask(0xFF);
			glStencilFunc(GL_ALWAYS, 1, 0xFF);
			glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);
			glColorMask(false, false, false, false);
			glDepthMask(false);
			glDepthFunc(GL_LEQUAL);
			glBeginQuery(GL_ANY_SAMPLES_PASSED, mirrorQuery);
			e.renderer().drawShape(mi.model(), tmp.identity());
			glEndQuery(GL_ANY_SAMPLES_PASSED);
			// 2) 그 화소의 깊이를 먼 값으로
			glStencilFunc(GL_EQUAL, 1, 0xFF);
			glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
			glDepthMask(true);
			glDepthFunc(GL_ALWAYS);
			glDepthRange(1, 1);
			e.renderer().drawShape(mi.model(), tmp.identity());
			glDepthRange(0, 1);
			glDepthFunc(GL_LESS);
			glColorMask(true, true, true, true);
			// 3) 반사 장면 (원작 0x12A4E8: 카메라를 평면에 대해 반사) — 거울 뷰 마스크에 맞는 모델만 (정적 월드는 다시 그리지 않음)
			glBeginConditionalRender(mirrorQuery, GL_QUERY_WAIT);
			int vm = m.viewMask();
			Matrix4f refl = m.reflection();
			Matrix4f rvp = new Matrix4f(vp).mul(refl);
			float[] cp = m.clipPlane(cam.eye);
			glFrontFace(GL_CW); // 반사 = 행렬식 −1 → 감김 반전 (면 컬링을 켠 패스도 같은 면을 남긴다)
			fx.beginWorld(e, cam, false);
			basic.set("uViewProj", rvp);
			basic.set("uClipPlane", cp[0], cp[1], cp[2], cp[3]);
			glEnable(GL_CLIP_DISTANCE0);
			tmp.identity();
			for (int pass = 0; pass < 2; pass++) {
				for (Instance i : instances) {
					String key = i.name().toLowerCase(Locale.ROOT);
					if ((viewMasks.getOrDefault(key, Mirrors.LEVEL_MODEL) & vm) != 0 && i != mi && !fx.hidden(i) && !removed.contains(key)
							&& !posed.containsKey(key) && !ambientKeys.contains(key) && !dbgHidden(key)) {
						e.renderer().drawPass(i.model(), tmp, pass);
					}
				}
			}
			mirrorCam.eye.set(cam.eye);
			mirrorCam.target.set(cam.target);
			mirrorCam.fovDeg = cam.fovDeg;
			mirrorCam.near = cam.near;
			mirrorCam.far = cam.far;
			mirrorCam.mirror = refl;
			mirrorCam.clipPlane = cp;
			renderPosed(e, mirrorCam, vm); // 애니·자세 인스턴스 (bit 61 문·항아리, PushPull 크랭크 …)
			if ((vm & 0x80) != 0 && mirrorActors != null) {
				mirrorActors.accept(mirrorCam); // 영웅·블레이드 (마스크 0xC0)
			}
			glDisable(GL_CLIP_DISTANCE0);
			glFrontFace(GL_CCW);
			basic.use();
			basic.set("uClipPlane", 0f, 0f, 0f, 0f);
			glEndConditionalRender();
			// 4) 거울 깊이 복원 (뒤에 그리는 캐릭터·이펙트가 거울 면에 가려지도록)
			e.renderer().begin3d(cam);
			fx.beginWorld(e, cam, false);
			basic.set("uViewProj", vp);
			glColorMask(false, false, false, false);
			glDepthFunc(GL_ALWAYS);
			e.renderer().drawShape(mi.model(), tmp.identity());
			glColorMask(true, true, true, true);
			glDisable(GL_STENCIL_TEST);
			// 5) 거울 모델 자체: 반사 위에 두 패스 — 불투명 파트는 반사를 덮고 (ATHN04B 대리석 타일), 반투명 파트 (OLYMP02 대리석 알파) 는 그 위에 블렌드
			if (!MIRROR_RAW) {
				glDepthFunc(GL_LEQUAL);
				e.renderer().drawPass(mi.model(), tmp.identity(), 0);
				e.renderer().drawPass(mi.model(), tmp.identity(), 1);
				glDepthFunc(GL_LESS);
			}
		}
		basic.use();
		basic.set("uViewProj", vp);
	}

	/** 디버그: GOW_HIDE=인스턴스,… 를 그리지 않는다 ("*" = 하늘 밖 전부, "sky" = 하늘). 화면을 덮는 메시 찾기용 (43 문서). */
	private static final java.util.Set<String> DBG_HIDE = System.getenv("GOW_HIDE") == null ? java.util.Set.of()
			: java.util.Set.of(System.getenv("GOW_HIDE").toLowerCase(Locale.ROOT).split(","));

	/** 비교용: GOW_STARTPOSE=off 이면 배치 오브젝트를 바인드 자세로 굽고 반복 act 를 재생하지 않는다 (이전 동작). */
	private static final boolean START_POSE = !"off".equals(System.getenv("GOW_STARTPOSE"));

	/** 비교용: GOW_PASSORDER=model 이면 모델마다 두 패스 (이전 동작). */
	private static final boolean MODEL_PASSES = "model".equals(System.getenv("GOW_PASSORDER"));

	private static boolean dbgHidden(String key) {
		return !DBG_HIDE.isEmpty() && (DBG_HIDE.contains(key) || (!key.equals("sky") && DBG_HIDE.contains("*")));
	}

	private java.util.Set<String> ambientKeys = java.util.Set.of();

	/** @param mirrorView 0 = 일반 뷰 (반사 전용 제외), 아니면 거울 뷰 마스크 — 그 마스크와 겹치는 것만 (반사 패스) */
	private void renderPosed(Engine e, Camera cam, int mirrorView) {
		if (ambientKeys.size() != ambient.size()) {
			ambientKeys = new java.util.HashSet<>(ambientNames());
		}
		if (posed.isEmpty() && ambient.isEmpty()) {
			return;
		}
		java.util.function.Predicate<String> view = mirrorView == 0 ? this::inMainView
				: k -> (viewMasks.getOrDefault(k, Mirrors.LEVEL_MODEL) & mirrorView) != 0;
		if (mirrorView != 0 && posed.keySet().stream().noneMatch(view) && ambient.stream().noneMatch(a -> view.test(a.key()))) {
			return;
		}
		if (skinned == null) {
			skinned = new devple.gow.engine.render.Shader("skinned.vert", "basic.frag");
		}
		fx.env().applyFog(skinned, cam, e.window().height()); // 구운 메시와 같은 안개 (basic.frag 공유)
		for (Map.Entry<String, float[][]> en : posed.entrySet()) {
			if (removed.contains(en.getKey()) || dbgHidden(en.getKey()) || !view.test(en.getKey())) {
				continue;
			}
			drawPosed(e, cam, en.getKey(), en.getValue(), posedHidden.get(en.getKey()));
		}
		// 반복 act 배치: 시작 시간 + 레벨 시간 (스크립트 Animator 가 잡으면 posed 가 우선)
		float now = fx.env().time();
		for (Ambient a : ambient) {
			if (posed.containsKey(a.key()) || removed.contains(a.key()) || dbgHidden(a.key()) || fxHidden(a.key()) || !view.test(a.key())) {
				continue;
			}
			float t = a.start() + now;
			float[][] lw = a.act().sample(a.skeleton(), t, true).world(a.skeleton());
			float[][] w = new float[lw.length][];
			for (int i = 0; i < lw.length; i++) {
				w[i] = Mat4.mul(lw[i], a.inst());
			}
			float len = a.act().duration;
			drawPosed(e, cam, a.key(), w, a.act().hiddenJoints(a.skeleton(), ((t % len) + len) % len));
		}
		FxEnvironment.disableFog(skinned);
	}

	private boolean fxHidden(String key) {
		for (Instance i : instances) {
			if (i.name().equalsIgnoreCase(key)) {
				return fx.hidden(i);
			}
		}
		return false;
	}

	private void drawPosed(Engine e, Camera cam, String key, float[][] joints, boolean[] hidden) {
		devple.gow.engine.asset.AnimatedModel m = posedModels.computeIfAbsent(key, this::posedModel);
		if (m != null) {
			m.hiddenJoints = hidden;
			m.draw(cam, e.window().aspect(), tmp.identity(), joints, e.renderer().white(), -1f, 1f);
			m.hiddenJoints = null;
		}
	}

	private devple.gow.engine.asset.AnimatedModel posedModel(String key) {
		for (LevelLayout.Placement p : data.layout.placements()) {
			if (p.instance().name().equalsIgnoreCase(key) && p.model() != null && p.object().skeleton() != null) {
				GowModel gm = assets.gowModel(p.model());
				if (gm != null && p.object().skeleton().size() <= devple.gow.engine.asset.AnimatedModel.MAX_JOINTS) {
					return new devple.gow.engine.asset.AnimatedModel(assets, gm, p.object().skeleton(), skinned);
				}
			}
		}
		return null;
	}

	/**
	 * 다른 슬롯(이웃) 레벨로 그리기: 하늘·충돌선 없이 지오메트리만, 주 레벨이 정한 카메라로. 원작은 두 슬롯 레벨이 함께 상주하며 둘 다 그린다
	 * (36/37 문서: ATHN02D 메두사 방 = ATHN02C 지오메트리, 이음 전환 경계 너머).
	 */
	public void renderNeighbour(Engine e, Camera c) {
		e.renderer().begin3d(c);
		fx.beginWorld(e, c, false);
		tmp.identity();
		for (int pass = 0; pass < 2; pass++) {
			for (Instance i : instances) {
				if (!i.sky() && !fx.hidden(i) && !dbgHidden(i.name().toLowerCase(Locale.ROOT)) && inMainView(i.name().toLowerCase(Locale.ROOT))) {
					e.renderer().drawPass(i.model(), tmp, pass);
				}
			}
		}
		FxEnvironment.disableFog(e.renderer().basic());
	}

	@Override
	public void exit(Engine e) {
		fx.close();
		lensFlares.close();
		collisionLines.close();
		volumeLines.close();
		instances.forEach(i -> i.model().close());
		assets.close();
	}
}
