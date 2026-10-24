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

	/**
	 * 반복 act 를 재생하는 하늘 GO (SCR_Sky 소유자 + 시작 act 반복). 원작 0x218848 은 소유 GO 위치를 매 프레임 카메라로 옮기고
	 * 모델을 층 1 (하늘) 에 두며 동적 조명 플래그 0x800 을 끈다 (0x218778) [확실]. 애니 컴포넌트는 다른 GO 처럼 계속 돈다.
	 *
	 * @param off 관절 0 월드 (정적 하늘과 같은 규칙: 이 점이 카메라 눈에 온다)
	 */
	private record SkyAmbient(Ambient anim, Vector3f off) {
	}

	private final List<SkyAmbient> skyAmbient = new ArrayList<>();

	/** 애니 하늘 GO 이름 (소문자) — 로그·시험용. */
	public List<String> skyAmbientNames() {
		return skyAmbient.stream().map(s -> s.anim().key()).toList();
	}

	/**
	 * 하늘 GO 하나 (SCR_Sky 소유자): 관절 0 월드 {@code off} 가 카메라 눈에 온다 (0x218848). 이미터도 GO 컴포넌트라 같이 옮겨진다 —
	 * ATHN03A/03AA group2 의 도시 화톳불 FPemit* (관절 0 기준 (±100, −72, −130..−400)), 거대한 아레스 goaresanimgroup1 의 불붙은 바위 BFBemit (관절 34, 600/초)
	 * · 던지는 불덩이 FBFemit (관절 23, 19 초쯤 400/초) · 재 ASH* (GameScene.startSkyEffects, 43 §16).
	 *
	 * @param act   반복 재생하는 시작 act (없으면 null = 바인드/시작 자세 고정), start = 시작 시간
	 */
	public record SkyGo(String name, Vector3f off, float[] inst, devple.gow.tools.anim.Animation act, float start) {
	}

	private final List<SkyGo> skyGos = new ArrayList<>();

	/** 하늘 GO 전부 (정적·애니). */
	public List<SkyGo> skyGos() {
		return skyGos;
	}

	/** 레벨 효과 시간 (하늘 반복 act 시간 = 시작 시간 + 이 값). */
	public float fxTime() {
		return fx != null ? fx.env().time() : 0;
	}
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

	/** 자세를 모두 잊는다 (불러올 때의 정지 자세로) — 다시 쓰는 이웃 슬롯 장면 (GameScene 이웃 캐시, 48 §8). */
	public void clearPoses() {
		posed.clear();
		posedHidden.clear();
	}

	/** 인스턴스를 더 그리지 않는다 (부서지는 물체 파괴, 스크립트 Vis 숨김). */
	public void hideInstance(String name) {
		removed.add(name.toLowerCase(Locale.ROOT));
	}

	/** 숨긴 인스턴스 (소문자) 사본 — 이음으로 떠날 때 넘긴다 (GameScene.Resident). */
	public java.util.Set<String> removedInstances() {
		return new java.util.HashSet<>(removed);
	}

	/** 숨긴 인스턴스 집합을 통째로 바꾼다 (다른 슬롯 레벨: 이웃 스크립트 Vis, GameScene.otherHidden). */
	public void setRemoved(java.util.Set<String> names) {
		if (!removed.equals(names)) {
			removed.clear();
			removed.addAll(names);
		}
	}

	/** 숨긴 인스턴스를 다시 그린다 (스크립트 Vis Visible 1). */
	public void showInstance(String name) {
		removed.remove(name.toLowerCase(Locale.ROOT));
	}

	/** 통합 게임 씬용: 이 카메라로 렌더 (자유/레벨 카메라 처리 생략). */
	public void setExternalCamera(Camera c) {
		external = c;
	}

	/** {@link #prepare} 의 배치 결과 (GL 전): 렌더 인스턴스 하나와 그 정적 그림자. */
	private record PendingInstance(String name, WadAssets.Prepared model, boolean sky, Vector3f off,
								   devple.gow.engine.light.ShadowGeometry shadow, float[][] shadowJoints, boolean[] shadowHidden) {
	}

	/** null = 아직 준비 안 함 / 올리기 끝남. */
	private List<PendingInstance> pending;
	private MeshBuilder collisionMb, volumeMb;
	private devple.gow.tools.fx.EnvParams envParams;
	private boolean prepared;

	/**
	 * CPU 단계 (48 문서 §8): WAD 해석·레벨 데이터·충돌·배치 메시 굽기 (시작 자세)·텍스처 해독·거울 평면·와이어 정점. GL 을 부르지 않으므로
	 * 백그라운드 스레드 ({@link devple.gow.engine.asset.Loader}) 에서 돌려도 된다. 그 뒤 {@link #enter} (메인 스레드) 가 GL 로 올린다.
	 * 결과는 어느 스레드에서 돌려도 같다.
	 */
	public LevelScene prepare() {
		if (prepared) {
			return this;
		}
		try {
			assets = new WadAssets(wadPath);
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		data = new LevelData(assets.wad());
		collision = new LevelCollision(data.sheets);
		List<TriMesh> all = new ArrayList<>();
		envParams = new devple.gow.tools.fx.EnvParams(assets.wad());
		pending = new ArrayList<>();
		java.util.Set<String> skyNames = skyNames(envParams);
		Map<String, int[]> mirrorOwners = Mirrors.owners(envParams);
		shadowOwners = scriptOwners(envParams, "SCR_Shadow");
		java.util.Set<String> aiTemplates = aiTemplateObjects(assets.wad());
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
				skyGos.add(new SkyGo(p.instance().name().toLowerCase(Locale.ROOT), new Vector3f(off), p.instance().matrix(),
						amb != null ? amb.act() : null, amb != null ? amb.start() : 0));
				if (amb != null) {
					// 반복 act 가 있는 하늘 GO (ATHN03A/03AA/03C 거대한 아레스 goaresanimgroup1, act 1 aoBAR1a03Hl09 29.7 초):
					// 구운 시작 자세 대신 매 프레임 스키닝으로 하늘 단계에 그린다 (renderSkyAmbient)
					skyAmbient.add(new SkyAmbient(amb, new Vector3f(off)));
					continue;
				}
			}
			devple.gow.engine.light.ShadowGeometry shadow = null;
			if (!sky && p.object().skeleton() != null && System.getProperty("gow.noshadow") == null
					&& !aiTemplates.contains(p.object().name().toLowerCase(Locale.ROOT))) {
				// 레벨 GO 그림자 (47 문서 §11): SHG_<모델> 이 있는 스키닝 GO — 자세 (posed/반복 act) 가 아니면 시작 자세 관절로
				devple.gow.engine.light.ShadowGeometry g = shgCache.computeIfAbsent(mdl, k -> {
					try {
						return java.util.Optional.ofNullable(devple.gow.engine.light.ShadowGeometry.forModel(assets.wad(), k));
					} catch (RuntimeException ex) {
						return java.util.Optional.empty();
					}
				}).orElse(null);
				shadow = g;
			}
			pending.add(new PendingInstance(p.instance().name(), assets.prepare(p.instance().name(), tm, true), sky, off, shadow,
					sp.act() == null ? p.jointWorld() : sp.jointWorld(), sp.act() == null ? null : sp.hidden()));
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
					// 0x12A330 기준: 모델 Y 축 (관절 0 월드 2 행) · 경계 중심 [근사: AABB 중심 = off]
					float[] j0 = p.jointWorld()[0];
					m = m.withAxis(new Vector3f(j0[4], j0[5], j0[6]), off);
					mirrors.add(m);
					mirrorKeys.add(m.key());
					if (Boolean.getBoolean("gow.debug")) {
						System.out.printf(Locale.ROOT, "SCR_Mirror %s: 평면 n=(%.2f, %.2f, %.2f) d=%.1f 이탈 %.2f 범위 %s 축 %s 값 [0]=%d [4]=%d%n",
								p.instance().name(), m.normal().x, m.normal().y, m.normal().z, m.d(), m.maxDev(), java.util.Arrays.toString(b),
								m.axis(), m.a(), m.b());
					}
				} else if (Boolean.getBoolean("gow.debug")) {
					System.out.printf(Locale.ROOT, "SCR_Mirror %s: 면적 0 — 반사 생략%n", p.instance().name());
				}
			}
		}
		collisionMb = buildCollisionLines();
		volumeMb = buildVolumeLines();
		cam.near = FxEnvironment.viewNear; // 원작 뷰포트 near (+0x384 = −10, 0x168DC8)
		cam.far = 30000f;
		cam.fovDeg = 60f;
		placeStart(all);
		camMode = startCam;
		prepared = true;
		return this;
	}

	/** 준비 결과 요약 (시험용: 스레드와 무관하게 같은 값인지) — 배치 이름·정점·색인·그림자 관절의 해시. */
	long preparedHash() {
		long h = 17;
		for (PendingInstance q : pending) {
			h = h * 31 + q.name().hashCode();
			h = h * 31 + Boolean.hashCode(q.sky()) + q.off().hashCode();
			for (float[] v : q.model().vertices()) {
				h = h * 31 + java.util.Arrays.hashCode(v);
			}
			for (int[] ix : q.model().indices()) {
				h = h * 31 + java.util.Arrays.hashCode(ix);
			}
			h = h * 31 + (q.shadow() == null ? 0 : java.util.Arrays.deepHashCode(q.shadowJoints()));
		}
		return h * 31 + collision.triangles().size();
	}

	/** GL 단계 (메인 스레드). {@link #prepare} 를 먼저 하지 않았으면 여기서 (예전과 같은 한 번에 적재). */
	@Override
	public void enter(Engine e) {
		prepare();
		glStep(e, Long.MAX_VALUE);
		System.out.printf(Locale.ROOT, "레벨 %s: 렌더 인스턴스 %d (하늘 %d, 시작 act 자세 %d, 반복 재생 %d, 거울 %d), 충돌 삼각형 %d, 볼륨 %d, 카메라 %d%n",
				assets.wad().name(), instances.size(), instances.stream().filter(Instance::sky).count() + skyAmbient.size(), startPosed, ambient.size(), mirrors.size(),
				collision.triangles().size(), data.volumes.size(), data.placedCameras.size());
		if (Boolean.getBoolean("gow.debug") && !ambient.isEmpty()) {
			System.out.println("반복 재생 배치: " + ambientNames());
		}
	}

	/** 올린 배치 수 (pending 앞에서부터, {@link #glStep}). */
	private int uploaded;

	/**
	 * GL 단계를 deadline (System.nanoTime) 까지 이어서 한다: 렌즈 플레어 → 배치 하나씩 올리기 → 와이어 → LevelFx (예전 enter 와 같은 순서·내용).
	 * 다 끝났으면 true. 장면 전환 페이드 동안 메인 스레드가 여러 프레임에 나눠 부른다 ({@link #preUpload}, 48 문서 §9); enter 는 남은 것을 한 번에.
	 */
	private boolean glStep(Engine e, long deadline) {
		if (fx != null) {
			return true;
		}
		if (lensFlares == null) {
			lensFlares = new devple.gow.engine.fx.LensFlares(envParams, data, assets); // SCR_LensFlare (46 문서)
		}
		while (pending != null && uploaded < pending.size()) {
			if (System.nanoTime() >= deadline) {
				return false;
			}
			PendingInstance q = pending.get(uploaded++);
			Instance inst = new Instance(q.name(), assets.upload(q.model()), q.sky(), q.off());
			instances.add(inst);
			if (q.shadow() != null) {
				staticShadows.add(new StaticShadow(inst, q.name().toLowerCase(Locale.ROOT), q.shadow(), q.shadowJoints(), q.shadowHidden()));
			}
		}
		if (pending != null) {
			pending = null;
			envParams = null;
			collisionLines = collisionMb.build(GL_LINES);
			volumeLines = volumeMb.build(GL_LINES);
			collisionMb = volumeMb = null;
		}
		if (System.nanoTime() >= deadline) {
			return false;
		}
		java.util.Set<String> skyOwners = new java.util.HashSet<>();
		skyGos.forEach(s -> skyOwners.add(s.name()));
		fx = new LevelFx(e, assets, data, instances, wadPath, skyOwners); // 하늘 GO 이미터는 하늘 공간에서 (GameScene.startSkyEffects)
		return true;
	}

	/**
	 * 장면 전환 페이드 동안 GL 단계 일부를 미리 (48 문서 §9). {@link #prepare} 가 끝난 장면만. 결과는 enter 에서 한 번에 한 것과 같다
	 * (같은 순서로 같은 GL 객체를 만든다 — 바뀌는 것은 어느 프레임에서인가뿐). 다 끝났으면 true.
	 */
	public boolean preUpload(Engine e, long deadline) {
		if (!prepared) {
			return false;
		}
		return glStep(e, deadline);
	}

	private int warmCursor;

	/**
	 * 페이드 동안 (48 문서 §9.7, {@link #preUpload} 뒤): 1×1 화면 밖 FBO 에 모든 배치를 한 번씩 그려 둔다 — 드라이버의 첫 그리기 (텍스처·정점 버퍼
	 * GPU 올리기) 가 첫 화면 프레임에 몰리지 않게. 새 GL 객체는 만들지 않고, 화면·게임 상태·그림자 모으기에는 닿지 않는다. 다 했으면 true.
	 * (반복 act GO 의 스키닝 모델은 미리 만들지 않는다: 보이지 않는 것까지 만들어 ATHN02D 에서 한 틱 280 ms 였다.)
	 */
	public boolean warmGpu(Engine e, long deadline) {
		if (fx == null) {
			return false;
		}
		devple.gow.engine.render.GpuWarmup w = e.renderer().warmup();
		int stage = devple.gow.engine.render.DrawStage.set(devple.gow.engine.render.DrawStage.ALL);
		w.begin();
		try {
			e.renderer().begin3d(cam);
			tmp.identity();
			while (warmCursor < instances.size()) {
				if (System.nanoTime() >= deadline) {
					return false;
				}
				e.renderer().draw(instances.get(warmCursor++).model(), tmp);
			}
			return true;
		} finally {
			w.end();
			devple.gow.engine.render.DrawStage.set(stage);
			FxEnvironment.disableFog(e.renderer().basic());
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
		// 본문 없는 인스턴스 SCR_Sky 의 소유 GO (원작 0x218848 은 스크립트 소유 GO 를 옮긴다 — 본문을 읽지 않음, 16 §1.4):
		// ATHN03A/03AA/03C goaresanimgroup1 (도시 위 거대한 아레스, 템플릿 MDL_AresAnimGroup1 에는 SCR_Sky 가 없다)
		for (String o : env.skyOwners) {
			if (o != null && !o.isBlank()) {
				out.add(o.trim().toLowerCase(Locale.ROOT));
			}
		}
		return out;
	}

	/** 배치가 SCR_Sky 대상인가: 모델 이름(MDL_x)·오브젝트 이름·GO 인스턴스 이름이 SCR_Sky 이름과 같다. */
	static boolean isScrSky(LevelLayout.Placement p, java.util.Set<String> skyNames) {
		String mdl = p.model();
		return (mdl != null && skyNames.contains(mdl.toLowerCase(Locale.ROOT)))
				|| skyNames.contains(p.object().name().toLowerCase(Locale.ROOT))
				|| skyNames.contains(p.instance().name().toLowerCase(Locale.ROOT));
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

	private MeshBuilder buildCollisionLines() {
		MeshBuilder mb = new MeshBuilder();
		for (LevelCollision.Tri t : collision.triangles()) {
			float r = t.floor() ? 0.2f : 1f, g = t.floor() ? 1f : 0.25f, b = 0.2f;
			mb.line(t.a().x, t.a().y, t.a().z, t.b().x, t.b().y, t.b().z, r, g, b);
			mb.line(t.b().x, t.b().y, t.b().z, t.c().x, t.c().y, t.c().z, r, g, b);
			mb.line(t.c().x, t.c().y, t.c().z, t.a().x, t.a().y, t.a().z, r, g, b);
		}
		return mb;
	}

	private MeshBuilder buildVolumeLines() {
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
		return mb;
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
		// 0x152EC8: 거울 관리자 갱신 0x12A6C8 (거울 고르기·반사 카메라) 이 가시 칸 질의보다 먼저 — 거울 뷰가 켜졌으면 반사 눈도 같은 마스크에 누적
		frameMirror = MIRRORS ? chooseMirror(cam.eye) : null;
		updateContextVisibility(cam.eye, frameMirror != null ? frameMirror.reflect(cam.eye) : null);
		fx.beforeRender(e, cam);
		e.renderer().begin3d(cam);
		fx.drawBackground(e, cam);
		fx.beginWorld(e, cam, true);
		// 하늘: 카메라 중심, 깊이 쓰기 끔 → 항상 맨 뒤. 층 1 (SCR_Sky → 0x150738(모델, 1)) 이라 층 2 단계와 무관하게 모든 파트
		frameCam = cam;
		int stage = devple.gow.engine.render.DrawStage.set(devple.gow.engine.render.DrawStage.ALL);
		glDepthMask(false);
		drawSkyObjects(e, cam, fx.env().time());
		if (skyNeighbour != null && skyNeighbour != this) {
			skyNeighbour.drawNeighbourSky(e, cam, fx.env().time());
		}
		glDepthMask(true);
		devple.gow.engine.render.DrawStage.set(stage);
		fx.beginWorld(e, cam, false);
		tmp.identity();
		// 원작처럼 모든 인스턴스의 불투명 패스 뒤에 반투명 패스 (Renderer.drawPass). GOW_PASSORDER=model 이면 이전 (모델마다 두 패스)
		for (int pass = MODEL_PASSES ? 1 : 0; pass < 2; pass++) {
			if (pass == 1 && !MODEL_PASSES && stage != devple.gow.engine.render.DrawStage.LATE) {
				renderMirrors(e, cam); // 불투명 뒤, 반투명 (거울 바닥 자체 포함) 앞
			}
			drawInstances(e, pass);
		}
		renderPosed(e, cam, 0);
		submitStaticShadows();
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

	private void drawInstances(Engine e, int pass) {
		boolean late = devple.gow.engine.render.DrawStage.current() == devple.gow.engine.render.DrawStage.LATE;
		for (Instance i : instances) {
			if (late && !i.model().hasLate()) {
				continue;
			}
			String key = i.name().toLowerCase(Locale.ROOT);
			if (!i.sky() && !fx.hidden(i) && !removed.contains(key) && !posed.containsKey(key) && !ambientKeys.contains(key)
					&& !culled(key) && !(MIRRORS && !MODEL_PASSES && mirrorKeys.contains(key)) && inMainView(key)) {
				Vector3f lc = litCenters.get(key);
				if (lc != null) {
					devple.gow.engine.light.LightUniforms.apply(e.renderer().basic(), litFlags.get(key), lc, 1f);
				}
				e.renderer().setMaterialTimes(matTimes.get(key));
				if (MODEL_PASSES) {
					e.renderer().draw(i.model(), tmp);
				} else {
					e.renderer().drawPass(i.model(), tmp, pass);
				}
				e.renderer().setMaterialTimes(null);
				if (lc != null) {
					devple.gow.engine.light.LightUniforms.off(e.renderer().basic());
				}
			}
		}
	}

	/** 이번 프레임 {@link #render} 의 카메라 ({@link #renderLate} 용). */
	private Camera frameCam;

	/**
	 * 그림자 합성 뒤 단계 (47 §7.4.2, {@link devple.gow.engine.render.DrawStage#LATE}): 게임 씬이 {@code DrawStage.EARLY} 로 {@link #render} 를
	 * 부르고 그림자를 합성한 다음 부른다. 같은 카메라·가시 칸 결과로 층 2 반투명 단계 머티리얼 (가산·뺄셈·flags2 0x100) 과 층 3 만 그린다.
	 */
	public void renderLate(Engine e) {
		Camera cam = frameCam;
		if (cam == null) {
			return;
		}
		e.renderer().begin3d(cam);
		fx.beginWorld(e, cam, false);
		tmp.identity();
		for (int pass = MODEL_PASSES ? 1 : 0; pass < 2; pass++) {
			drawInstances(e, pass);
		}
		renderPosed(e, cam, 0);
		FxEnvironment.disableFog(e.renderer().basic());
	}

	/** 비교용: GOW_MIRROR=off 이면 SCR_Mirror 반사를 그리지 않는다 (이전 동작). */
	private static final boolean MIRRORS = !"off".equals(System.getenv("GOW_MIRROR"));
	/** 디버그: GOW_MIRROR=raw 면 거울 모델을 반사 위에 그리지 않는다 (반사 장면만 확인). */
	private static final boolean MIRROR_RAW = "raw".equals(System.getenv("GOW_MIRROR"));

	/**
	 * SCR_Mirror 반사 (Mirrors 주석, 43 문서 §9·§12). 원작 순서 (0x161A80) [확실]: ① 거울 뷰 (반사 카메라) 층 1–4 를 지운 프레임에 먼저 그린다
	 * (층 2 칸 6 = 값 [0] 후처리 흐림, 칸 7 = Z 0 으로 지움) → ② 주 뷰 층 0 = 거울 소유 모델 (0x150738 모드 0) 을 알파 테스트 없이 GS 블렌드로
	 * 그 위에 (Z 씀) → ③ 노드 0x10 (Z GEQUAL 0 → RGBA 0): 거울 면이 덮지 않은 곳의 반사를 지운다 → ④ 0x12ABA0 (아래 메모) → ⑤ 주 뷰 층 1–4.
	 * 엔진: 거울 화소 스텐실 (③ 과 같은 효과) → 그 화소 깊이를 먼 값 + 지움 색 (①의 바탕) → VP·R 로 거울 뷰 마스크에 맞는 것만 (가시 칸 =
	 * 반사 눈, 0x152EC8) → 값 [0] 이면 흐림 → 거울 깊이 복원 → 층 0 그리기 ({@link devple.gow.engine.render.Renderer#drawLayer0}).
	 * 거울 화소가 하나도 안 보이면 조건부 렌더로 반사 장면을 건너뛴다. 고르지 않은 거울도 층 0 그리기 (반사 없음).
	 */
	private void renderMirrors(Engine e, Camera cam) {
		if (mirrors.isEmpty() || !MIRRORS) {
			return;
		}
		devple.gow.engine.render.Shader basic = e.renderer().basic();
		Matrix4f vp = new Matrix4f(cam.viewProj(e.window().aspect()));
		if (mirrorQuery == 0) {
			mirrorQuery = devple.gow.engine.render.GlStats.query(glGenQueries());
			if (Boolean.getBoolean("gow.debug")) {
				System.out.println("스텐실 비트: " + glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER, GL_STENCIL,
						GL_FRAMEBUFFER_ATTACHMENT_STENCIL_SIZE));
			}
		}
		// 0x12A6C8: 렌더러 중 0x12A330 거리 (앞쪽만) 가 가장 작은 거울 하나만 거울 뷰를 켠다 (render() 에서 미리 골랐다)
		List<Mirrors.Mirror> live = liveMirrors();
		Mirrors.Mirror chosen = frameMirror != null && live.contains(frameMirror) ? frameMirror : null;
		if (System.getProperty("gow.debug.mirror") != null && chosen != lastChosen) {
			StringBuilder sb = new StringBuilder();
			for (Mirrors.Mirror m : live) {
				sb.append(String.format(Locale.ROOT, " %s=%.0f", m.key(), m.distance(cam.eye)));
			}
			System.out.printf(Locale.ROOT, "[거울] 눈 (%.0f, %.0f, %.0f) 고름 %s:%s%n", cam.eye.x, cam.eye.y, cam.eye.z,
					chosen == null ? "없음" : chosen.key(), sb);
		}
		lastChosen = chosen;
		for (Mirrors.Mirror m : live) {
			Instance mi = mirrorOwner(m);
			if (culled(m.key())) {
				continue; // 가시 칸으로 숨은 거울 모델 (고르기는 숨김 bit 54 만 본다 — 0x12A330)
			}
			if (m != chosen) {
				// 거울 뷰가 고르지 않은 거울: 층 0 그리기만 (원작 노드는 거울 뷰가 켜졌을 때만 켜진다, 0x12A998)
				if (!MIRROR_RAW) {
					e.renderer().drawLayer0(mi.model(), tmp.identity());
				}
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
			// 2) 그 화소의 깊이를 먼 값으로 (노드 C: 거울 뷰 Z 를 0 으로 = 먼 값)
			glStencilFunc(GL_EQUAL, 1, 0xFF);
			glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
			glDepthMask(true);
			glDepthFunc(GL_ALWAYS);
			glDepthRange(1, 1);
			e.renderer().drawShape(mi.model(), tmp.identity());
			glDepthRange(0, 1);
			glDepthFunc(GL_LESS);
			glColorMask(true, true, true, true);
			glBeginConditionalRender(mirrorQuery, GL_QUERY_WAIT);
			// 3) 바탕 = 지운 프레임 (거울 뷰는 프레임 맨 앞에 그린다, 0x161A80): 지움 색 + 배경 안개
			if (composite == null) {
				composite = new devple.gow.engine.render.MirrorComposite();
			}
			composite.fill(devple.gow.engine.render.Renderer.CLEAR_R, devple.gow.engine.render.Renderer.CLEAR_G,
					devple.gow.engine.render.Renderer.CLEAR_B); // 원작 지움 색 (검정, 0x29CF64 · 43 §13.4)
			fx.drawBackground(e, cam);
			// 4) 반사 장면 (원작 0x12A4E8: 카메라를 평면에 대해 반사) — 거울 뷰 마스크에 맞는 모델만 (정적 월드는 다시 그리지 않음).
			//    가시 칸 컨텍스트는 거울 뷰 자신의 카메라 위치 (= 반사 눈) 로 (0x152EC8 → 뷰마다 0x16DC60(눈, 뷰 마스크)) [확실]
			//    가시 칸: 반사 눈은 render() 에서 주 뷰 눈과 같은 마스크에 누적 (0x152EC8, 모든 뷰가 같은 집합)
			devple.gow.engine.light.ShadowCasters sc = devple.gow.engine.light.ShadowCasters.current();
			boolean pauseShadows = sc != null && sc.collecting();
			if (pauseShadows) {
				sc.pause(); // 그림자는 주 뷰만 (거울 뷰 마스크에 그림자 서버 항목이 없다 [근사])
			}
			// 거울 뷰엔 그림자 합성이 없다 (0x160CC0 특수 단계는 주 뷰 패스 1 만) → 층 2 단계로 나누지 않고 모두
			int mirrorStage = devple.gow.engine.render.DrawStage.set(devple.gow.engine.render.DrawStage.ALL);
			int vm = m.viewMask();
			Matrix4f refl = m.reflection();
			Matrix4f rvp = new Matrix4f(vp).mul(refl);
			float[] cp = m.clipPlane(cam.eye);
			glFrontFace(GL_CW); // 반사 = 행렬식 −1 → 감김 반전 (면 컬링을 켠 패스도 같은 면을 남긴다)
			e.renderer().begin3d(cam);
			fx.beginWorld(e, cam, false);
			basic.set("uViewProj", rvp);
			basic.set("uClipPlane", cp[0], cp[1], cp[2], cp[3]);
			glEnable(GL_CLIP_DISTANCE0);
			tmp.identity();
			for (int pass = 0; pass < 2; pass++) {
				for (Instance i : instances) {
					String key = i.name().toLowerCase(Locale.ROOT);
					if ((viewMasks.getOrDefault(key, Mirrors.LEVEL_MODEL) & vm) != 0 && i != mi && !fx.hidden(i) && !removed.contains(key)
							&& !posed.containsKey(key) && !ambientKeys.contains(key) && !culled(key)) {
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
			devple.gow.engine.render.DrawStage.set(mirrorStage);
			if (pauseShadows) {
				sc.resume();
			}
			// 5) 값 [0] ≠ 0: 거울 뷰 후처리 (층 2 칸 6, 0x120498 → 0x11FEA0 × 2, 계수 0.5) — 거울 화소만
			if (m.blur() && !NO_MIRROR_BLUR) {
				composite.blur(e.window().width(), e.window().height(), 0.5f, 2);
			}
			glEndConditionalRender();
			// 6) 거울 깊이 복원 (뒤에 그리는 캐릭터·이펙트가 거울 면에 가려지도록)
			e.renderer().begin3d(cam);
			fx.beginWorld(e, cam, false);
			basic.set("uViewProj", vp);
			glColorMask(false, false, false, false);
			glDepthFunc(GL_ALWAYS);
			e.renderer().drawShape(mi.model(), tmp.identity());
			glColorMask(true, true, true, true);
			glDisable(GL_STENCIL_TEST);
			// 7) 층 0: 거울 모델을 반사 위에 GS 식 그대로 (알파 테스트 없이 (Cs−Cd)·As+Cd) — 반사가 비치는 정도 = 1 − As
			if (!MIRROR_RAW) {
				glDepthFunc(GL_LEQUAL);
				e.renderer().drawLayer0(mi.model(), tmp.identity());
				glDepthFunc(GL_LESS);
			}
		}
		basic.use();
		basic.set("uViewProj", vp);
	}

	/** 비교용: GOW_MIRRORBLUR=off 면 값 [0] 후처리 흐림을 끈다. */
	private static final boolean NO_MIRROR_BLUR = "off".equals(System.getenv("GOW_MIRRORBLUR"));
	private devple.gow.engine.render.MirrorComposite composite;
	private Mirrors.Mirror lastChosen;
	/** 이번 프레임 거울 뷰를 켠 거울 (없으면 null). */
	private Mirrors.Mirror frameMirror;

	/** 거울 후보: 소유 인스턴스가 있고 제거·효과 숨김이 아닌 것 (원작 0x12A330 은 모델 숨김 bit 54 만 본다 — 가시 칸 컨텍스트는 뷰 비트라 무관). */
	private List<Mirrors.Mirror> liveMirrors() {
		List<Mirrors.Mirror> live = new ArrayList<>();
		for (Mirrors.Mirror m : mirrors) {
			Instance mi = mirrorOwner(m);
			if (mi != null && !removed.contains(m.key()) && !fx.hidden(mi) && !dbgHidden(m.key())) {
				live.add(m);
			}
		}
		return live;
	}

	private Instance mirrorOwner(Mirrors.Mirror m) {
		for (Instance i : instances) {
			if (i.name().equalsIgnoreCase(m.key())) {
				return i;
			}
		}
		return null;
	}

	/** 0x12A6C8: 이번 프레임 거울 뷰를 켤 거울 (없으면 null). */
	private Mirrors.Mirror chooseMirror(Vector3f eye) {
		return mirrors.isEmpty() ? null : Mirrors.nearest(liveMirrors(), eye);
	}

	/** 이번 프레임 거울 뷰 거울 이름 (없으면 null) — 테스트·디버그. */
	public String frameMirrorKey() {
		return frameMirror == null ? null : frameMirror.key();
	}

	/** 디버그: GOW_HIDE=인스턴스,… 를 그리지 않는다 ("*" = 하늘 밖 전부, "sky" = 하늘). 화면을 덮는 메시 찾기용 (43 문서). */
	private static final java.util.Set<String> DBG_HIDE = System.getenv("GOW_HIDE") == null ? java.util.Set.of()
			: java.util.Set.of(System.getenv("GOW_HIDE").toLowerCase(Locale.ROOT).split(","));

	/** 비교용: GOW_STARTPOSE=off 이면 배치 오브젝트를 바인드 자세로 굽고 반복 act 를 재생하지 않는다 (이전 동작). */
	private static final boolean START_POSE = !"off".equals(System.getenv("GOW_STARTPOSE"));

	/** 비교용: GOW_PASSORDER=model 이면 모델마다 두 패스 (이전 동작). */
	private static final boolean MODEL_PASSES = "model".equals(System.getenv("GOW_PASSORDER"));

	/** 비교용: GOW_VISCELLS=off 이면 가시 칸 컨텍스트를 무시하고 전부 그린다 (이전 동작). */
	private static final boolean VIS_CELLS = !"off".equals(System.getenv("GOW_VISCELLS"));

	/** 이번 프레임 가시 칸으로 숨은 인스턴스 (소문자, {@link LevelData#contextHidden}). */
	private java.util.Set<String> ctxHidden = java.util.Set.of();
	private final Vector3f ctxEye = new Vector3f(Float.NaN, 0, 0);
	private Vector3f ctxMirrorEye;

	/**
	 * 원작 0x152EC8: 매 프레임 마스크를 비우고 (0x16D6F0) 활성 뷰포트 카메라 위치로 칸 질의 (0x16DC60) 뒤 컨텍스트마다 뷰 마스크를 켜고 끈다
	 * (0x16D6F8 → 0x134980: 컨텍스트 항목 중 MDL 0xF / PRT 0x13 / 0x1C / SHG 0x27 의 +0x48). 거울 뷰가 켜졌으면 반사 눈도 같은 마스크에 누적
	 * (모든 뷰가 같은 집합, 43 §12.3).
	 */
	private void updateContextVisibility(Vector3f eye) {
		updateContextVisibility(eye, null);
	}

	/**
	 * @param mirrorEye 거울 뷰가 켜졌으면 반사 눈 (뷰 목록에서 거울 뷰가 먼저 — 0x12A2A8 이 Engine_Init 에서 목록 맨 앞에 넣는다 [추정: 주 뷰가 그 전에 들어감]), 아니면 null
	 */
	private void updateContextVisibility(Vector3f eye, Vector3f mirrorEye) {
		if (!VIS_CELLS || eye.equals(ctxEye) && java.util.Objects.equals(mirrorEye, ctxMirrorEye)) {
			return;
		}
		ctxEye.set(eye);
		ctxMirrorEye = mirrorEye == null ? null : new Vector3f(mirrorEye);
		java.util.Set<String> h = mirrorEye == null ? data.contextHidden(eye.x, eye.y, eye.z)
				: data.contextHidden(new float[][]{{mirrorEye.x, mirrorEye.y, mirrorEye.z}, {eye.x, eye.y, eye.z}});
		if (System.getProperty("gow.debug.vis") != null && !h.equals(ctxHidden)) {
			System.out.printf(Locale.ROOT, "[가시 칸] %s 눈 (%.0f, %.0f, %.0f) 숨김 %d: %s%n", data.wad.name(), eye.x, eye.y, eye.z, h.size(),
					new java.util.TreeSet<>(h));
		}
		ctxHidden = h;
		fx.setContextHidden(ctxHidden);
	}

	/** 지금 가시 칸으로 숨은 인스턴스 (소문자) — 테스트·디버그. */
	public java.util.Set<String> contextHidden(Vector3f eye) {
		updateContextVisibility(eye);
		return ctxHidden;
	}

	private boolean culled(String key) {
		return dbgHidden(key) || ctxHidden.contains(key.toLowerCase(Locale.ROOT));
	}

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
		boolean late = devple.gow.engine.render.DrawStage.current() == devple.gow.engine.render.DrawStage.LATE;
		for (Map.Entry<String, float[][]> en : posed.entrySet()) {
			if (removed.contains(en.getKey()) || culled(en.getKey()) || !view.test(en.getKey()) || late && !posedDrawsLate(en.getKey())) {
				continue;
			}
			drawPosed(e, cam, en.getKey(), en.getValue(), posedHidden.get(en.getKey()));
		}
		// 반복 act 배치: 시작 시간 + 레벨 시간 (스크립트 Animator 가 잡으면 posed 가 우선)
		float now = fx.env().time();
		for (Ambient a : ambient) {
			if (posed.containsKey(a.key()) || removed.contains(a.key()) || culled(a.key()) || fxHidden(a.key()) || !view.test(a.key())
					|| late && !posedDrawsLate(a.key())) {
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

	/**
	 * 다른 슬롯 레벨 장면 (GameScene 이 매 프레임 건다, 없으면 null): 하늘 단계에 그 레벨의 하늘 GO 도 그린다. 원작은 두 슬롯 레벨이 함께 상주해
	 * 각 슬롯의 SCR_Sky GO 가 자기 가시 칸 결과대로 그려진다 — ATHN03AA 가 현재인 채 눈이 ATHN03A 쪽 (z &lt; −40) 으로 가면 ATHN03AA 의 하늘
	 * (CXT_bvsky) 은 칸 밖이라 숨고 ATHN03A 의 같은 하늘이 보인다 (sparky 영상 camSpecialSparky 가 원형 제단 위로 나가는 동안, 계단 위에서 ATHN03A 로
	 * 걸어 들어가는 동안). 엔진은 현재 레벨 하늘만 그려 하늘이 검게 비었다 (43 §16) [확실: 가시 칸 데이터 / 근사: 두 슬롯 렌더 구조].
	 */
	public LevelScene skyNeighbour;

	/** 하늘 GO (정적 + 애니) — 가시 칸으로 숨은 것 제외, 깊이 쓰기는 호출자가 끈 상태. time = 애니 하늘의 효과 시간. */
	private void drawSkyObjects(Engine e, Camera cam, float time) {
		for (Instance i : instances) {
			if (i.sky() && !dbgHidden("sky") && !ctxHidden.contains(i.name().toLowerCase(Locale.ROOT))) {
				tmp.translation(cam.eye.x - i.offset().x, cam.eye.y - i.offset().y, cam.eye.z - i.offset().z);
				e.renderer().draw(i.model(), tmp);
			}
		}
		renderSkyAmbient(e, cam, time);
	}

	/**
	 * 다른 슬롯으로서 하늘 GO 를 그린다 ({@link #skyNeighbour}): 이 레벨의 가시 칸 (같은 눈) 으로 거르고, 애니 하늘은 현재 레벨의 효과 시간으로
	 * (다른 슬롯 장면은 갱신하지 않는다 — 원작 GO 애니는 두 슬롯 모두 같은 시계).
	 */
	void drawNeighbourSky(Engine e, Camera cam, float time) {
		updateContextVisibility(cam.eye);
		drawSkyObjects(e, cam, time);
	}

	/**
	 * 애니 하늘 GO ({@link SkyAmbient}): 정적 하늘 뒤, 월드 앞. 관절 0 이 카메라 눈에 오게 옮긴 관절 월드로 스키닝하고 (0x218848),
	 * 안개는 하늘과 같은 배경 모드. 모델 자체 가림을 위해 깊이를 쓰고, 다 그린 뒤 깊이만 지워 월드 지오메트리가 하늘 거리 깊이에 가리지 않게 한다
	 * (정적 하늘은 깊이를 쓰지 않으므로 지운 깊이는 프레임 시작과 같다).
	 *
	 * @param now 애니 하늘의 효과 시간 (다른 슬롯 하늘은 현재 레벨 시간)
	 */
	private void renderSkyAmbient(Engine e, Camera cam, float now) {
		if (skyAmbient.isEmpty() || dbgHidden("sky")) {
			return;
		}
		if (skinned == null) {
			skinned = new devple.gow.engine.render.Shader("skinned.vert", "basic.frag");
		}
		fx.env().applyFog(skinned, cam, e.window().height());
		fx.env().setBackground(skinned, true, e.window().height());
		glDepthMask(true);
		boolean drew = false;
		for (SkyAmbient s : skyAmbient) {
			Ambient a = s.anim();
			if (removed.contains(a.key()) || ctxHidden.contains(a.key()) || fxHidden(a.key())) {
				continue;
			}
			float t = a.start() + now;
			float[][] w = skyJoints(a, t, s.off(), cam.eye);
			devple.gow.engine.asset.AnimatedModel m;
			if (posedModels.containsKey(a.key())) {
				m = posedModels.get(a.key());
			} else {
				m = posedModel(a.key());
				if (m != null) {
					m.sky = true;
				}
				posedModels.put(a.key(), m);
			}
			if (m == null) {
				continue;
			}
			float len = a.act().duration;
			m.hiddenJoints = a.act().hiddenJoints(a.skeleton(), ((t % len) + len) % len);
			m.draw(cam, e.window().aspect(), tmp.identity(), w, e.renderer().white(), -1f, 1f);
			m.hiddenJoints = null;
			drew = true;
		}
		fx.env().setBackground(skinned, false, e.window().height());
		FxEnvironment.disableFog(skinned);
		e.renderer().basic().use();
		if (drew) {
			glDepthMask(true); // glClear 의 깊이 지우기는 쓰기 마스크를 따른다
			glClear(GL_DEPTH_BUFFER_BIT);
		}
		glDepthMask(false);
	}

	/** 애니 하늘 GO 의 시간 t 관절 월드 — 관절 0 (시작 배치의 관절 0 월드 off) 을 눈 위치로 옮긴다 (0x218848: GO 위치 = 카메라). */
	static float[][] skyJoints(Ambient a, float t, Vector3f off, Vector3f eye) {
		float[][] lw = a.act().sample(a.skeleton(), t, true).world(a.skeleton());
		float[][] w = new float[lw.length][];
		float dx = eye.x - off.x, dy = eye.y - off.y, dz = eye.z - off.z;
		for (int i = 0; i < lw.length; i++) {
			w[i] = Mat4.mul(lw[i], a.inst());
			w[i][12] += dx;
			w[i][13] += dy;
			w[i][14] += dz;
		}
		return w;
	}

	private boolean fxHidden(String key) {
		for (Instance i : instances) {
			if (i.name().equalsIgnoreCase(key)) {
				return fx.hidden(i);
			}
		}
		return false;
	}

	/** 합성 뒤 단계에 그릴 파트가 있는 자세 GO 인가 (없으면 관절 샘플링부터 건너뛴다). 모델을 아직 안 만들었으면 true. */
	private boolean posedDrawsLate(String key) {
		if (!posedModels.containsKey(key)) {
			return true;
		}
		devple.gow.engine.asset.AnimatedModel m = posedModels.get(key);
		return m != null && m.drawsLate();
	}

	private void drawPosed(Engine e, Camera cam, String key, float[][] joints, boolean[] hidden) {
		// 모델이 없는 이름 (null) 도 기억한다 — computeIfAbsent 는 null 을 넣지 않아 그런 이름은 프레임마다 전체 배치의 관절 월드를 다시 만들었다
		// (48 문서 §9: ATHN01A 할당 1 위 Mat4.fromEuler/mul 의 출처). posedModel 은 같은 이름에 같은 결과라 값은 같다
		devple.gow.engine.asset.AnimatedModel m;
		if (posedModels.containsKey(key)) {
			m = posedModels.get(key);
		} else {
			m = posedModel(key);
			posedModels.put(key, m);
		}
		if (m != null) {
			m.hiddenJoints = hidden;
			m.matTimes = matTimes.get(key);
			m.draw(cam, e.window().aspect(), tmp.identity(), joints, e.renderer().white(), -1f, 1f);
			m.hiddenJoints = null;
			m.matTimes = null;
		}
	}

	/** 스크립트 재질 애니 시간 (소유 GO 소문자 → 재질 이름 → 시간, {@code nav.Platforms#materialTimes}) — 자세·정지 그리기 모두. */
	private volatile Map<String, Map<String, Float>> matTimes = Map.of();

	public void setMaterialTimes(Map<String, Map<String, Float>> times) {
		matTimes = times != null ? times : Map.of();
	}

	private devple.gow.engine.asset.AnimatedModel posedModel(String key) {
		for (LevelLayout.Placement p : data.layout.placements()) {
			if (p.instance().name().equalsIgnoreCase(key) && p.model() != null && p.object().skeleton() != null) {
				GowModel gm = assets.gowModel(p.model());
				if (gm != null && p.object().skeleton().size() <= devple.gow.engine.asset.AnimatedModel.MAX_JOINTS) {
					var m = new devple.gow.engine.asset.AnimatedModel(assets, gm, p.object().skeleton(), skinned);
					// 레벨 GO 그림자 (47 문서 §11): 같은 WAD 의 SHG_<모델> 이 있으면 renShadowServer 오브젝트 — 내비게이션 갱신이 없는 GO 는
					// 모드 0 (밀어내기). SCR_Shadow (0x2465B0 → 0x1BF5C0 → 0x1BF680) 가 붙은 GO 는 받는 면 질의를 해 모드 1 (판) [확실]
					m.shadowKey = key;
					if (shadowOwners.contains(key.toLowerCase(Locale.ROOT))) {
						m.shadowMode = devple.gow.engine.light.ShadowVolume.MODE_SLAB;
					}
					return m;
				}
			}
		}
		return null;
	}

	/** 시작 자세로 구운 스키닝 GO 중 SHG_ 가 있는 것 (그림자만 따로 모은다). */
	private record StaticShadow(Instance inst, String key, devple.gow.engine.light.ShadowGeometry geometry, float[][] joints, boolean[] hidden) {
	}

	private final List<StaticShadow> staticShadows = new ArrayList<>();
	private final Map<String, java.util.Optional<devple.gow.engine.light.ShadowGeometry>> shgCache = new HashMap<>();
	private static final Matrix4f IDENTITY = new Matrix4f();

	/**
	 * 렌더러 0x162F20 은 그림자 서버의 모든 오브젝트를 돈다 (GO 가 움직이든 아니든) — 구운 시작 자세 GO 도 같은 관절로 그림자를 낸다.
	 * 자세 (posed) · 반복 act GO 는 {@link devple.gow.engine.asset.AnimatedModel#draw} 가 넘긴다. 모드 0, SCR_Shadow 소유자는 1.
	 */
	private void submitStaticShadows() {
		devple.gow.engine.light.ShadowCasters sc = devple.gow.engine.light.ShadowCasters.current();
		if (sc == null || !sc.collecting() || staticShadows.isEmpty()) {
			return;
		}
		for (StaticShadow s : staticShadows) {
			if (posed.containsKey(s.key()) || ambientKeys.contains(s.key()) || removed.contains(s.key()) || culled(s.key()) || fx.hidden(s.inst())
					|| !inMainView(s.key())) {
				continue;
			}
			int mode = shadowOwners.contains(s.key()) ? devple.gow.engine.light.ShadowVolume.MODE_SLAB : devple.gow.engine.light.ShadowVolume.MODE_EXTRUDE;
			sc.submit(s, null, s.geometry(), s.joints(), s.hidden(), IDENTITY,
					devple.gow.engine.light.LightServer.jointSphere(s.joints(), IDENTITY, new org.joml.Vector4f()), mode, s.key());
		}
	}

	/** 그림자 모으기 대상 레벨 GO 이름 (소문자) — 테스트. */
	public List<String> staticShadowKeys() {
		return staticShadows.stream().map(StaticShadow::key).toList();
	}

	/**
	 * 레벨 WAD 자체 AI1 템플릿의 "Instance Name" 오브젝트 (소문자, ATHN01D aoxf1 …, 36 문서 §4): 원작은 이 오브젝트를 복사해 적으로 스폰하고
	 * 템플릿 자체는 그리지 않는다 [추정] — 엔진은 원점의 템플릿을 구운 메시로 그리지만 (기존 동작) 그림자는 모으지 않는다.
	 */
	static java.util.Set<String> aiTemplateObjects(devple.gow.tools.wad.WadFile wad) {
		devple.gow.engine.game.Tweaks t = new devple.gow.engine.game.Tweaks();
		try {
			t.load(wad);
		} catch (RuntimeException ex) {
			return java.util.Set.of();
		}
		java.util.Set<String> out = new java.util.HashSet<>();
		for (String node : t.nodes("/TweakTemplates/AI1/")) {
			String n = t.s(node, "Instance Name", null);
			if (n != null && !n.isEmpty()) {
				out.add(n.toLowerCase(Locale.ROOT));
			}
		}
		return out;
	}

	/** SCR_Shadow 소유 GO (소문자). */
	private java.util.Set<String> shadowOwners = java.util.Set.of();

	/** 인스턴스 스크립트 target 의 소유 GO 이름 (소문자). */
	static java.util.Set<String> scriptOwners(devple.gow.tools.fx.EnvParams env, String target) {
		java.util.Set<String> out = new java.util.HashSet<>();
		for (devple.gow.tools.fx.EnvParams.Raw r : env.others) {
			if (target.equals(r.target()) && r.owner() != null && !r.owner().isBlank()) {
				out.add(r.owner().trim().toLowerCase(Locale.ROOT));
			}
		}
		return out;
	}

	/** SCR_Shadow 소유 GO (소문자) — 테스트. */
	public java.util.Set<String> shadowOwners() {
		return shadowOwners;
	}

	/**
	 * 다른 슬롯(이웃) 레벨로 그리기: 하늘·충돌선 없이 지오메트리만, 주 레벨이 정한 카메라로. 원작은 두 슬롯 레벨이 함께 상주하며 둘 다 그린다
	 * (36/37 문서: ATHN02D 메두사 방 = ATHN02C 지오메트리, 이음 전환 경계 너머).
	 */
	public void renderNeighbour(Engine e, Camera c) {
		boolean late = devple.gow.engine.render.DrawStage.current() == devple.gow.engine.render.DrawStage.LATE;
		if (!late) { // 합성 뒤 단계 (47 §7.4.2) 는 같은 프레임의 가시 칸 결과를 그대로
			updateContextVisibility(c.eye); // 원작 질의는 충돌 월드의 시트 전부 (두 슬롯) 를 같은 카메라로 돈다 (0x11F098)
		}
		e.renderer().begin3d(c);
		fx.beginWorld(e, c, false);
		tmp.identity();
		for (int pass = 0; pass < 2; pass++) {
			for (Instance i : instances) {
				if (late && !i.model().hasLate()) {
					continue;
				}
				String key = i.name().toLowerCase(Locale.ROOT);
				if (!i.sky() && !fx.hidden(i) && !removed.contains(key) && !posed.containsKey(key) && !culled(key) && inMainView(key)) {
					Map<String, Float> mt = matTimes.get(key);
					e.renderer().setMaterialTimes(mt);
					e.renderer().drawPass(i.model(), tmp, pass);
					e.renderer().setMaterialTimes(null);
				}
			}
		}
		if (!posed.isEmpty()) { // 이웃 스크립트 Animator·이음으로 넘겨받은 자세 (열린 문, 43 §13.8)
			if (skinned == null) {
				skinned = new devple.gow.engine.render.Shader("skinned.vert", "basic.frag");
			}
			fx.env().applyFog(skinned, c, e.window().height());
			for (Map.Entry<String, float[][]> en : posed.entrySet()) {
				if (!removed.contains(en.getKey()) && !culled(en.getKey()) && inMainView(en.getKey())) {
					drawPosed(e, c, en.getKey(), en.getValue(), posedHidden.get(en.getKey()));
				}
			}
			FxEnvironment.disableFog(skinned);
		}
		FxEnvironment.disableFog(e.renderer().basic());
	}

	/**
	 * {@link #exit} 를 {@link devple.gow.engine.render.GlDeferred} 로 미룬다 (48 문서 §9: 씬 교체 프레임의 GL 지우기 2–9 ms) — 같은 순서로, 배치는
	 * 32 개씩 나눠. GL 해제뿐이라 게임에 보이는 차이는 없다. 동기 모드 (gow.syncLoad) 면 바로 exit.
	 */
	public void exitDeferred(Engine e) {
		if (!devple.gow.engine.asset.Loader.async()) {
			exit(e);
			return;
		}
		List<Runnable> steps = new ArrayList<>();
		steps.add(() -> {
			fx.close();
			lensFlares.close();
			collisionLines.close();
			volumeLines.close();
		});
		for (int i = 0; i < instances.size(); i += 32) {
			List<Instance> part = instances.subList(i, Math.min(instances.size(), i + 32));
			steps.add(() -> part.forEach(in -> in.model().close()));
		}
		steps.add(() -> exitRest(e));
		devple.gow.engine.render.GlDeferred.addAll(steps);
	}

	@Override
	public void exit(Engine e) {
		fx.close();
		lensFlares.close();
		collisionLines.close();
		volumeLines.close();
		instances.forEach(i -> i.model().close());
		exitRest(e);
	}

	private void exitRest(Engine e) {
		// 레벨마다 새로 만드는 GL 객체도 함께 (48 문서 §4: 전환마다 셰이더 프로그램·쿼리·자세 GO 메시가 남았다)
		posedModels.values().forEach(m -> {
			if (m != null) {
				m.close();
			}
		});
		posedModels.clear();
		if (skinned != null) {
			skinned.close();
			skinned = null;
		}
		if (composite != null) {
			composite.close();
			composite = null;
		}
		if (mirrorQuery != 0) {
			devple.gow.engine.render.GlStats.delQuery(mirrorQuery);
			mirrorQuery = 0;
		}
		assets.close();
	}
}
