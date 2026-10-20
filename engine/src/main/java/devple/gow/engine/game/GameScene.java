package devple.gow.engine.game;

import devple.gow.engine.asset.AnimatedModel;
import devple.gow.engine.asset.WadAssets;
import devple.gow.engine.combat.EnemyFactory;
import devple.gow.engine.combat.EnemyFighter;
import devple.gow.engine.core.Engine;
import devple.gow.engine.input.Input;
import devple.gow.engine.input.PadButton;
import devple.gow.engine.level.LevelCollision;
import devple.gow.engine.level.LevelScene;
import devple.gow.engine.render.Camera;
import devple.gow.engine.render.Shader;
import devple.gow.engine.scene.Scene;
import devple.gow.engine.script.ScriptActor;
import devple.gow.engine.script.ScriptEntity;
import devple.gow.engine.script.ScriptListener;
import devple.gow.engine.script.ScriptWorld;
import devple.gow.engine.ui.HudBinding;
import devple.gow.engine.ui.UiLayer;
import devple.gow.engine.ui.UiVariables;
import devple.gow.tools.level.LevelData;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 통합 게임 씬: 원본 레벨(배치·충돌·카메라) + 크레토스(이동·전투·성장) + 레벨 스크립트(ScriptWorld) + 적 + 원작 HUD.
 * <ul>
 *   <li>지면: 충돌 시트의 바닥 높이 추종 + 중력 (/GlobGame/ Gravity 50 m/s², 1m = 16 유닛, 최대 낙하 Sold+0xDC — 38 문서 §2.2)</li>
 *   <li>벽: 허리 높이 구(반지름 12) 밀어내기</li>
 *   <li>카메라: CMV 볼륨이 고른 레벨 카메라 위치에서 크레토스를 바라봄, 없으면 추적 카메라</li>
 *   <li>스크립트: 메시지 → HUD 메시지 창, 체크포인트 → 재시작 위치, 데스존 → 사망, LoadWad/Warp/Load → 레벨 전환, SpawnEnemy → 적 생성</li>
 *   <li>START = 원작 일시정지 메뉴</li>
 * </ul>
 */
public final class GameScene implements Scene {

	public static final float UNITS_PER_METER = 16f;
	private static final float GRAVITY = devple.gow.engine.nav.HeroNavigator.GRAVITY;
	private static final float STEP = 14f;

	/**
	 * 다른 레벨에서 넘어올 때의 도착 정보 (36 문서 §3). kind: SEAMLESS/WALK = 위치·방향 유지(pos, yaw),
	 * WARP = from 레벨 이름으로 도착 Start 를 찾는다. from = 떠나온 레벨 (SEAMLESS/WALK 면 다른 슬롯에 남아 충돌이 유지됨).
	 */
	public record Arrival(LevelLink.Kind kind, String from, float[] pos, float yaw, devple.gow.engine.level.CameraRig.Handoff camera,
						  List<Resident> resident) {
		public Arrival(LevelLink.Kind kind, String from, float[] pos, float yaw) {
			this(kind, from, pos, yaw, null, null);
		}

		public Arrival(LevelLink.Kind kind, String from, float[] pos, float yaw, devple.gow.engine.level.CameraRig.Handoff camera) {
			this(kind, from, pos, yaw, camera, null);
		}
	}

	/**
	 * 이음 때 두 슬롯 레벨의 상태 — 원작은 LoadCheck 가 "현재 레벨" 표시만 바꾸고 두 레벨은 슬롯에 그대로 산다 (36 §2, 30 §7). 엔진은 두 레벨을
	 * 새로 읽으므로 화면에 남는 것만 넘긴다: Vis 숨김 인스턴스 + Animator 오브젝트 자세 (열린 문, 43 §13.8) [근사: 스크립트 변수·충돌 켬/끔은 새로].
	 */
	public record Resident(String wad, java.util.Set<String> hidden, List<devple.gow.engine.nav.Platforms.State> anims) {
	}

	/** 스폰된 적 한 마리 (전투 + 렌더 모델 + 스크립트 액터). */
	private record Enemy(EnemyFighter fighter, AnimatedModel model, ScriptActor actor, EnemyFactory.Kind kind) {
	}

	private final GameSession session;
	private final String levelName;
	private final String entry;
	private final float autoRun;
	private LevelScene level;
	private LevelCollision col;
	private HeroRig hero;
	private UiLayer hud;
	private ScriptWorld world;
	private final HudBinding hudBinding = new HudBinding();
	private final Camera cam = new Camera();
	private devple.gow.engine.level.CameraRig cameraRig;

	/**
	 * 영웅 이동 상태 → 원작 +0x174 비트 (카메라 후보 마스크 0x1EE510 의 입력, 30 문서 §8): 지상 0x1, 점프 공중 0x4, 떨어짐 0x8, 턱 0x10,
	 * 벽·좁은 턱·사다리 0x20, 물 0x80, 줄·수평 줄·짚라인 0x400. 상자 밀기·상황 동작은 지상 [추정].
	 */
	private int heroStateBits() {
		var n = nav != null ? nav.navigator : null;
		devple.gow.engine.nav.NavState st = n != null ? n.state() : devple.gow.engine.nav.NavState.GROUND;
		return switch (st) {
			case LEDGE_HANG -> 0x10;
			case WALL_CLIMB, WALL_WALK, LADDER -> 0x20;
			case ROPE, ROPE_TRAVERSE, ZIPLINE -> 0x400;
			case SWIM -> 0x80;
			default -> hero.ctl.grounded ? 0x1 : hero.ctl.jumped() || n != null && n.airJump() ? 0x4 : 0x8;
		};
	}
	private final Vector3f spawn = new Vector3f();
	private final Vector3f checkpoint = new Vector3f();
	private float spawnYaw;
	private float camYaw;
	private boolean paused;
	/** 파워업(업그레이드) 메뉴 열림: 메뉴 스크립트가 투자를 처리하므로 HUD 값 쓰기를 멈춘다. */
	private boolean powerUp;
	/** 세이브 포인트: 저장 질문(SaveGameMenu) 대기 중 / 메모리카드 화면 진행 중 (22 문서 §7). */
	private boolean saveAsk;
	private MemoryCardFlow saveFlow;
	private final devple.gow.engine.ui.MinigameIcon minigameIcon = new devple.gow.engine.ui.MinigameIcon();
	private float time;
	private float messageTimer;
	/** 다른 슬롯 레벨 렌더 (원작은 두 슬롯 모두 그림). */
	private LevelScene otherScene;
	/** 부서지는 물체 (Breakable / Brkb, 41 문서). */
	private Breakables breakables;
	/** 떨어지는 중인 적의 수직 속도. */
	private final java.util.Map<EnemyFighter, Float> enemyFall = new java.util.IdentityHashMap<>();
	private boolean otherScenePending;
	/** 다른 슬롯 레벨 스크립트가 Vis 로 숨긴 인스턴스 (소문자, 렌더 스레드가 otherScene 에 건다). 다른 슬롯이 바뀌면 비운다. */
	private final java.util.Set<String> otherHidden = java.util.concurrent.ConcurrentHashMap.newKeySet();
	/** 다른 슬롯 레벨 Animator 오브젝트 자세 (이름 → 관절 월드, 숨김 관절) — 렌더 스레드가 otherScene 에 건다. */
	private final Map<String, Object[]> otherPosed = new java.util.concurrent.ConcurrentHashMap<>();
	/** 표시 중인 메시지 번호 (-1 없음): 닫힐 때 이벤트 1040(번호) 를 플레이어가 보낸다 (원작 0x1B8ECC/0x1B8F98/0x1B9170). */
	private int messageId = -1;
	/** 열린 질문 메시지 번호 (Internal 0xB, −1 = 없음), 선택지 수, 지금 선택 (1 부터). */
	private int questionId = -1, questionChoices, questionChoice;
	private float stepTimer;
	private boolean wasAir;
	private boolean leaving;
	/** 원작 레벨 슬롯 2개 (LoadWad/LoadCheck/Warp 판정). */
	private LevelLink link;
	/** 도착 정보 (null = 새 게임/직접 시작/불러오기). */
	private Arrival arrival;
	/** 다른 슬롯 레벨의 충돌 (원작은 이웃 레벨도 상주 → 경계에서 바닥이 이어진다). null = 없음. */
	private String otherName;
	/** 이 레벨의 바닥을 가진 숙주 레벨 (ATHN02D → ATHN02C). 그 바닥을 밟아도 WALK 전환하지 않는다. */
	private String hostLevel;
	private LevelCollision otherCol;
	private LevelData otherData;
	/** 기어오르기·줄·발판·R1 오브젝트·이웃 레벨 스크립트 (38 문서). */
	private SceneNav nav;
	private float otherFloorTime;
	private final Map<String, EnemyFactory.Kind> kinds = new HashMap<>();
	private final List<Enemy> enemies = new ArrayList<>();
	/** 생물끼리 밀어내기 참여 몸 (영웅 + 적, 프레임마다 다시 채움 — 32 문서 §생물끼리 밀어내기). */
	private final List<devple.gow.engine.combat.BodyPush.Body> bodies = new ArrayList<>();
	/** 사운드만 올린 리소스 WAD (exit 때 내림). */
	private final List<String> soundOnly = new ArrayList<>();
	/** 사운드 전용 WAD 의 AI1 템플릿 번호 (스폰하지 않음). */
	private final java.util.Set<Integer> soundOnlyTemplates = new java.util.HashSet<>();
	private Shader enemyShader;
	private Tweaks orbTweaks;
	private devple.gow.engine.combat.GrabSync grab;
	private devple.gow.engine.fx.MagicFx magicFx;
	private final Random rnd = Engine.LOCKSTEP ? new Random(1) : new Random(); // 검증 lockstep 이면 고정 씨앗
	/** 보스전 (37 문서, 없으면 null) / 전투 ↔ 스크립트 이벤트 다리 (1035/1047/1039/1019/1054). */
	private devple.gow.engine.boss.BossFight boss;
	private devple.gow.engine.boss.CombatScriptBridge bridge;
	private devple.gow.engine.boss.BossArena arena;
	/** 레벨 WAD 의 FLP_&lt;레벨&gt; (보스 체력 미터 등, 없으면 null) — HUD 와 변수 공유. */
	private UiLayer levelHud;
	private final devple.gow.engine.ui.EnemyMeterBinding enemyMeters = new devple.gow.engine.ui.EnemyMeterBinding();

	/** 불러오기 시 시작 위치 (null 이면 진입점/시작 인스턴스). */
	private float[] loadPos;
	private float loadYaw;
	/** 레벨에 생성된 세이브 포인트 위치 (스크립트 Creator "savepoint"). */
	private final List<Vector3f> savePoints = new ArrayList<>();

	public GameScene(GameSession session, SaveGame save) {
		this(session, save.level, null, 0);
		this.loadPos = save.pos;
		this.loadYaw = save.yaw;
	}

	public GameScene(GameSession session, String levelName, String entry, float autoRun) {
		this.session = session;
		this.levelName = levelName.toUpperCase();
		this.entry = entry;
		this.autoRun = autoRun;
		prefetch();
	}

	/** 레벨 전환으로 들어옴. */
	public GameScene(GameSession session, String levelName, Arrival arrival) {
		this(session, levelName, null, 0);
		this.arrival = arrival;
		if (devple.gow.engine.asset.Loader.async() && arrival != null && arrival.from() != null
				&& (arrival.kind() == LevelLink.Kind.SEAMLESS || arrival.kind() == LevelLink.Kind.WALK)) {
			prepareOther(arrival.from().toUpperCase(java.util.Locale.ROOT)); // 이음: 떠나온 레벨 = 다른 슬롯 (그리기용 장면도 미리, LevelLink.norm 과 같은 이름)
			prepareOtherData(arrival.from().toUpperCase(java.util.Locale.ROOT)); // 게임용 데이터 (loadOther) 도
		}
	}

	/** 이 레벨 장면 (CPU 단계를 미리 돌림, null = enter 에서 한 번에). */
	private java.util.concurrent.Future<LevelScene> levelPrep;

	/**
	 * 장면 전환 페이드 (0.4 초, SceneManager) 동안 GL 없는 적재를 백그라운드로 (48 문서 §8): 레벨 장면 CPU 단계, 레벨 RSRCS 적 WAD·R_PERMK (HUD·이펙트)·
	 * R_HERO·R_WPN 해석. enter 가 결과를 받아 (끝나지 않았으면 기다려) GL 만 올린다. 결과 값은 동기 적재와 같다.
	 */
	private void prefetch() {
		if (!devple.gow.engine.asset.Loader.async()) {
			return;
		}
		java.nio.file.Path wad = session.rom.resolve(this.levelName + ".WAD");
		java.nio.file.Path rom = session.rom;
		levelPrep = devple.gow.engine.asset.Loader.submit("level " + this.levelName, () -> {
			LevelScene s = new LevelScene(wad, -2, false).prepare();
			for (String name : EnemyFactory.resourceWads(s.data().wad)) {
				WadAssets.prefetch(rom.resolve(name + ".WAD"), devple.gow.tools.fx.EffectLibrary::preparse); // + wireEffects 의 이펙트 GO 표
			}
			devple.gow.tools.fx.EffectLibrary.preparse(s.assets().wad()); // wireEffects (MagicFx.addWad(레벨)) 의 이펙트 GO 표 (48 §9)
			for (int i = levelWadReopens(s.data().wad, levelName); i > 0; i--) {
				WadAssets.prefetch(wad, devple.gow.tools.fx.EffectLibrary::preparse); // 레벨 템플릿 적이면 spawn 의 MagicFx.addWad 가 쓴다
			}
			boolean addOn = devple.gow.engine.level.HostLevel.addOn(s.data().wad); // ensureHost (덧붙는 레벨의 숙주 찾기) 미리
			hostPrep = addOn ? devple.gow.engine.level.HostLevel.candidates(rom, levelName) : null;
			hostAddOn = addOn;
			if (hostPrep != null) {
				hostPrep.forEach(c -> prepareOtherData(c.name())); // 숙주가 되면 loadOther 가 쓸 게임용 데이터
			}
			return s;
		});
		WadAssets.prefetch(session.rom.resolve("R_PERMK.WAD")); // HUD
		WadAssets.prefetch(session.rom.resolve("R_PERMK.WAD"), devple.gow.tools.fx.EffectLibrary::preparse); // MagicFx 이펙트 GO (EffectSystem)
		int costume = session.player != null ? session.player.costume : session.costume;
		WadAssets.prefetch(session.rom.resolve("R_HERO" + costume + ".WAD"));
		if (session.player != null) { // 블레이드 모델 (HeroRig → BladeModels)
			WadAssets.prefetch(session.rom.resolve("R_WPN" + costume + "_" + Math.max(0, Math.min(5, session.player.level.getOrDefault(Weapon.BLADES, 1))) + ".WAD"));
		}
	}

	/** 블레이드 god 판 (0x1FA9A0, 45 문서 §2): 영웅 코스튬·블레이드 레벨의 R_WPN 이펙트 GO. */
	private void setGodBlades(devple.gow.engine.fx.MagicFx fx, HeroRig h) {
		fx.setGodBlades(session.rom.resolve("R_WPN" + h.player.costume + "_" + Math.max(0, Math.min(5, h.bladeLevel())) + ".WAD"),
				() -> !h.player.artemisEquipped);
	}

	/** 페이드 동안 미리 만든 것 (48 문서 §9, null = enter 에서). */
	private LevelScene preLevel;
	private HeroRig preHero;
	private UiVariables preVars;
	private UiLayer preHud, preLevelHud;
	private boolean preLevelHudDone, preGodBlades;
	private devple.gow.engine.fx.MagicFx preFx;
	private Tweaks preOrbTweaks;
	private Breakables preBreakables;
	private boolean preAnimsAttached;
	/** 이름 → 미리 읽은 EnemyFactory.Kind 또는 그 예외 (loadEnemyKinds 가 꺼내 씀). */
	private final Map<String, Object> preKinds = new HashMap<>();
	private int preStep;
	/** 페이드 한 틱에 미리 할 시간 (ms). 한 단계는 쪼개지 않으므로 넘을 수 있다. */
	private static final long PRELOAD_NS = (long) (Double.parseDouble(System.getProperty("gow.preload.ms", "4")) * 1e6);

	/**
	 * 페이드 아웃 동안 (이전 씬은 그려지기만 하고 멈춰 있다) enter 의 GL·생성 작업 일부를 틱마다 PRELOAD_NS 씩 미리 한다 (48 문서 §9):
	 * 영웅 (R_HERO 모델·스켈레톤·애니·전투 데이터·블레이드), HUD (R_PERMK FLP_HUDK), MagicFx (R_PERMK 이펙트 GO) · god 블레이드, 레벨 장면 GL
	 * (배치를 하나씩), 레벨 HUD, 오브 튜닝. 모두 이 씬만의 새 객체이고 정적 상태·이전 씬을 건드리지 않으므로 enter 에서 만든 것과 같다 (바뀌는 것은 만든
	 * 프레임뿐). 동기 모드 (gow.syncLoad, 스크린샷) 는 하지 않는다 — 예전 프레임 순서 그대로.
	 */
	@Override
	public void preload(Engine e) {
		if (!devple.gow.engine.asset.Loader.async() || preStep < 0) {
			return;
		}
		long t0 = System.nanoTime(), deadline = t0 + PRELOAD_NS;
		try {
			while (preStep >= 0 && System.nanoTime() < deadline) {
				long s0 = System.nanoTime();
				String what = preloadStep(e, deadline);
				if (what == null) {
					break; // 레벨 준비가 아직 (적재 스레드)
				}
				devple.gow.engine.core.PerfMonitor.loadTime("pre " + what, s0);
			}
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** 한 단계. 한 일 이름 (기다려야 하면 null). */
	private String preloadStep(Engine e, long deadline) throws IOException {
		switch (preStep) {
			case 0 -> {
				preHero = new HeroRig(session.rom, session.permk, session.player != null ? session.player.costume : session.costume, session.player);
				preStep++;
				return "hero";
			}
			case 1 -> {
				preVars = new UiVariables();
				preHud = new UiLayer(new WadAssets(session.rom.resolve("R_PERMK.WAD")), true, "FLP_HUDK", session.messages, preVars);
				preStep++;
				return "hud";
			}
			case 2 -> {
				preFx = new devple.gow.engine.fx.MagicFx(session.rom);
				preStep++;
				return "magicFx";
			}
			case 3 -> {
				setGodBlades(preFx, preHero);
				preGodBlades = true;
				preStep++;
				return "godBlades";
			}
			case 4 -> {
				if (preLevel == null) {
					if (levelPrep == null || !levelPrep.isDone()) {
						return null;
					}
					preLevel = devple.gow.engine.asset.Loader.join(levelPrep, "level " + levelName);
					levelPrep = null;
				}
				if (preLevel.preUpload(e, deadline)) {
					preStep++;
				}
				return "level GL";
			}
			case 5 -> {
				level = preLevel; // loadLevelHud 가 level.data() 를 본다 (enter 가 같은 장면을 다시 넣는다)
				preLevelHud = loadLevelHud(preVars);
				preLevelHudDone = true;
				preStep++;
				return "level hud";
			}
			case 6 -> {
				Tweaks t = new Tweaks();
				t.load(session.permk);
				t.load(preLevel.data().wad);
				preOrbTweaks = t;
				preBreakables = new Breakables(preLevel.data(), t); // enter 와 같은 시점의 튜닝 (영구 + 레벨, 적 종류 전)
				preHero.attachExternalAnims(preLevel.data().wad); // 레벨별 영웅 애니 — enter 와 같은 순서 (레벨 → 적 종류 → 보스)
				preAnimsAttached = true;
				preStep++;
				return "tweaks+breakables+anims";
			}
			case 7 -> {
				// 레벨 RSRCS 적 종류 (EnemyFactory.load = WAD·튜닝·스켈레톤·애니 해석, GL·소리 없음). 소리 뱅크·튜닝 합치기·영웅 애니 연결은 enter 에서
				// 예전 순서로 (이전 씬 exit 의 소리 내리기보다 뒤여야 한다)
				for (String name : EnemyFactory.resourceWads(preLevel.data().wad)) {
					var path = session.rom.resolve(name + ".WAD");
					if (!preKinds.containsKey(name) && Files.exists(path)) {
						try {
							preKinds.put(name, EnemyFactory.load(path, session.permk, -1));
						} catch (RuntimeException ex) {
							preKinds.put(name, ex);
						}
					}
				}
				preStep = -1;
				return "enemy kinds";
			}
			default -> {
				preStep = -1;
				return "done";
			}
		}
	}

	/**
	 * enter (와 그 안의 스크립트 시작) 이 레벨 WAD 를 따로 다시 여는 횟수 (48 문서 §9) — 그만큼 미리 해석해 둔다: 레벨 HUD (FLP_), 보스 (BossDirector 가
	 * 레벨 WAD 의 템플릿), 레벨 자체 AI1 템플릿 오브젝트 (levelTemplateKind, 오브젝트마다) — 합쳐 최대 3. 남은 것은 enter 끝에 버려진다.
	 */
	private static int levelWadReopens(devple.gow.tools.wad.WadFile w, String level) {
		int n = 0;
		for (devple.gow.tools.wad.WadTag t : w.tags()) {
			if (t.hasData() && t.name().startsWith("FLP_") && t.magic() == devple.gow.tools.ui.FlpFile.MAGIC) {
				n++;
				break;
			}
		}
		if (devple.gow.engine.boss.BossDirector.hostsBoss(level)) {
			n++;
		}
		Tweaks lt = new Tweaks();
		lt.load(w);
		java.util.Set<String> objs = new java.util.HashSet<>();
		for (String node : lt.nodes("/TweakTemplates/AI1/")) {
			String o = lt.s(node, "Instance Name", null);
			if (o != null && !o.isEmpty()) {
				objs.add(o.toLowerCase(java.util.Locale.ROOT));
			}
		}
		return Math.min(3, n + objs.size()); // 메모리: 레벨 WAD 해석 사본은 많아야 3 개 (enter 끝에 버림)
	}

	@Override
	public void enter(Engine e) {
		long enterT0 = System.nanoTime();
		try {
			level = preLevel != null ? preLevel : levelPrep != null ? devple.gow.engine.asset.Loader.join(levelPrep, "level " + levelName)
					: new LevelScene(session.rom.resolve(levelName + ".WAD"), -2, false);
			levelPrep = null;
			long ph = devple.gow.engine.core.PerfMonitor.phase(". join", enterT0);
			level.enter(e); // GL 올리기 (CPU 단계는 페이드 동안 끝났으면 건너뜀)
			ph = devple.gow.engine.core.PerfMonitor.phase(". level GL", ph);
			level.setExternalCamera(cam);
			level.setDeferEffects(true);
			// SCR_Mirror 반사 패스의 영웅 (원작 0x1DAEC0: 영웅 모델 bit 61, 마스크 0x40 → 거울 뷰 0x80, 값 [4] ≠ 0 인 거울은 뺀다 — 43 문서 §9)
			level.setMirrorActors(rc -> {
				level.fx().env().applyFog(hero.shader(), rc, e.window().height());
				hero.draw(rc, e.window().aspect(), renderAlpha, e.renderer().white());
			});
			col = level.collision();
			hero = preHero != null ? preHero : new HeroRig(session.rom, session.permk, session.player != null ? session.player.costume : session.costume, session.player);
			hero.difficulty = session.difficulty;
			session.player = hero.player;
			ph = devple.gow.engine.core.PerfMonitor.phase(". hero", ph);
			UiVariables vars = preVars != null ? preVars : new UiVariables();
			hud = preHud != null ? preHud : new UiLayer(new WadAssets(session.rom.resolve("R_PERMK.WAD")), true, "FLP_HUDK", session.messages, vars);
			levelHud = preLevelHudDone ? preLevelHud : loadLevelHud(vars);
			ph = devple.gow.engine.core.PerfMonitor.phase(". hud", ph);
			session.sound.load(level.data().wad);
			ph = devple.gow.engine.core.PerfMonitor.phase(". sound", ph);
			hero.combat.onSound((h, p) -> session.sound.play(h, p));
			vars.addListener((name, value) -> {
				if (name.regionMatches(true, 0, "PS2_Sound", 0, 9) && value != null && value.startsWith("SND_")) {
					session.sound.play(value);
				}
				if (powerUp && name.equals("PS2_PSMDone") && "1".equals(String.valueOf(value).replace(".0", ""))) {
					onPowerUpClosed();
				} else if (questionId >= 0 && name.equals("PS2_Done") && "1".equals(String.valueOf(value).replace(".0", ""))) {
					answerQuestion(vars.get("PS2_Menu_Choice"));
				} else if (saveAsk && name.equals("PS2_Done") && "1".equals(String.valueOf(value).replace(".0", ""))) {
					onSaveAnswered("1".equals(String.valueOf(vars.get("PS2_Menu_Choice")).replace(".0", "")));
				} else if (paused && !powerUp && !saveAsk && saveFlow == null
						&& (name.equals("PS2_PauseDone") || name.endsWith("Menu_Choice"))) {
					onMenuClosed(name, value);
				}
			});
			hud.player().addCommandListener((cmd, arg) -> {
				if (saveFlow != null) {
					saveFlow.command(cmd, arg);
				}
			});
			ph = devple.gow.engine.core.PerfMonitor.phase(". hud listeners", ph);
			enemyShader = new Shader("skinned.vert", "basic.frag");
			if (preOrbTweaks != null) {
				orbTweaks = preOrbTweaks;
			} else {
				orbTweaks = new Tweaks();
				orbTweaks.load(session.permk);
				orbTweaks.load(level.data().wad);
			}
			ph = devple.gow.engine.core.PerfMonitor.phase(". tweaks", ph);
			breakables = preBreakables != null ? preBreakables : new Breakables(level.data(), orbTweaks);
			if (!preAnimsAttached) {
				hero.attachExternalAnims(level.data().wad); // 레벨별 영웅 애니 (ANM_hero_Athn01A 등)
			}
			ph = devple.gow.engine.core.PerfMonitor.phase(". breakables+anims", ph);
			loadEnemyKinds();
			preKinds.values().forEach(k -> { // 쓰이지 않은 미리 읽은 종류 (없어야 정상) — 자산을 닫는다
				if (k instanceof EnemyFactory.Kind kk) {
					kk.assets().close();
				}
			});
			preKinds.clear();
			ph = devple.gow.engine.core.PerfMonitor.phase(". enemy kinds", ph);
			loadBoss();
			devple.gow.engine.core.PerfMonitor.phase(". boss", ph);
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		long ph = System.nanoTime();
		grab = new devple.gow.engine.combat.GrabSync(hero.ctl, hero.combat);
		grab.setPullers(() -> enemies.stream().map(Enemy::fighter).toList()); // 결합 없는 끌기 = 공격 대상이 영웅인 적 (0x2097A8)
		if (System.getProperty("gow.giveAll") != null) {
			for (int id = 3; id <= 7; id++) {
				hero.player.addItem(id, 1);
			}
			hero.player.level.put(Weapon.BLADES, 2);
			hero.player.mp = hero.player.maxMp();
			hero.combat.godMeter = 100; // 신들의 분노 게이지 가득 (L3+R3)
		}
		grab.onSound(n -> session.sound.play(n));
		// 마법 (35 문서): 대상 = 살아있는 적 전체, 소리 → SoundManager, HUD 선택 마법 아이콘
		hero.magic.setTargets(() -> enemies.stream().map(Enemy::fighter).filter(f -> !f.dead()).toList());
		hero.magic.setSightBlocked((eye, to) -> {
			Vector3f d = new Vector3f(to).sub(eye);
			float len = d.length();
			return len > 1f && col.raycast(eye, d, len - 1f) != null;
		});
		ph = devple.gow.engine.core.PerfMonitor.phase(". grab+magic", ph);
		magicFx = preFx != null ? preFx : new devple.gow.engine.fx.MagicFx(session.rom); // 원작 이펙트 GO (40 문서)
		hero.magic.setSink(magicFx.sink((name, at) -> session.sound.play(name, at)));
		magicFx.bindHero(hero.combat, hero::jointWorld);
		if (!preGodBlades) {
			setGodBlades(magicFx, hero);
		}
		hero.hideBlades = magicFx::godBladesOn;
		ph = devple.gow.engine.core.PerfMonitor.phase(". magicFx", ph);
		// 동적 라이트 (47 문서): 레벨 WAD 의 LGT_ + 무기 WAD 의 블레이드 라이트. -Dgow.nolights 면 예전 고정 조명 (비교용)
		if (System.getProperty("gow.nolights") == null) {
			lights = new devple.gow.engine.light.LightServer();
			int nl = lights.addAll(level.data().wad, levelName).size();
			hero.attachBladeLights(lights);
			devple.gow.engine.light.LightServer.setCurrent(lights);
			System.out.printf("라이트: 레벨 %d, 블레이드 %d%n", nl, lights.lights().size() - nl);
			// 캐릭터 그림자 (renShadowServer 0x27, 47 문서 §7): 방향 = 비트 4 라이트. -Dgow.noshadow 면 끔
			if (System.getProperty("gow.noshadow") == null) {
				shadows = new devple.gow.engine.light.ShadowCasters();
				devple.gow.engine.light.ShadowCasters.setCurrent(shadows);
			}
		}
		magicFx.bindBlades(new devple.gow.engine.fx.MagicFx.Blades() { // 사슬이 정한 블레이드 행렬·궤적 (45 문서 §3)
			public boolean world(int side, Matrix4f out) {
				return hero.bladeWorld(side, out);
			}

			public boolean trail(int side) {
				return hero.trail(side);
			}
		});
		ph = devple.gow.engine.core.PerfMonitor.phase(". lights", ph);
		wireEffects();
		ph = devple.gow.engine.core.PerfMonitor.phase(". wireEffects", ph);
		hudBinding.castable = hero.magic::has;
		boolean seamless = arrival != null && (arrival.kind() == LevelLink.Kind.SEAMLESS || arrival.kind() == LevelLink.Kind.WALK);
		link = new LevelLink(levelName, seamless ? arrival.from() : null, w -> Files.exists(session.rom.resolve(w + ".WAD")));
		if (link.other() != null) {
			loadOther(link.other());
		}
		ph = devple.gow.engine.core.PerfMonitor.phase(". other", ph);
		cameraRig = new devple.gow.engine.level.CameraRig(level.data());
		cameraRig.setOther(otherData); // 다른 슬롯 레벨의 CMV·카메라도 질의 (원작 두 슬롯 상주)
		// 카메라 전환 → 전역 이벤트 1037 (인자0 = 카메라 이름, 원작 0x1EEE88)
		cameraRig.onSwitch(n -> {
			if (world != null) {
				world.postEvent(1037, n, 0, 0, null);
			}
		});
		ph = devple.gow.engine.core.PerfMonitor.phase(". cameraRig", ph);
		world = new ScriptWorld(level.data(), session.scriptHost, listener());
		nav = new SceneNav(level.data(), col, hero, world, session.scriptHost, orbTweaks, navHost());
		buildRopeVisuals();
		ph = devple.gow.engine.core.PerfMonitor.phase(". script+nav", ph);
		if (otherName != null) {
			nav.setOther(otherName, otherData, otherCol, nav.neighbourListener());
			Resident left = resident(arrival, otherName);
			if (left != null) { // 이음: 떠나온 레벨 = 다른 슬롯 그대로 (생성 스크립트 결과보다 넘긴 상태가 우선)
				otherHidden.clear();
				otherHidden.addAll(left.hidden());
				nav.neighbourPlatforms().restore(left.anims());
				if (System.getProperty("gow.debug") != null) {
					System.out.println("다른 슬롯 상태 넘겨받음 " + otherName + ": 숨김 " + left.hidden().size() + ", 애니 " + left.anims());
				}
			}
		}
		String arrivalCam = placeSpawn();
		respawn(spawn);
		boolean warped = arrival != null && arrival.kind() == LevelLink.Kind.WARP;
		if (warped) {
			// Warp_Jump → 영웅 SetMatrix 0x1E79D0: 줄이면 놓기 0x1C4FF8(1) → 떨어짐 (+0x174 0x8), 다음 프레임 공중 줄 잡기 0x1C5C20 이
			// 도착점 줄을 잡는다 — 잡기 대기 없음 (ATRET01A WarpFrmhdes05 = 무덤 줄 gorope1 한가운데, 43 §13.9)
			nav.navigator.warpArrived();
		}
		ph = devple.gow.engine.core.PerfMonitor.phase(". spawn", ph);
		checkpoint.set(spawn);
		cam.fovDeg = 60;
		cam.near = devple.gow.engine.fx.FxEnvironment.viewNear; // 원작 뷰포트 near (+0x384 = −10, 0x168DC8 — 43 §11)
		cam.far = 30000;
		bridge = new devple.gow.engine.boss.CombatScriptBridge(world);
		if (boss != null) {
			boss.attach(arena());
		}
		world.start();
		ph = devple.gow.engine.core.PerfMonitor.phase(". script start", ph);
		// Warp 도 이미 서 있는 볼륨은 "안" 으로: 원작 영웅 볼륨 판정은 이전 위치 → 지금 위치 선분 (0x1A7FFC, 콜백 0x1A8508) 인데 순간이동
		// (0x1E0A18 → 0x1B3030 → 0x29D90C) 프레임은 선분을 건너뛰고 이전 위치 = 도착점 — 도착점을 덮는 Warp 볼륨이 곧바로 되돌리지 않는다 (43 §13.9)
		world.placePlayer(spawn.x, spawn.y, spawn.z, seamless || warped);
		if (warped && loadPos == null) {
			// Warp_Jump 끝 0x1A9C18: 도착 Start 의 대상 발동 (PAND04AA WarpFrmCore → 엘리베이터 AnimLiftUp 등, 43 §13.10) [확실]
			world.fireStart(world.warpTarget(arrival.from()));
		}
		Resident here = resident(arrival, levelName);
		if (here != null) { // 이음: 들어온 레벨은 원작에서 이미 다른 슬롯에 살아 있었다 → 그 화면 상태 (생성 스크립트 뒤)
			if (warped) {
				level.setRemoved(here.hidden()); // Warp 로 되돌아옴: 떠날 때 숨김 집합 그대로 (생성 스크립트 VisOFF 도 되돌림)
			} else {
				here.hidden().forEach(level::hideInstance);
			}
			nav.restorePlatforms(here.anims());
		}
		// 검증: --arrive 이음 재현에는 슬롯 상태가 없다 → -Dgow.arrive.open=오브젝트[:애니],… 를 애니 끝 자세로 (열린 문, 43 §13.8)
		String open = System.getProperty("gow.arrive.open");
		if (open != null && seamless && arrival.resident() == null) {
			for (String o : open.split(",")) {
				String[] f = o.split(":");
				String an = f.length > 1 ? f[1] : "!0";
				boolean ok = nav.neighbourPlatforms().play(f[0], an, 0, 1, 1e9f) || nav.playPlatform(f[0], an, 0, 1, 1e9f);
				System.out.println("도착 재현: " + f[0] + " 애니 끝 자세 " + ok);
			}
		}
		// 시작 카메라는 생성 시 스크립트(CreationSensor → Vis Attr11_1 CMV 끔, 예 ATHN02D MedusaHeadCam) 뒤에 고른다 — 원작은 레벨이
		// 이웃 슬롯에 먼저 올라와 있다가 들어오므로 그 시점엔 생성 스크립트가 이미 돌았다 [추정]. Warp_Jump 순서: 볼륨 선택(0x1EFC98 →
		// 0x1EDF48(0)) 뒤 Start 의 CameraName 을 SwitchCamera (0x1A8E10).
		if (seamless && arrival.camera() != null) {
			cameraRig.resume(arrival.camera());
		} else if (seamless && arrival.from().equals(otherName)) { // --arrive 이음 재현: 떠나온 레벨에서 쓰던 카메라 대신 (43 §13.8) [근사: 하네스]
			Vector3f sp = cameraRig.seedApproach(hero.ctl.position, hero.ctl.yaw, otherData);
			if (sp != null) {
				System.out.printf("도착 카메라 씨앗: %s (%.0f, %.0f, %.0f)%n", cameraRig.current(), sp.x, sp.y, sp.z);
			}
		}
		cameraRig.update(0, hero.ctl.position, hero.ctl.yaw, false);
		if (!arrivalCam.isEmpty()) {
			cameraRig.request(arrivalCam); // Warp 도착 Start 의 CameraName
			cameraRig.update(0, hero.ctl.position, hero.ctl.yaw, false);
		}
		if (Float.isNaN(col.groundHeight(spawn.x, spawn.y + STEP, spawn.z, 64))) {
			nav.settleArrival(spawn); // 움직이는 발판 위 도착점 (ATHN05D WarpFrom4A)
		}
		devple.gow.engine.core.PerfMonitor.phase(". arrival", ph);
		String ln = levelName;
		devple.gow.engine.core.PerfMonitor.gauge("level", () -> ln);
		devple.gow.engine.core.PerfMonitor.gauge("enemies", enemies::size);
		var snd = session.sound; // 게이지가 씬을 붙잡지 않게
		devple.gow.engine.core.PerfMonitor.gauge("voices", snd::active);
		devple.gow.engine.core.PerfMonitor.gauge("al", () -> snd.backend().stats());
		System.out.printf("레벨 %s (진입점 %s): 시작 (%.0f, %.0f, %.0f), 적 종류 %s%n", levelName, entry, spawn.x, spawn.y,
				spawn.z, kinds.keySet());
		WadAssets.dropPrefetched(); // 쓰지 않은 미리 해석 (예: 소리 전용 RSRCS) 은 버린다
		otherDataPrep.clear(); // 이후 이웃 교체는 예전처럼 그 틱에 동기로
		devple.gow.engine.core.PerfMonitor.loadTime("enter " + levelName, enterT0);
	}

	/** 레벨이 쓰는 적 리소스: RSRCS 태그(0x1F4)의 24바이트 이름들 → R_&lt;이름&gt;.WAD (11 문서 §6.3 Slots 힙). */
	private void loadEnemyKinds() throws IOException {
		for (String name : EnemyFactory.resourceWads(level.data().wad)) {
			var path = session.rom.resolve(name + ".WAD");
			if (!kinds.containsKey(name) && Files.exists(path)) {
				try {
					Object pre = preKinds.remove(name);
					if (pre instanceof RuntimeException ex) {
						throw ex; // 페이드 동안 해 본 결과 그대로 (소리 전용 WAD)
					}
					EnemyFactory.Kind k = pre != null ? (EnemyFactory.Kind) pre : EnemyFactory.load(path, session.permk, -1);
					kinds.put(name, k);
					orbTweaks.load(k.assets().wad());
					session.sound.load(k.assets().wad());
					hero.attachExternalAnims(k.assets().wad());
				} catch (RuntimeException ex) {
					// 스켈레톤 없는 리소스 WAD (R_AHH 등 "FkSnd" 사운드 뱅크 로더): 사운드만 올린다
					System.out.println("적 리소스 " + name + ": 전투 개체 아님 (" + ex.getMessage() + ") → 사운드만");
					devple.gow.tools.wad.WadFile sw = devple.gow.tools.wad.WadFile.load(path);
					session.sound.load(sw);
					soundOnly.add(sw.name());
					Tweaks st = new Tweaks();
					st.load(sw);
					for (String n : st.nodes("/TweakTemplates/AI1/")) {
						try {
							soundOnlyTemplates.add(Integer.parseInt(n.split("/")[3]));
						} catch (RuntimeException ignored) {
							// 숫자가 아닌 노드
						}
					}
				}
			}
		}
	}

	/** 레벨 WAD 의 FLP 오버레이 (예 FLP_Athn01E 히드라 체력 미터). 없으면 null. */
	private UiLayer loadLevelHud(UiVariables vars) {
		for (devple.gow.tools.wad.WadTag t : level.data().wad.tags()) {
			if (t.hasData() && t.name().startsWith("FLP_") && t.magic() == devple.gow.tools.ui.FlpFile.MAGIC) {
				try {
					UiLayer u = new UiLayer(new WadAssets(session.rom.resolve(levelName + ".WAD")), true, t.name(), session.messages, vars);
					u.acceptInput = false;
					System.out.println("레벨 HUD: " + t.name());
					return u;
				} catch (RuntimeException | IOException ex) {
					System.out.println("레벨 HUD 실패 " + t.name() + ": " + ex.getMessage());
				}
			}
		}
		return null;
	}

	/** 레벨 WAD 안의 보스 리소스 (BossDirector). 영웅 쪽 동기 애니·소리 뱅크도 붙인다. */
	private void loadBoss() throws IOException {
		boss = devple.gow.engine.boss.BossDirector.create(levelName, session.rom, session.permk);
		if (boss == null) {
			return;
		}
		boss.autoPlay = System.getProperty("gow.boss.demo") != null; // GowMain --boss-demo
		for (EnemyFactory.Kind k : boss.kinds().values()) {
			orbTweaks.load(k.assets().wad());
		}
		for (java.nio.file.Path p : boss.heroAnimWads()) {
			hero.attachExternalAnims(devple.gow.tools.wad.WadFile.load(p));
		}
		System.out.println("보스전: " + boss.getClass().getSimpleName() + " 템플릿 " + boss.kinds().keySet());
	}

	private devple.gow.engine.boss.BossArena arena() {
		if (arena == null) {
			arena = new devple.gow.engine.boss.BossArena() {
				public devple.gow.engine.anim.HeroController heroCtl() {
					return hero.ctl;
				}

				public devple.gow.engine.combat.Combatant heroCombat() {
					return hero.combat;
				}

				public devple.gow.engine.combat.Hittable hero() {
					return hero.hittable();
				}

				public void damageHero(float a) {
					hero.player.hp = Math.max(0, Math.min(hero.player.maxHp(), hero.player.hp - a));
				}

				public ScriptWorld world() {
					return world;
				}

				public devple.gow.engine.boss.CombatScriptBridge bridge() {
					return bridge;
				}

				public void sound(String name) {
					session.sound.play(name);
				}

				public void requestCamera(String name, Vector3f... targets) {
					cameraRig.request(name, targets); // 아레스 AresCamera1 (0x1C3370)
				}

				public devple.gow.engine.fx.EffectSystem effects() {
					return magicFx != null ? magicFx.effects() : null;
				}

				public devple.gow.engine.level.LevelCollision collision() {
					return col;
				}

				public void cameraTarget(Vector3f p, float weight) {
					cameraRig.setTargetSlot(p, weight); // BMI 머리 관절 (0x1C0378 → 0x1F3F78)
				}
			};
		}
		return arena;
	}

	/**
	 * 바닥을 이웃 레벨에 두는 레벨 (ATHN02D → ATHN02C, {@link devple.gow.engine.level.HostLevel}): 서 있을 점 아래 자기 바닥이 없으면
	 * 숙주 레벨을 다른 슬롯에 올린다. 원작은 숙주가 이 레벨을 LoadWad 한 뒤 두 레벨이 함께 상주한다.
	 */
	/**
	 * enter 안의 loadOther (이음 도착 = 떠나온 레벨, 숙주 레벨) 가 쓸 게임용 이웃 데이터 (LevelData·충돌) 를 적재 스레드에서 미리 (48 문서 §9).
	 * 값 = {LevelData, LevelCollision 또는 null, 충돌 생성 예외 또는 null} — loadOther 의 동기 경로와 같은 순서·값. enter 끝에 비운다.
	 */
	private final Map<String, java.util.concurrent.Future<Object[]>> otherDataPrep = new java.util.concurrent.ConcurrentHashMap<>();

	private void prepareOtherData(String wad) {
		if (!devple.gow.engine.asset.Loader.async() || otherDataPrep.containsKey(wad)) {
			return;
		}
		java.nio.file.Path p = session.rom.resolve(wad + ".WAD");
		otherDataPrep.put(wad, devple.gow.engine.asset.Loader.submit("otherData " + wad, () -> {
			LevelData d = new LevelData(devple.gow.tools.wad.WadFile.load(p));
			try {
				return new Object[]{d, new LevelCollision(d.sheets), null};
			} catch (RuntimeException ex) {
				return new Object[]{d, null, ex};
			}
		}));
	}

	/** 레벨 준비 (적재 스레드) 가 미리 본 숙주 레벨 판정 (null = 미리 안 함). */
	private volatile Boolean hostAddOn;
	private volatile List<devple.gow.engine.level.HostLevel.Candidate> hostPrep;

	private void ensureHost(float x, float y, float z) {
		if (!Float.isNaN(col.groundHeight(x, y + STEP, z, 600))
				|| !(hostAddOn != null ? hostAddOn : devple.gow.engine.level.HostLevel.addOn(level.data().wad))) {
			return;
		}
		String h = hostAddOn != null ? devple.gow.engine.level.HostLevel.find(hostPrep, x, y, z)
				: devple.gow.engine.level.HostLevel.find(session.rom, levelName, x, y, z);
		if (h != null) {
			hostLevel = h;
			if (!h.equals(otherName)) {
				System.out.println("숙주 레벨 " + h + " (바닥이 그쪽 시트에 있음) → 다른 슬롯");
				loadOther(h);
			}
		}
	}

	/** 시작 위치·방향 결정 ({@link LevelEntry}). 반환 = 도착 카메라 이름 (없으면 ""). */
	private String placeSpawn() {
		if (loadPos != null) {
			spawn.set(loadPos[0], loadPos[1], loadPos[2]);
			spawnYaw = loadYaw;
			ensureHost(spawn.x, spawn.y, spawn.z);
			return "";
		}
		if (arrival != null && arrival.pos() != null && arrival.kind() != LevelLink.Kind.WARP) {
			spawn.set(arrival.pos()[0], arrival.pos()[1], arrival.pos()[2]);
			spawnYaw = arrival.yaw();
			ensureHost(spawn.x, spawn.y, spawn.z);
			return "";
		}
		String from = arrival != null && arrival.kind() == LevelLink.Kind.WARP ? arrival.from() : null;
		LevelEntry.Spot s = LevelEntry.resolve(level.data(), world, entry, from);
		ensureHost(s.pos()[0], s.pos()[1], s.pos()[2]);
		if (hostLevel != null && otherCol != null && !Float.isNaN(otherCol.groundHeight(s.pos()[0], s.pos()[1] + STEP, s.pos()[2], 600))) {
			float[] p0 = s.pos();
			spawn.set(p0[0], otherCol.groundHeight(p0[0], p0[1] + STEP, p0[2], 600), p0[2]);
			spawnYaw = s.yaw();
			System.out.println("시작 지점: " + s.source() + " (숙주 " + hostLevel + " 바닥)");
			return s.camera();
		}
		float[] at = LevelEntry.snapToFloor(col, s.pos(), 256);
		if (at == null) {
			at = s.pos();
		} else if (at != s.pos()) {
			System.out.printf("시작 지점 아래 바닥 없음 → 가까운 바닥 (%.0f, %.0f, %.0f) [근사]%n", at[0], at[1], at[2]);
		}
		spawn.set(at[0], at[1], at[2]);
		spawnYaw = s.yaw();
		System.out.println("시작 지점: " + s.source());
		return s.camera();
	}

	/** 다른 슬롯 레벨의 충돌만 읽어 둔다 (렌더·스크립트 없음). 원작은 백그라운드 로드, 여기서는 즉시 [근사]. */
	private void loadOther(String wad) {
		if (wad.equals(otherName)) {
			return;
		}
		if (lights != null && otherName != null) {
			lights.removeSource(otherName); // 이전 다른 슬롯 레벨의 라이트 빼기 (47 문서 §8)
		}
		long t0 = System.nanoTime();
		otherName = wad;
		otherHidden.clear();
		otherPosed.clear();
		otherScenePending = true; // 렌더 스레드에서 지오메트리 교체 (render)
		if (!otherCache.containsKey(wad)) {
			prepareOther(wad); // 그리기용 장면 CPU 단계는 백그라운드로 (48 §8)
		}
		otherCol = null;
		otherData = null;
		try {
			java.util.concurrent.Future<Object[]> pre = otherDataPrep.remove(wad);
			if (pre != null) { // enter 안: 적재 스레드가 미리 읽은 같은 값 (48 §9)
				Object[] r = devple.gow.engine.asset.Loader.join(pre, "otherData " + wad);
				otherData = (LevelData) r[0];
				if (r[2] != null) {
					throw (RuntimeException) r[2];
				}
				otherCol = (LevelCollision) r[1];
			} else {
				otherData = new LevelData(devple.gow.tools.wad.WadFile.load(session.rom.resolve(wad + ".WAD")));
				otherCol = new LevelCollision(otherData.sheets);
			}
			if (lights != null && System.getProperty("gow.noneighbourlights") == null) { // 다른 슬롯 레벨의 LGT 도 같은 서버에 (원작 두 슬롯 상주, 0x151F08)
				System.out.printf("라이트: 다른 슬롯 %s %d%n", wad, lights.addAll(otherData.wad, wad).size());
			}
		} catch (IOException | RuntimeException ex) {
			System.out.println("이웃 레벨 충돌 로드 실패 " + wad + ": " + ex.getMessage());
		}
		if (nav != null) {
			nav.setOther(wad, otherData, otherCol, nav.neighbourListener());
		}
		if (cameraRig != null) {
			cameraRig.setOther(otherData);
		}
		devple.gow.engine.core.PerfMonitor.loadTime("other " + wad, t0);
	}

	/** 현재 + 다른 슬롯 레벨 바닥 + 움직이는 발판 중 높은 것 (NaN = 없음). */
	private float ground(float x, float y, float z, float drop) {
		if (nav != null) {
			return nav.ground(x, y, z, drop);
		}
		float g = col.groundHeight(x, y, z, drop);
		if (otherCol != null) {
			float o = otherCol.groundHeight(x, y, z, drop);
			if (!Float.isNaN(o) && (Float.isNaN(g) || o > g)) {
				return o;
			}
		}
		return g;
	}

	/** 내비게이션 보조 → 게임 씬 동작. */
	private SceneNav.Host navHost() {
		return new SceneNav.Host() {
			@Override
			public void heroDied() {
				GameScene.this.heroDied();
			}

			@Override
			public void neighbourLevel(ScriptListener.LevelRequest r) {
				listener().level(r); // 이웃의 LoadCheck(자기 레벨) = 현재 레벨 리스너와 같은 규칙 (SEAMLESS)
			}

			@Override
			public void teleport(float[] pos, float yaw) {
				spawnYaw = yaw;
				respawn(new Vector3f(pos[0], pos[1], pos[2]));
			}

			@Override
			public void message(int id, float seconds) {
				showMessage(id, seconds);
			}

			@Override
			public void neighbourVisible(List<String> objects, boolean show) {
				for (String o : objects) {
					String k = o.toLowerCase(java.util.Locale.ROOT);
					if (show) {
						otherHidden.remove(k);
					} else {
						otherHidden.add(k);
					}
				}
			}

			@Override
			public void neighbourCamera(String name, Boolean query, List<String> owners, boolean on) {
				if (cameraRig == null) {
					return;
				}
				if (name != null) {
					cameraRig.request(name);
				}
				if (query != null) {
					cameraRig.setVolumeQuery(query);
				}
				if (owners != null) {
					owners.forEach(o -> cameraRig.setOwnerVolumes(o, on));
				}
			}

			@Override
			public void orbs(String emitter, Vector3f at) {
				for (OrbDrops.Orb o : OrbDrops.roll(orbTweaks, emitter, rnd, hero.player.hp, hero.player.mp)) {
					hero.player.pickupOrb(o.type(), o.points());
				}
				session.sound.play("SND_PICKUPS_ORBS_E");
			}
		};
	}

	private void respawn(Vector3f at) {
		hero.ctl.position.set(at);
		hero.ctl.prevPosition.set(at);
		hero.ctl.yaw = spawnYaw;
		hero.ctl.vy = 0;
		hero.ctl.launch.zero();
		if (nav != null) {
			nav.reset();
		}
	}

	private ScriptListener listener() {
		return new ScriptListener() {
			@Override
			public void message(int id, float seconds) {
				showMessage(id, seconds);
			}

			@Override
			public void camera(String name) {
				cameraRig.request(name); // Internal 0x8 SwitchCamera
			}

			@Override
			public void animate(ScriptEntity source, List<String> objects, String anim) {
				if (nav != null) {
					nav.animate(source, objects, anim);
				} // Animator → 발판·회전방 관절 애니 (Marker 위치도 따라감)
			}

			@Override
			public void vis(ScriptEntity source, List<String> objects, int[] values) {
				// Visible (슬롯 0): 1 보임 / 2 숨김 / 0 그대로 — 렌더와 R1 상호작용 (13 문서 Vis)
				if (values.length > 0 && (values[0] == 1 || values[0] == 2)) {
					boolean show = values[0] == 1;
					if (System.getProperty("gow.trace.vis") != null) { // 디버그: Vis 표시/숨김 추적
						System.out.println("VIS " + source.name() + " " + (show ? "ON " : "OFF ") + objects);
					}
					for (String o : objects) {
						if (show) {
							level.showInstance(o);
						} else {
							level.hideInstance(o);
						}
						if (hero.nav != null) {
							var it = hero.nav.items().byOwner(o);
							if (it != null) {
								it.hidden = !show;
							}
						}
					}
				}
				// 슬롯 2 = 타입 0x27 (그림자) 속성 (0x1ABB78 → 0x1B1398) [확실: 디스어셈블]: 1 켬 / 2 끔
				if (values.length > 2 && (values[2] == 1 || values[2] == 2) && shadows != null) {
					for (String o : objects) {
						shadows.setHidden(o, values[2] == 2);
					}
				}
				if (values.length > 3 && values[3] != 0 && nav != null) {
					nav.setSolid(objects, values[3] == 1); // Attr11_0: 충돌 on/off ("collisionOFF", "DoorCollOff") [추정: 데이터 용례]
				}
				if (values.length > 4 && values[4] != 0) {
					if (System.getProperty("gow.trace.vis") != null) {
						System.out.println("VIS " + source.name() + " CMV " + (values[4] == 1 ? "ON " : "OFF ") + objects);
					}
					objects.forEach(o -> cameraRig.setOwnerVolumes(o, values[4] == 1)); // Attr11_1: CMV 볼륨 on/off
				}
			}

			@Override
			public void internal(int func, List<Object> args) {
				if (func == 0x2F && !args.isEmpty()) { // CompleteGame(kind): 해금 비트 기록 (11 문서 §2.3)
					int kind = (int) num(args.get(0));
					try {
						int m = Unlocks.complete(SaveGame.dir(), kind, session.difficulty);
						System.out.printf("CompleteGame(%d) 난이도 %d → 해금 비트 0x%X%n", kind, session.difficulty, m);
					} catch (IOException ex) {
						System.out.println("해금 기록 실패: " + ex.getMessage());
					}
				}
				if (func == 0xB && !args.isEmpty()) { // 질문 (원작 0x21D9C8 → GameMgr +0x152C) → HUD 메뉴 템플릿 → 1021 (선택 번호, 메시지 번호)
					openQuestion((int) num(args.get(0)));
				}
				if (func == 0x9 && !args.isEmpty()) {
					Object a = args.get(0);
					cameraRig.setVolumeQuery(a instanceof Number n ? n.floatValue() != 0 : !(a instanceof Boolean b) || b); // Internal 0x9 CMV 질의 on/off
				}
			}

			@Override
			public void checkpoint(ScriptEntity source) {
				checkpoint.set(hero.ctl.position);
				System.out.println("체크포인트 " + checkpoint);
			}

			@Override
			public void level(LevelRequest r) {
				if (r.op() == LevelOp.GOTO) {
					// Internal:7 Goto(name): 같은 레벨 안 엔티티 위치로 이동 (원작 Warp_Jump)
					ScriptEntity t = world.find(r.entry());
					if (t != null) {
						float[] p = t.position();
						spawnYaw = (float) Math.atan2(t.world[8], t.world[10]);
						respawn(new Vector3f(p[0], p[1], p[2]));
						String cam = t.type() == 9 ? String.valueOf(world.slot(t, "CameraName")) : "";
						if (!cam.isEmpty()) {
							cameraRig.request(cam);
						}
					}
					return;
				}
				LevelLink.Transition t = link.handle(r);
				if (link.other() != null && !link.other().equals(otherName)) {
					System.out.println("다른 슬롯 레벨: " + link.other() + " (" + r.op() + ")");
					loadOther(link.other());
				}
				if (t == null) {
					return;
				}
				Vector3f p = hero.ctl.position;
				switch (t.kind()) {
					case WARP -> goToLevel(t.target(), new Arrival(LevelLink.Kind.WARP, levelName, null, 0));
					case SEAMLESS -> {
						// LoadCheck: 원작은 "현재 레벨" 표시만 바꾼다. 이웃 레벨 바닥이 발밑에 있을 때만 그 레벨로 넘어간다
						// (좌표계가 다른 Warp 짝 레벨에서 허공으로 떨어지지 않도록, 36 문서 §3)
						if (otherCol != null && !Float.isNaN(otherCol.groundHeight(p.x, p.y + 32, p.z, 600))) {
							goToLevel(t.target(), new Arrival(LevelLink.Kind.SEAMLESS, levelName, new float[]{p.x, p.y, p.z}, hero.ctl.yaw));
						}
					}
					case LOAD -> goToLevel(t.target(), t.entry());
					default -> {
					}
				}
			}

			@Override
			public boolean loadCheck(String wad) {
				// 원작은 백그라운드 로드 완료 여부. 여기서는 슬롯에 올라 있으면 준비된 것으로 본다
				return link.loaded(wad);
			}

			@Override
			public void deathZone(ScriptActor actor, ScriptEntity source, int event, int arg) {
				if (actor == world.player()) {
					System.out.println("데스존: " + source);
					heroDied();
				} else {
					enemies.stream().filter(en -> en.actor() == actor).forEach(en -> en.fighter().hp = 0);
				}
			}

			@Override
			public void objectEvent(ObjectEvent e) {
				if (boss != null) {
					boss.objectEvent(e);
				} else if (bridge != null) {
					bridge.objectEvent(e);
				}
			}

			@Override
			public void spawnEnemy(SpawnRequest r) {
				spawn(r);
			}

			@Override
			public void movie(String name, int op, int arg) {
				if (op == 0 && pendingEngine != null) {
					playMovie(pendingEngine, name);
				}
			}

			@Override
			public void music(String func, List<Object> args) {
				var snd = session.sound;
				switch (func) {
					case "MusicGroupStart" -> snd.music(String.valueOf(args.get(0)), args.size() > 1 ? num(args.get(1)) : 0);
					case "MusicGroupStop" -> snd.stopMusic(args.isEmpty() ? 0 : num(args.get(0)));
					case "MusicGroupParam" -> {
						if (args.size() >= 3) {
							snd.musicParam((int) num(args.get(0)), (int) num(args.get(1)), num(args.get(2)));
						}
					}
					case "PlaySound" -> {
						if (!args.isEmpty() && args.get(0) instanceof String n) {
							snd.play(n);
						}
					}
					default -> {
					}
				}
			}

			@Override
			public void sound(ScriptEntity source, String group, int op) {
				float[] sp = source.position();
				session.sound.soundGroup(group, op, new Vector3f(sp[0], sp[1], sp[2]));
			}

			@Override
			public void create(ScriptEntity source) {
				// Creator 엔티티: 이름 슬롯 "savepoint" → R_SAVEPT 세이브 포인트를 소유 GO 위치에 생성
				Object name = world.slot(source, "Name0");
				if ("savepoint".equals(name) && source.owner != null) {
					float[] gp = source.owner.instance().pos();
					savePoints.add(new Vector3f(gp[0], gp[1], gp[2]));
					System.out.printf("세이브 포인트 생성 (%.0f, %.0f, %.0f)%n", gp[0], gp[1], gp[2]);
				}
			}
		};
	}

	/**
	 * 컷신 재생 (33 문서): 씬 스택에 MovieScene 을 올려 게임을 멈추고, 끝나면 음악을 다시 켜고
	 * 스크립트에 종료 이벤트 501(인자 0 = 동영상 이름)을 보낸다 (36 문서: ATHN01D TwinKickDoor-main-movieDone 이 EventArg(0) == "Semo02" 를 검사).
	 */
	private void playMovie(Engine e, String name) {
		if (System.getProperty("gow.skipmovies") != null) {
			// --skip-movies: 재생 없이 바로 끝 이벤트 (자동 연출·검증용)
			world.postEvent(7, 0, 0, 0, null);
			world.postEvent(501, name, 0, 0, world.player());
			return;
		}
		String music = session.sound.currentMusic();
		session.sound.stopMusic(0);
		devple.gow.engine.video.MovieScene.play(e, name, () -> {
			if (music != null) {
				session.sound.music(music, 0.5f);
			}
			world.postEvent(7, 0, 0, 0, null);
			world.postEvent(ScriptWorld.EVENT_MOVIE_DONE, name, 0, 0, world.player()); // MpegController-EvtMpegDone: Filter 1 센서 → 보낸 이 = 플레이어 (37 문서)
		});
	}

	private static float num(Object o) {
		return o instanceof Number n ? n.floatValue() : 0f;
	}

	private void goToLevel(String wad, String entryName) {
		session.warpLeft = null; // LoadWad: 다른 슬롯이 새 레벨로 바뀐다
		if (leaving) {
			return;
		}
		leaving = true;
		System.out.println("레벨 전환 → " + wad + (entryName != null ? " (" + entryName + ")" : ""));
		pendingEngine.scenes().transitionTo(new GameScene(session, wad, entryName, 0));
	}

	/** 이음 넘기기용 두 슬롯 화면 상태. */
	private List<Resident> residents() {
		List<Resident> out = new ArrayList<>();
		if (nav == null) {
			return out;
		}
		out.add(new Resident(levelName, level.removedInstances(), nav.platformStates()));
		if (otherName != null) {
			out.add(new Resident(otherName, new java.util.HashSet<>(otherHidden), nav.neighbourPlatforms().states()));
		}
		return out;
	}

	private static Resident resident(Arrival a, String wad) {
		if (a == null || a.resident() == null || wad == null) {
			return null;
		}
		for (Resident r : a.resident()) {
			if (r.wad().equalsIgnoreCase(wad)) {
				return r;
			}
		}
		return null;
	}

	private void goToLevel(String wad, Arrival a) {
		if (leaving) {
			return;
		}
		leaving = true;
		System.out.println("레벨 전환 → " + wad + " (" + a.kind() + ", " + a.from() + " 에서)");
		if (a.kind() != LevelLink.Kind.WARP && a.camera() == null && cameraRig != null) {
			a = new Arrival(a.kind(), a.from(), a.pos(), a.yaw(), cameraRig.handoff(), // 이음: 카메라 상태 유지 (30 문서 §7)
					residents()); // 두 슬롯 화면 상태 (떠나는 레벨 → 다른 슬롯, 들어가는 레벨 → 현재)
		}
		// Warp: 원작은 떠나온 레벨을 메모리에 두고 컨텍스트만 끈다 (0x1BC3A0). 바로 그 레벨로 Warp 해 돌아오면 LoadForWarp 가 다시 읽지 않고
		// (0x1BC110 = 1) 그대로 켠다 → 떠날 때의 화면 상태 (Vis 숨김·Animator 자세) 가 남는다 (HDES01E rope3 — 동영상 뒤 VisON, 43 §13.10).
		// 그 사이 다른 로드 (이음·LoadWad) 가 있으면 그 슬롯은 새 레벨로 바뀌므로 잊는다.
		GameScene.Resident back = session.warpLeft;
		if (a.kind() == LevelLink.Kind.WARP) {
			session.warpLeft = nav != null ? new Resident(levelName, level.removedInstances(), nav.platformStates()) : null;
			if (back != null && back.wad().equalsIgnoreCase(wad) && a.resident() == null) {
				a = new Arrival(a.kind(), a.from(), a.pos(), a.yaw(), a.camera(), List.of(back));
			}
		} else {
			session.warpLeft = null;
		}
		pendingEngine.scenes().transitionTo(new GameScene(session, wad, a));
	}

	private Engine pendingEngine;

	/** -Dgow.chain=A,B,C: 각 레벨에서 gow.chain.secs 초(기본 20) 뒤 다음 레벨로 LoadWad (누수 측정, 48 문서) [엔진 전용]. */
	private static final String[] CHAIN = System.getProperty("gow.chain") != null ? System.getProperty("gow.chain").toUpperCase().split(",") : null;
	private static final float CHAIN_SECS = Float.parseFloat(System.getProperty("gow.chain.secs", "20"));

	/**
	 * 디버그/검증용 스크립트 입력: {@code -Dgow.script="L1:0.5:0.4,R1:0.7:0.05,SQUARE:2:0.05"} (버튼:시작초:누름초).
	 * {@code -Dgow.giveAll} 이면 시작 시 마법 4종·아르테미스의 검·블레이드 Lv2·신들의 분노 게이지 100 지급.
	 */
	private final devple.gow.engine.input.ScriptedInput debugInput = parseDebugScript();

	private static devple.gow.engine.input.ScriptedInput parseDebugScript() {
		String sc = System.getProperty("gow.script");
		if (sc == null || sc.isBlank()) {
			return null;
		}
		List<devple.gow.engine.input.ScriptedInput.Step> steps = new ArrayList<>();
		List<devple.gow.engine.input.ScriptedInput.Stick> sticks = new ArrayList<>();
		for (String part : sc.split(",")) {
			String[] f = part.trim().split(":");
			if (f[0].equalsIgnoreCase("LS") || f[0].equalsIgnoreCase("RS")) { // 스틱: LS:시작초:누름초:x:y (48 문서)
				sticks.add(new devple.gow.engine.input.ScriptedInput.Stick(Float.parseFloat(f[1]), Float.parseFloat(f[2]),
						f[0].equalsIgnoreCase("RS"), Float.parseFloat(f[3]), Float.parseFloat(f[4])));
				continue;
			}
			steps.add(new devple.gow.engine.input.ScriptedInput.Step(Float.parseFloat(f[1]), Float.parseFloat(f[2]),
					PadButton.valueOf(f[0].toUpperCase())));
		}
		var in = new devple.gow.engine.input.ScriptedInput(0, steps).loop(Float.parseFloat(System.getProperty("gow.script.loop", "0")));
		sticks.forEach(in::stick);
		return in;
	}

	/**
	 * SpawnEnemy (36 문서 §4): TemplateId = AI1 번호.
	 * <ol>
	 *   <li>레벨 RSRCS 적 WAD 중 그 AI1 이 있는 종류</li>
	 *   <li>레벨 WAD 자체 AI1 (예 ATHN01D 301 "AOXF1") → 템플릿 Instance Name 과 같은 레벨 오브젝트(aoxf1)의 스켈레톤·모델</li>
	 *   <li>RSRCS 의 비전투 WAD 에만 있는 템플릿 (R_AHH "FakeBHHsnd" 같은 사운드 뱅크 로더) → 전투 개체를 만들지 않는다</li>
	 *   <li>그 밖 (오브젝트가 없는 레벨 템플릿 = 히드라·메두사 여왕·아레스 같은 보스) → 첫 적 종류를 대역으로 [근사]
	 *       (사망 → DestructionSensor 로 스크립트가 진행되도록)</li>
	 * </ol>
	 * 이전에는 2·3 도 첫 적 종류로 생성해 ATHN01B 등에 엉뚱한 해골 병사가 나왔다.
	 */
	private void spawn(ScriptListener.SpawnRequest r) {
		int tpl = r.templateId();
		// 보스 리소스 (레벨 WAD 안) 가 먼저 (37 문서)
		if (boss != null && boss.silent(tpl)) {
			return; // 소리 전용 가짜 적 (FakeBHAsnd 등)
		}
		EnemyFactory.Kind bossKind = boss != null ? boss.kindFor(tpl) : null;
		// 레벨 템플릿 종류(키 "레벨:오브젝트")는 레벨 WAD 의 모든 AI1 을 가지므로 1단계에서 뺀다
		EnemyFactory.Kind kind = bossKind != null ? bossKind : kinds.entrySet().stream().filter(e -> !e.getKey().contains(":") && e.getValue() != null
				&& e.getValue().hasTemplate(tpl)).map(Map.Entry::getValue).findFirst().orElse(null);
		if (kind == null) {
			kind = levelTemplateKind(tpl);
		}
		EnemyFighter f;
		if (kind != null) {
			f = kind.spawn(tpl);
		} else {
			if (soundOnlyTemplates.contains(tpl)) {
				System.out.printf("스폰 생략: 템플릿 %d 은 사운드 뱅크 로더 (%s)%n", tpl, r.source().name());
				return;
			}
			kind = kinds.entrySet().stream().filter(e -> !e.getKey().contains(":") && e.getValue() != null).map(Map.Entry::getValue)
					.findFirst().orElse(null);
			if (kind == null) {
				return;
			}
			Tweaks merged = new Tweaks();
			merged.load(session.permk);
			merged.load(kind.assets().wad());
			merged.load(level.data().wad);
			boolean has = merged.findNode(String.format("/TweakTemplates/AI1/%03d/", tpl)) != null;
			kind = new EnemyFactory.Kind(kind.assets(), merged, kind.skeleton(), kind.anims(), kind.model(), kind.ai1(), kind.combatFiles(),
					kind.attacks(), kind.block(), kind.idle(), kind.walk(), kind.animNode(), kind.ai());
			f = has ? kind.spawn(tpl) : kind.spawn();
			System.out.printf("보스/특수 템플릿 %d → %s 대역 [근사]%n", tpl, kind.assets().wad().name());
		}
		float[] w = r.world();
		f.pos.set(w[12], w[13], w[14]);
		f.prevPos.set(f.pos);
		if (bossKind != null) {
			// 보스는 스폰 엔티티 방향으로 (헤드리스 BossRig 와 같은 규칙) — 히드라는 이 방향에 고정, 잡기 카메라·동기 정렬의 기준 (37 문서 §4)
			f.yaw = (float) Math.atan2(w[8], w[10]);
		}
		f.setDifficulty(session.difficulty);
		if (bossKind == null && f.brain().tree != null && nav != null) {
			f.setNavWorld(devple.gow.engine.combat.EnemyNav.of(nav.navWorld)); // 바닥·중력·벽·줄 이동 (32 문서 §적 자신의 벽·줄 이동)
		}
		f.setCrowd(() -> bodies); // 생물끼리 밀어내기 0x1E2DD8 — 보스도 (Sold +0x1C 비트 1·BMI Think 이면 자기는 밀지 않음)
		f.setFoe(hero.hittable());
		f.brain().setSightBlocked((eye, to) -> { // 인지 시선 가림 (17 문서 §인지)
			Vector3f d = new Vector3f(to).sub(eye);
			float len = d.length();
			return len > 1f && col.raycast(eye, d, len - 1f) != null;
		});
		f.combat().onSound((h, p) -> session.sound.play(h, p));
		if (magicFx != null) {
			magicFx.addWad(kind.assets()); // 적 WAD 의 이펙트 GO·PlayFX/MFX 템플릿 (40 문서 §9)
			magicFx.bind(f.combat(), devple.gow.engine.fx.MagicFx.joints(f), false); // 적 기술 PlayFX·적중 피 (40 문서)
		}
		f.setIncomingScale(hero::incomingScale);
		EnemyFactory.Kind fk = kind;
		f.onShatter(() -> stoneKill(f, fk));
		if (bossKind == null) {
			f.startSpawnAction(r.spawnAction()); // 스폰 슬롯 7: SPAWN / SKSWALLSPAWN / 도발 (32 문서 §벽 스폰)
		}
		AnimatedModel em = kind.buildModel(enemyShader);
		em.shadowMode = devple.gow.engine.light.ShadowVolume.MODE_SLAB; // AI 생물 (0x1C13E0 → 0x1EBD78 → 0x1BF680, +0x174 bit 31 없음), 47 문서 §7
		em.shadowKey = r.actor() != null ? r.actor().name : null;
		enemies.add(new Enemy(f, em, r.actor(), kind));
		if (boss != null) {
			boss.onSpawn(f, r.actor(), r.templateId(), arena());
		} else {
			bridge.attach(f, r.actor());
		}
		System.out.printf("적 스폰: %s (템플릿 %d, 순찰 %s) at (%.0f, %.0f, %.0f)%n", kind.assets().wad().name(), r.templateId(),
				r.patrol(), f.pos.x, f.pos.y, f.pos.z);
	}

	/** 석상 파괴 보너스 (Bonus 023 "Stone Kill" / 공중 024 "Air Stone Kill": #eed31206 붉은 오브, #2af48b79 메시지). */
	private void stoneKill(EnemyFighter f, EnemyFactory.Kind k) {
		String node = String.format("/TweakTemplates/Bonus/%03d/", hero.ctl.grounded ? 23 : 24);
		var orbs = k.tweaks().rawHash(node, 0xeed31206);
		hero.player.addRedOrbs(orbs.isPresent() ? (int) Float.intBitsToFloat(orbs.getAsInt()) : 15);
		magicFx.effect("shatter", f.pos);
		session.sound.play("SND_PICKUPS_ORBS_E");
	}

	/** 레벨 WAD 자체 AI1 템플릿의 종류 (처음 쓸 때 로드, 없으면 null). */
	private EnemyFactory.Kind levelTemplateKind(int ai1) {
		Tweaks lt = new Tweaks();
		lt.load(level.data().wad);
		String obj = EnemyFactory.levelTemplateObject(lt, ai1);
		if (obj == null) {
			return null;
		}
		String key = levelName + ":" + obj;
		if (!kinds.containsKey(key)) {
			EnemyFactory.Kind k = null;
			try {
				k = EnemyFactory.load(session.rom.resolve(levelName + ".WAD"), session.permk, ai1, obj);
			} catch (IOException | RuntimeException ex) {
				System.out.println("레벨 템플릿 " + ai1 + " (" + obj + ") 로드 실패: " + ex.getMessage());
			}
			kinds.put(key, k);
		}
		return kinds.get(key);
	}

	/** 질문 답 (원작 GameMgr 0x1B8F4C). */
	static final int EVENT_ANSWER = 1021;

	/**
	 * 질문 메뉴 (원작 0x1B9EC0): GameMgr 이 HUD 메뉴 템플릿을 채운다 — 줄 = 메시지, 선택지 "Yes"/"No", 상태 +0x208 = 12, 기본 선택 +0x278 = 1.
	 * 메시지 본문이 "질문 줄 ---- *예 / 아니오" 형식이라 (1005·1013·1019) 그대로 나눈다: 위 = PS2_MenuTemplate_Line1..3, 아래 = Menu1..5,
	 * '*' = 기본 선택. FLP MenuTemplates 프레임 = 10·줄 수 + 선택지 수 (GotoFrame2 는 1 부터: 1 줄 2 지 = 12 = 원작 값) [확실: FLP 배치].
	 */
	private void openQuestion(int id) {
		QuestionMenu q = QuestionMenu.of(session.messages, id);
		List<String> lines = q.lines(), choices = q.choices();
		int def = q.defaultChoice();
		UiVariables v = hud.variables();
		for (int i = 1; i <= 3; i++) {
			v.set("PS2_MenuTemplate_Line" + i, i <= lines.size() ? lines.get(i - 1) : "");
		}
		for (int i = 1; i <= 5; i++) {
			v.set("PS2_MenuTemplate_Menu" + i, i <= choices.size() ? choices.get(i - 1) : "");
		}
		questionId = id;
		questionChoices = choices.size();
		questionChoice = def;
		v.set("PS2_Menu_Choice", questionChoice);
		v.event("PS2_MenuTemplate_Event", q.frame());
		session.sound.play("SND_IG_POPUP");
		System.out.println("질문 " + id + ": " + String.join(" / ", lines) + " → " + choices);
	}

	/**
	 * 확정 (HUD FLP 가 위/아래로 PS2_Menu_Choice 와 TemplateChoice On/Off 를 바꾸고, 확정 시 Pause_Check 5 → PS2_Done = 1) →
	 * 1021 (선택 번호 1.., 질문 번호, 0, 영웅) (원작 0x1B8F4C, PAND01BB: 예 1 / 아니오 2).
	 */
	private void answerQuestion(Object choice) {
		int c = 1;
		try {
			c = (int) Float.parseFloat(String.valueOf(choice));
		} catch (NumberFormatException ignored) {
			// 선택 없음 → 기본 1
		}
		world.postEvent(EVENT_ANSWER, Math.clamp(c, 1, Math.max(1, questionChoices)), questionId, 0, world.player());
		hud.variables().event("PS2_MenuTemplate_Event", 0);
		System.out.println("질문 " + questionId + " 답 " + c);
		questionId = -1;
	}


	private void showMessage(int id, float seconds) {
		String text = session.messages != null ? session.messages.get(id) : null;
		messageId = id;
		if (text == null) {
			messageTimer = seconds > 0 ? seconds : 4f;
			return;
		}
		String[] lines = text.replace("----", "\n").split("\n");
		UiVariables v = hud.variables();
		for (int i = 1; i <= 5; i++) {
			v.set("PS2_MessageTemplate_Line" + i, i <= lines.length ? lines[i - 1].trim() : "");
		}
		v.event("PS2_MessageTemplate_Event", 1);
		messageTimer = seconds > 0 ? seconds : 4f;
		System.out.println("메시지 " + id + ": " + text.replace('\n', ' '));
	}

	private boolean nearSavePoint() {
		for (Vector3f sp : savePoints) {
			if (sp.distance(hero.ctl.position) < 45) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 세이브 포인트 (원작 상태 15 SavePoint → 16 SaveGameMenu → 17 SaveWrite): HUD 의 "저장하시겠습니까?"(PS2_SaveGame_Event) →
	 * 예 → 원작 메모리카드 화면을 {@link MemoryCardFlow} 로 진행 (카드 → 파일 → 덮어쓰기/새 파일 → 저장 중 → 완료).
	 */
	/** 현재 미니게임 아이콘 (35 문서 §6): 타이밍 프롬프트 = 종류 아이콘, 연타 = XButtonPress [추정: 연타 아이콘 선택], 없으면 숨김. */
	private int minigameIconIndex() {
		if (!grab.active()) {
			// 버튼 안내 센서 (슬롯 1, 원작 0x1AAD40) — 엔진은 아이콘 하나만 보이므로 잡기가 없을 때만
			return world.promptActive() ? devple.gow.engine.ui.MinigameIcon.PROMPT : devple.gow.engine.ui.MinigameIcon.NONE;
		}
		if (grab.timed() != null) {
			return grab.timed().iconIndex();
		}
		var q = grab.qte();
		return q != null && q.state() == devple.gow.engine.combat.RepeatPressQte.State.RUNNING
				? devple.gow.engine.ui.MinigameIcon.BUTTON_PRESS : devple.gow.engine.ui.MinigameIcon.NONE;
	}

	private void askSave() {
		paused = saveAsk = true;
		hud.variables().event("PS2_SaveGame_Event", 1);
	}

	private void onSaveAnswered(boolean yes) {
		saveAsk = false;
		if (!yes) {
			paused = false;
			return;
		}
		Vector3f p = new Vector3f(hero.ctl.position);
		float yaw = hero.ctl.yaw;
		saveFlow = MemoryCardFlow.save(hud.variables(), session.messages, SaveGame.dir(),
				() -> SaveGame.capture(session, levelName, new float[]{p.x, p.y, p.z}, yaw));
		saveFlow.start();
	}

	private void updateSaveFlow(float dt) {
		saveFlow.update(dt);
		switch (saveFlow.result()) {
			case SAVED -> {
				checkpoint.set(hero.ctl.position);
				System.out.println("저장 완료: " + MemoryCardFlow.file(SaveGame.dir(), saveFlow.fileIndex()));
				saveFlow = null;
				paused = false;
			}
			case CANCELLED -> {
				saveFlow = null;
				paused = false;
			}
			default -> {
			}
		}
	}

	private void heroDied() {
		hero.player.deaths++;
		hero.player.onCheckpointRestart(hero.player.hp);
		respawn(checkpoint);
	}

	/**
	 * 원작 상태 13 PowerUpMenu 종료 (PSMDone): 메뉴가 바꾼 HUD 값을 진행도로 되돌려 쓴다 (0x1D7700 투자 오브 복사,
	 * Upgrade_LoadWad 0x1D7890 레벨 복사). 무기 레벨이 바뀌면 원작은 R_WPN / 마법 WAD 를 교체한다 (여기서는 수치만).
	 */
	private void onPowerUpClosed() {
		UiVariables v = hud.variables();
		PlayerState ps = hero.player;
		ps.redOrbs = (int) v.number("PS2_PowerOrb_Count", ps.redOrbs);
		for (Weapon w : Weapon.values()) {
			int lv = (int) v.number("PS2_" + w.key + "_Level", ps.level.get(w));
			if (lv != ps.level.get(w)) {
				System.out.printf("업그레이드: %s 레벨 %d → %d%n", w, ps.level.get(w), lv);
			}
			ps.level.put(w, lv);
			ps.invested.put(w, (int) v.number("PS2_" + w.key + "Orb_Count", ps.invested.get(w)));
		}
		powerUp = false;
		paused = false;
	}

	private void onMenuClosed(String name, Object value) {
		paused = false;
		hud.acceptInput = true;
		if (name.endsWith("Menu_Choice") && "1".equals(String.valueOf(value).replace(".0", ""))) {
			respawn(checkpoint);
		}
	}

	@Override
	public void update(Engine e, float dt) {
		if (firstUpdates < 2) { // 적재 직후 첫 틱 시간 (48 문서 §9, -Dgow.perf)
			long t0 = System.nanoTime();
			updateTick(e, dt);
			devple.gow.engine.core.PerfMonitor.loadTime("first update " + levelName, t0);
			firstUpdates++;
			return;
		}
		updateTick(e, dt);
	}

	private int firstUpdates, firstRenders;

	private void updateTick(Engine e, float dt) {
		pendingEngine = e;
		if (session.openingPending && levelName.equals(GameFlow.START_LEVEL)) {
			session.openingPending = false;
			playMovie(e, "Semo01"); // 새 게임 오프닝
			return;
		}
		time += dt;
		if (CHAIN != null && !leaving && time >= CHAIN_SECS) { // 레벨 연속 적재·해제 측정 (-Dgow.chain, 48 문서)
			devple.gow.engine.core.PerfMonitor.requestReport();
			String next = CHAIN[++session.chainIndex % CHAIN.length];
			System.out.println("체인 " + session.chainIndex + " → " + next);
			goToLevel(next, (String) null);
			return;
		}
		if (System.getProperty("gow.debug") != null && ((int) (time * 60)) % 60 == 0) {
			Vector3f hp = hero.ctl.position;
			System.out.printf("[디버그] t=%.0f 영웅 (%.0f, %.0f, %.0f) 지면=%s 바닥높이=%.1f 소리=%d 음악=%s 백엔드=%s%n", time, hp.x, hp.y,
					hp.z, hero.ctl.grounded, col.groundHeight(hp.x, hp.y + STEP, hp.z, 4000), session.sound.active(),
					session.sound.currentMusic(), session.sound.backend().getClass().getSimpleName());
			System.out.printf("[디버그] 카메라 눈 %s 대상 %s%n", cam.eye, cam.target);
		}
		if (!powerUp) {
			hudBinding.update(hero.player, hud.variables());
		}
		if (debugInput != null && questionId >= 0) {
			debugInput.advance(dt); // 질문 메뉴 중에는 게임 틱이 멈추므로 스크립트 입력을 여기서 진행해 HUD 로 (검증용)
			hud.update(debugInput);
		} else {
			hud.update(e);
		}
		if (levelHud != null) {
			if (bridge != null) {
				enemyMeters.update(hud.variables(), bridge.meterTargets());
			}
			if (boss != null) {
				boss.hud(hud.variables()); // 클래스 코드가 직접 쓰는 HUD 변수 (BMI EnemyState, 37 문서 §10)
			}
			levelHud.update(e);
		}
		if (messageTimer > 0 && (messageTimer -= dt) <= 0) {
			hud.variables().event("PS2_MessageTemplate_Event", 0);
			if (messageId >= 0) {
				world.postEvent(1040, messageId, 0, 0, world.player()); // 메시지 끝 (아레나 "완료" 메시지 → 워프 등)
				messageId = -1;
			}
		}
		if (saveFlow != null) {
			updateSaveFlow(dt);
		}
		if (paused || leaving) {
			return;
		}
		session.playTime += dt;
		if (e.input().pressed(PadButton.START)) {
			paused = true;
			hud.variables().event("PS2_PauseMenu_Event", 1);
			return;
		}
		if (e.input().pressed(PadButton.USE) && !hero.combat.active() && nearSavePoint()) {
			askSave();
			return;
		}
		if (questionId >= 0) {
			return; // 질문 메뉴가 열려 있는 동안 게임은 멈춘다 (원작 HUD Pause_Check 5, 입력은 HUD FLP 가 받음)
		}
		if (e.input().pressed(PadButton.USE) && !hero.combat.active()) {
			world.postEvent(SceneNav.EVENT_USE, 0, 0, 0, world.player()); // 버튼 안내 센서 (mode 2 = 볼륨 안일 때만 반응)
		}
		if (e.input().pressed(PadButton.SELECT)) {
			paused = powerUp = true;
			HudBinding.write(hero.player, hud.variables()); // Progress_ToHud
			hud.variables().event("PS2_PSMMenu_Event", 1);
			return;
		}
		Input in = e.input();
		if (debugInput != null) {
			debugInput.advance(dt);
			in = debugInput;
		}
		if (time < autoRun) {
			in = new Input(e.window().handle()) {
				@Override
				public float leftY() {
					return 1f;
				}
			};
		}
		EnemyFighter target = nearestEnemy();
		if (!grab.active()) {
			hero.combat.setTarget(target);
			hero.update(in, camYaw, dt, target != null ? target.pos : null);
			if (boss == null || !boss.heroLocked()) {
				physics(dt);
			}
		}
		updateEnemies(dt);
		if (boss != null) {
			boss.update(in, dt);
		} else {
			bridge.tick();
		}
		if (target != null && (boss == null || !boss.claimsGrab(target))) {
			Enemy te = enemies.stream().filter(x -> x.fighter() == target).findFirst().orElse(null);
			boolean was = grab.active();
			grab.update(boss != null ? boss.minigameInput(in, grab.timed(), grab.qte()) : in, target,
					te != null ? te.kind().tweaks() : null, dt);
			if (was && !grab.active() && grab.executionSucceeded()) {
				target.hp = 0; // 처형 성공 → 사망 처리 (원작은 처형 기술의 피해/스크립트) [근사]
			}
		}
		if (minigameIcon.show(hud.player(), minigameIconIndex())) {
			session.sound.play("SND_ICON");
		}
		Vector3f p = hero.ctl.position;
		nav.update(dt, p);
		poseRopes();
		for (var b : nav.platformBodies()) {
			if (b.anim() != null && b.scripted) { // 정지 CDV 오브젝트의 반복 act 는 렌더가 따로 돌린다 (반복 재생 배치)
				level.poseInstance(b.owner, b.joints(), b.hidden()); // 움직인 발판·문·회전방은 애니 자세로 (관절 숨김 포함: DMC 결계)
			}
		}
		for (var b : nav.neighbourPlatforms().bodies()) { // 다른 슬롯 레벨 문·발판 (43 §13.8)
			if (b.anim() != null && b.scripted) {
				otherPosed.put(b.owner, new Object[]{b.joints(), b.hidden()});
			}
		}
		breakables.update(hero.combat, world, this::onBreak);
		cameraRig.setHeroState(heroStateBits(), enemies.stream().anyMatch(en -> !en.fighter().dead() && en.fighter().brain() != null
				&& en.fighter().brain().state() == devple.gow.engine.combat.AiBrain.State.COMBAT));
		cameraRig.update(dt, p, hero.ctl.yaw, !hero.ctl.grounded);
		session.sound.setUnderwater(cameraRig.underwater()); // 수중 카메라 → 소리 수중 처리 (29 문서 §10)
		world.tick(dt, p.x, p.y, p.z);
		hero.tickBlades(dt); // 블레이드 사슬 (0x1FDB18): 이번 틱 자세가 정해진 뒤, 블레이드 GO 앵커보다 먼저
		magicFx.update(hero.magic, p, hero.ctl.yaw, enemies.stream().map(Enemy::fighter).toList(), dt, cam.eye);
		for (BladeChain.Side s : hero.chain.sides) { // 블레이드 벽 불꽃 (0x1FAFC8 → 0x202248, 45 문서 §3.7)
			if (s.wallHit) {
				magicFx.bladeSpark(s.index, s.wallSheetFx, s.wallPoint, s.wallNormal, (n, at) -> session.sound.play(n, at));
			}
		}
		magicFx.weaponWorld(hero.combat, (n, at) -> session.sound.play(n, at)); // 무기-월드 베기 Slice Hit (40 문서 §6)
		if (hero.player.hp <= 0) {
			heroDied();
		}
		footsteps(dt);
		Vector3f fwd = new Vector3f(cam.target).sub(cam.eye).normalize();
		session.sound.listener(cam.eye, fwd, new Vector3f(0, 1, 0));
		session.sound.update(dt);
	}

	/** 줄 스키닝 렌더 (47 문서 §5, 38 §17): 줄 오브젝트 하나 = 정지 관절 + 사슬 관절 + 시뮬레이션. */
	private record RopeVisual(String owner, float[][] base, int[] chain, devple.gow.engine.nav.RopeSim sim, boolean fixed) {
	}

	private final List<RopeVisual> ropeVisuals = new ArrayList<>();

	/**
	 * 레벨의 모든 줄에 시뮬레이션을 만든다 (원작은 SCR_RopeSkinned 마다 매 프레임 0x217A40 — 영웅이 없어도). -Dgow.norope 면 예전 정지 자세 (비교용).
	 */
	private void buildRopeVisuals() {
		ropeVisuals.clear();
		if (System.getProperty("gow.norope") != null) {
			return;
		}
		for (devple.gow.engine.nav.NavRope r : nav.navWorld.ropes()) {
			for (var pl : level.data().layout.placements()) {
				if (!pl.instance().name().equals(r.owner()) || pl.object().skeleton() == null) {
					continue;
				}
				int[] chain = devple.gow.engine.nav.RopeSkin.chain(pl.object().skeleton(), pl.jointWorld(), 0);
				if (chain.length >= 2) {
					ropeVisuals.add(new RopeVisual(r.owner(), pl.jointWorld(), chain, nav.navigator.sim(r),
							r.type() != devple.gow.tools.level.RopeDef.Type.HANG));
				}
				break;
			}
		}
		poseRopes(true);
	}

	private void poseRopes() {
		poseRopes(false);
	}

	/** 잠든 줄은 관절을 갱신하지 않는다 (0x217B38 → 0x218168). */
	private void poseRopes(boolean all) {
		for (RopeVisual v : ropeVisuals) {
			if (System.getProperty("gow.ropedebug") != null && v.sim().attached() && (lightFrame++ % 60) == 0) {
				StringBuilder sb = new StringBuilder("줄 " + v.owner() + " 영웅 " + hero.ctl.position + " 노드");
				for (Vector3f q : v.sim().nodePositions()) {
					sb.append(String.format(java.util.Locale.ROOT, " (%.0f,%.0f,%.0f)", q.x, q.y, q.z));
				}
				System.out.println(sb);
			}
			if (all || !v.sim().sleeping()) {
				level.poseInstance(v.owner(), devple.gow.engine.nav.RopeSkin.pose(v.base(), v.chain(), v.sim().nodePositions(), v.fixed()));
			}
		}
	}

	private EnemyFighter nearestEnemy() {
		EnemyFighter best = null;
		float bd = Float.MAX_VALUE;
		for (Enemy en : enemies) {
			if (en.fighter().dead()) {
				continue;
			}
			float d = en.fighter().pos.distanceSquared(hero.ctl.position);
			if (d < bd) {
				bd = d;
				best = en.fighter();
			}
		}
		return best;
	}

	private void updateEnemies(float dt) {
		collectBodies();
		for (Enemy en : enemies) {
			EnemyFighter f = en.fighter();
			boolean wasDead = f.dead();
			f.update(dt);
			// 바닥: 계단 높이 안이면 붙이고, 더 아래면 중력으로 떨어진다 (높은 스폰 지점 — ATHN08B 분신 파도 등) [근사: 원작 적 낙하 물리]
			float g = f.nav().world() != null ? Float.NaN : ground(f.pos.x, f.pos.y + STEP, f.pos.z, 4000); // EnemyNav 가 직접 처리
			float vy = enemyFall.getOrDefault(f, 0f);
			if (!Float.isNaN(g) && f.pos.y - g <= STEP && vy >= 0) {
				f.pos.y = g;
				enemyFall.remove(f);
			} else if (!Float.isNaN(g)) {
				vy -= GRAVITY * dt;
				f.pos.y = Math.max(g, f.pos.y + vy * dt);
				if (f.pos.y <= g) {
					enemyFall.remove(f);
				} else {
					enemyFall.put(f, vy);
				}
			}
			if (en.actor() != null) {
				en.actor().moveTo(f.pos.x, f.pos.y, f.pos.z);
			}
			if (f.dead() && f.deadTime() == 0 && !wasDead || (f.dead() && en.actor() != null && !en.actor().destroyed() && f.deadTime() > 0.5f)) {
				if (en.actor() != null && !en.actor().destroyed()) {
					world.destroy(en.actor()); // DestructionSensor (예: 튜토리얼 메시지)
					String emitter = en.kind().assets().wad().name().replace(".WAD", "").substring(2) + " Death";
					for (OrbDrops.Orb o : OrbDrops.roll(orbTweaks, emitter, rnd, hero.player.hp, hero.player.mp)) {
						hero.player.pickupOrb(o.type(), o.points());
					}
					session.sound.play("SND_PICKUPS_ORBS_E");
				}
			}
		}
		enemies.removeIf(en -> {
			if (en.fighter().dead() && en.fighter().deadTime() > 4f) {
				en.model().close();
				magicFx.unbind(en.fighter().combat(), en.fighter());
				enemyFall.remove(en.fighter());
				return true;
			}
			return false;
		});
	}

	/**
	 * 발소리: 원작 SND_FOOTSTEP_&lt;표면&gt; 은 레지스터 0 으로 STEP/WALK/RUN/LAND 를 고른다 (14 문서).
	 * 표면 = 발밑 충돌 면의 SheetFX → TWK /TweakTemplates/MFX/&lt;n&gt;/ "Foot Sound" (38 문서 §1, 예 20 Rock → SND_FOOTSTEP_STONE),
	 * 없으면 PLANK. 주기 = 이동 클립 길이의 절반 (한 걸음).
	 */
	private void footsteps(float dt) {
		var c = hero.ctl;
		String surface = footSound(c.position);
		if (!c.grounded) {
			wasAir = true;
			return;
		}
		if (wasAir) {
			wasAir = false;
			session.sound.play(surface, c.position, 3); // LAND
			magicFx.land(surfaceFx(c.position), c.position, c.yaw); // MFX Jump Land Effect (0x221EA8)
		}
		var cur = c.animator().current();
		String n = cur != null ? cur.name : "";
		boolean run = n.equals("walkBlend2"), walk = n.equals("walkBlend1");
		if (hero.combat.active() || !(run || walk)) {
			stepTimer = 0;
			return;
		}
		if ((stepTimer -= dt) <= 0) {
			stepTimer = cur.duration / 2;
			session.sound.play(surface, c.position, run ? 2 : 1);
			if (run) {
				magicFx.footstep(surfaceFx(c.position), c.position, c.yaw); // MFX Run Dust Name (40 문서 §6.3)
			}
		}
	}

	/** 이펙트 연결 (40/41 문서): 레벨·적 WAD 이펙트 GO, SEM 소리 → SoundManager, 충돌 질의. */
	private void wireEffects() {
		magicFx.addWad(level.assets());
		kinds.values().stream().filter(k -> k != null).forEach(k -> magicFx.addWad(k.assets()));
		magicFx.setSounds(new devple.gow.engine.fx.EffectSystem.Sounds() {
			public int play(String snd, Vector3f at, float near, float far) {
				int h = session.sound.play(snd, at);
				if (h > 0 && far > near && near > 0) {
					session.sound.setRange(h, near, far);
				}
				return h;
			}

			public void move(int h, Vector3f at) {
				session.sound.setPosition(h, at);
			}

			public void stop(int h) {
				session.sound.stop(h);
			}
		});
		magicFx.bladeSliceNative = true;
		hero.setBladeWalls((from, to, exclude) -> { // 0x1FAFC8 벽 질의: 정적 충돌 면 (제외 마스크 0x0B130000) [근사: CDV GO 몸체 제외]
			Vector3f d = new Vector3f(to).sub(from);
			float len = d.length();
			LevelCollision.Hit h = len > 1e-3f && col != null ? col.raycast(from, d.div(len), len, t -> (t.attrs() & exclude) == 0) : null;
			if (h == null) {
				return null;
			}
			int fx = h.tri().info() != null ? h.tri().info().sheetFx() : 0;
			return new float[]{h.point().x, h.point().y, h.point().z, h.tri().n().x, h.tri().n().y, h.tri().n().z, fx};
		});
		magicFx.setSurface(new devple.gow.engine.fx.MagicFx.Surface() {
			public float[] cast(Vector3f from, Vector3f to) {
				Vector3f d = new Vector3f(to).sub(from);
				float len = d.length();
				LevelCollision.Hit h = len > 1e-3f ? col.raycast(from, d.div(len), len) : null;
				if (h == null) {
					return null;
				}
				int fx = h.tri().info() != null ? h.tri().info().sheetFx() : 0;
				return new float[]{h.point().x, h.point().y, h.point().z, h.tri().n().x, h.tri().n().y, h.tri().n().z, fx};
			}

			public float[] floor(Vector3f p, float maxDrop) {
				LevelCollision.Floor f = col.floorAt(p.x, p.y, p.z, maxDrop);
				return f == null ? null : new float[]{f.y(), f.tri().info() != null ? f.tri().info().sheetFx() : 0};
			}
		});
	}

	/** 부서지는 물체 (41 문서): 정적 메시 숨김 → 물체 GO 파괴 애니·먼지·소리, Brkb OrbEmitter 오브. */
	private void onBreak(String owner) {
		level.hideInstance(owner);
		Breakables.Item it = breakables.item(owner);
		if (it == null) {
			return;
		}
		if (it.matrix != null) {
			magicFx.breakObject(owner, new org.joml.Matrix4f().set(it.matrix), it.fadeDelay, it.fadeTime, it.debrisPhysics);
		}
		List<OrbDrops.Orb> orbs = OrbDrops.rollNode(orbTweaks, it.orbNode(), rnd, hero.player.hp, hero.player.mp);
		for (OrbDrops.Orb o : orbs) {
			hero.player.pickupOrb(o.type(), o.points());
		}
		if (!orbs.isEmpty()) {
			session.sound.play("SND_PICKUPS_ORBS_E");
		}
		System.out.println("파괴: " + owner + " 오브 " + orbs.size());
	}

	private int surfaceFx(Vector3f p) {
		LevelCollision.Floor f = col.floorAt(p.x, p.y + 4, p.z, 8);
		return f != null && f.tri().info() != null ? f.tri().info().sheetFx() : 0;
	}

	private String footSound(Vector3f p) {
		int fx = surfaceFx(p);
		String s = fx > 0 ? orbTweaks.s(String.format("/TweakTemplates/MFX/%03d/", fx), "Foot Sound", null) : null;
		return s != null && s.startsWith("SND_") ? s : "SND_FOOTSTEP_PLANK";
	}

	/** 밀어내기 참여 몸: 영웅 (크라토스 Sold_000) + 내비게이션을 쓰는 적. */
	private void collectBodies() {
		bodies.clear();
		if (heroBody == null) {
			heroBody = new devple.gow.engine.combat.BodyPush.HeroBody(hero.ctl, () -> !hero.ctl.grounded, hero.combat);
		}
		heroBody.refresh(); // 관절 상자 0x1E6DF0 (머리)
		bodies.add(heroBody);
		for (Enemy en : enemies) {
			bodies.add(en.fighter()); // 생물 범주 1|2 = 모든 적 (보스 포함, 32 문서 §생물끼리 밀어내기 — 보스)
		}
	}

	private devple.gow.engine.combat.BodyPush.HeroBody heroBody;

	/** 지면 추종 + 중력 + 벽 밀어내기. 내비게이터가 위치를 정하는 상태(매달림·줄·밀기 …)면 건너뛴다. */
	private void physics(float dt) {
		Vector3f p = hero.ctl.position;
		if (nav.navigator.active()) {
			return;
		}
		nav.carry(p, hero.ctl.grounded); // 발판에 실려 감
		// 허공 방지: 지상에서 이동한 결과 아래에 바닥이 전혀 없으면(배 가장자리 밖 등) 수평 이동 취소.
		// 원작의 보이지 않는 벽 / 바다 데스존을 대신하는 근사 — 아래층 바닥이 있는 낙하는 허용
		if (hero.ctl.grounded && Float.isNaN(ground(p.x, p.y + STEP, p.z, 4000))
				&& !Float.isNaN(ground(hero.ctl.prevPosition.x, hero.ctl.prevPosition.y + STEP, hero.ctl.prevPosition.z, 4000))) {
			p.x = hero.ctl.prevPosition.x;
			p.z = hero.ctl.prevPosition.z;
		}
		float g = ground(p.x, p.y + STEP, p.z, 4000);
		var c0 = hero.ctl;
		if (!Float.isNaN(g) && p.y - g <= 2f && c0.vy <= 0) {
			p.y = g;
			c0.vy = 0;
			c0.grounded = true;
		} else {
			c0.vy = Math.max(c0.vy - GRAVITY * dt, -devple.gow.engine.nav.HeroNavigator.FALL_MAX);
			p.y += c0.vy * dt;
			c0.grounded = false;
			if (!Float.isNaN(g) && p.y < g) {
				p.y = g;
				c0.vy = 0;
				c0.grounded = true;
			}
		}
		if (Float.isNaN(g) && p.y < checkpoint.y - 3000) {
			heroDied(); // 바닥 없는 곳으로 떨어짐
		}
		// 생물끼리 밀어내기 (0x1E2DD8 — 벽 충돌 앞, 영웅도 같은 내비게이션 코드)
		collectBodies();
		if (heroBody.pushesSelf()) {
			devple.gow.engine.combat.BodyPush.push(heroBody, p, bodies, null);
		}
		Vector3f c = new Vector3f(p.x, p.y + 20, p.z);
		boolean pushed = col.collideSphere(c, 12);
		if (otherCol != null) {
			pushed |= otherCol.collideSphere(c, 12);
		}
		if (nav != null) {
			pushed |= nav.collideBodies(c, 12); // 문·창살·회전방 CDV (42 문서 §5)
		}
		if (pushed) {
			p.x = c.x;
			p.z = c.z;
		}
		// 다른 슬롯 레벨 바닥만 밟고 있으면 그 레벨로 (원작은 두 레벨이 같이 상주, 예 SEWR01 → SEWR02)
		if (otherCol != null && !otherName.equals(hostLevel) && c0.grounded && Float.isNaN(col.groundHeight(p.x, p.y + STEP, p.z, 64))
				&& !Float.isNaN(otherCol.groundHeight(p.x, p.y + STEP, p.z, 64))) {
			if ((otherFloorTime += dt) > 0.25f) { // 이음매에서 오가며 바뀌지 않도록 잠깐 머문 뒤
				goToLevel(otherName, new Arrival(LevelLink.Kind.WALK, levelName, new float[]{p.x, p.y, p.z}, hero.ctl.yaw));
			}
		} else {
			otherFloorTime = 0;
		}
	}

	/** 이번 프레임 렌더 보간 값 (반사 패스 콜백용). */
	private float renderAlpha = 1f;

	private void updateCamera(float alpha) {
		// 원작 카메라 시스템 (CMV 선택 + 레일/궤도 + 전환 보간): CameraRig, 30 문서. 갱신은 update() 틱에서
		cameraRig.apply(cam, alpha);
		camYaw = (float) Math.atan2(-(cam.target.x - cam.eye.x), -(cam.target.z - cam.eye.z));
	}

	@Override
	public void render(Engine e, float alpha) {
		if (firstRenders < 2) { // 적재 직후 첫 그리기 시간 (48 문서 §9, -Dgow.perf)
			long t0 = System.nanoTime();
			renderFrame(e, alpha);
			devple.gow.engine.core.PerfMonitor.loadTime("first render " + levelName, t0);
			firstRenders++;
			return;
		}
		renderFrame(e, alpha);
	}

	private void renderFrame(Engine e, float alpha) {
		renderAlpha = alpha;
		updateCamera(alpha);
		level.fx().env().setFogOverride(magicFx.screen.fog()); // 포세이돈 Fog Template (0x195C90, 35 문서 §4.6)
		if (shadows != null) {
			// 주 뷰의 레벨 스키닝 GO (모드 0, SCR_Shadow 면 1) + 적·영웅 (47 문서 §11). 거울 반사 패스는 LevelScene 이 멈춘다
			shadows.begin();
		}
		level.render(e, alpha);
		renderOtherSlot(e);
		// 캐릭터에도 레벨 안개 (같은 basic.frag 유니폼)
		var env = level.fx().env();
		env.applyFog(hero.shader(), cam, e.window().height());
		env.applyFog(enemyShader, cam, e.window().height());
		Matrix4f m = new Matrix4f();
		for (Enemy en : enemies) {
			if (en.fighter().shattered()) {
				continue; // 부서진 석상 (원작은 *stonedbrk 파편 모델) [근사: 숨김]
			}
			en.model().setVariant(en.fighter().modelVariant); // BMI 단계별 갑옷 (37 문서 §10)
			en.model().draw(cam, e.window().aspect(), en.fighter().world(m, alpha), en.fighter().animator().pose(),
					e.renderer().white());
		}
		hero.draw(cam, e.window().aspect(), alpha, e.renderer().white());
		renderShadows(e.window().aspect(), alpha);
		if (System.getProperty("gow.lightdebug") != null && hero.lastLights() != null && (lightFrame++ % 30) == 0) {
			System.out.println("영웅 라이트: " + hero.lastLights() + " 블레이드 " + (hero.bladeLight(0) != null ? hero.bladeLight(0).dyn : -1f));
		}
		devple.gow.engine.fx.FxEnvironment.disableFog(hero.shader());
		hero.drawChains(cam, e.window().aspect(), alpha, magicFx.godBladesOn()); // 사슬·궤적 (45 문서 §3)
		devple.gow.engine.fx.FxEnvironment.disableFog(enemyShader);
		if (System.getProperty("gow.nofx") == null) {
			level.renderEffects(e, cam); // 비·파티클·물은 캐릭터 뒤에 (--no-fx 로 끔: 스크린샷용)
		}
		magicFx.render(cam, e.window().aspect(), e.renderer().white());
		renderCameraFilter();
		if (levelHud != null) {
			levelHud.render(e);
		}
		hud.render(e);
	}

	/** 캐릭터 그림자 (47 문서 §7). null = 끔. */
	private devple.gow.engine.light.ShadowCasters shadows;
	private devple.gow.engine.render.ShadowRenderer shadowRenderer;
	private final devple.gow.engine.light.ShadowVolume.Out shadowTris = new devple.gow.engine.light.ShadowVolume.Out();
	private int shadowFrame;

	/** 모은 그림자 볼륨을 불투명 캐릭터 뒤, 반투명 (사슬·이펙트) 앞에 그린다. 받는 면 = 현재 + 다른 슬롯 레벨 충돌 (NoShadows 면 제외, 0x1BF658). */
	private void renderShadows(float aspect, float alpha) {
		if (shadows == null) {
			return;
		}
		hero.submitChainShadows(shadows, alpha);
		shadowTris.clear();
		shadows.build(lights, this::shadowReceiver, cam.eye, shadowTris);
		if (System.getProperty("gow.shadowdebug") != null && (shadowFrame++ % 60) == 0) {
			System.out.println("그림자: " + shadows.lastCasters + " 물체, 삼각형 " + shadows.lastTriangles + " " + shadows.describe());
		}
		if (shadowTris.triangles() > 0) {
			if (shadowRenderer == null) {
				shadowRenderer = new devple.gow.engine.render.ShadowRenderer();
			}
			shadowRenderer.draw(cam, aspect, shadowTris);
		}
	}

	private org.joml.Vector4f shadowReceiver(Vector3f from, Vector3f dir, float max) {
		java.util.function.Predicate<LevelCollision.Tri> f = t -> !t.has(devple.gow.tools.level.SurfaceAttr.NO_SHADOWS);
		LevelCollision.Hit h = col.raycast(from, dir, max, f);
		if (otherCol != null) {
			LevelCollision.Hit o = otherCol.raycast(from, dir, h != null ? h.distance() : max, f);
			if (o != null) {
				h = o;
			}
		}
		if (h == null) {
			return null;
		}
		Vector3f n = h.tri().n();
		return new org.joml.Vector4f(n.x, n.y, n.z, -n.dot(h.point()));
	}

	private devple.gow.engine.render.CameraFilterRenderer filterRenderer;
	/** 동적 라이트 서버 (47 문서). -Dgow.nolights 면 null. */
	private devple.gow.engine.light.LightServer lights;
	private int lightFrame;
	private final Map<Integer, java.util.Optional<devple.gow.engine.fx.CameraFilter>> filters = new java.util.HashMap<>();

	/** 이번 프레임 카메라 필터 번호 (0x1EFA14: 카메라 +0x910 대체 ≥ 0 이면 그것, 아니면 현재 카메라 CAM 0xB0). 테스트·디버그용. */
	public int cameraFilterIndex() {
		String force = System.getProperty("gow.camfilter"); // 디버그: 번호 강제 (스크린샷 검증)
		if (force != null) {
			return Integer.parseInt(force);
		}
		return devple.gow.engine.fx.CameraFilter.select(magicFx.screen.filter(), cameraRig.filter());
	}

	/** 카메라 필터 (45 문서 §1): 3D 장면 뒤, HUD 앞 [추정: 원작 노드 층 3 / 우선순위 7]. */
	private void renderCameraFilter() {
		if (System.getProperty("gow.nocamfilter") != null || orbTweaks == null) {
			return;
		}
		int n = cameraFilterIndex();
		if (n < 0) {
			return;
		}
		var f = filters.get(n); // (computeIfAbsent 람다는 매 프레임 할당)
		if (f == null) {
			f = java.util.Optional.ofNullable(devple.gow.engine.fx.CameraFilter.load(orbTweaks, n));
			filters.put(n, f);
		}
		if (f.isEmpty() || !f.get().visible()) {
			return;
		}
		if (filterRenderer == null) {
			filterRenderer = new devple.gow.engine.render.CameraFilterRenderer();
		}
		filterRenderer.draw(f.get(), cam);
	}

	/** 다른 슬롯 레벨 그리기용 장면 (CPU 단계 진행 중, 레벨 이름 → 결과). */
	private final Map<String, java.util.concurrent.Future<LevelScene>> otherPrep = new HashMap<>();
	/**
	 * 떠난 다른 슬롯 장면 (GL 상주, 최근 순) — DEST01 처럼 두 이웃 (DEST00/02) 경계를 오가면 LoadWad 가 같은 레벨을 번갈아 올린다.
	 * 다시 읽고 올리는 대신 최근 {@code OTHER_CACHE} 개를 붙잡아 둔다 [엔진 근사: 원작 슬롯은 2 개라 바뀐 슬롯은 다시 읽는다, 48 §8].
	 * 이 레벨을 떠날 때 모두 닫는다.
	 */
	private final java.util.LinkedHashMap<String, LevelScene> otherCache = new java.util.LinkedHashMap<>(4, 0.75f, true);
	private static final int OTHER_CACHE = devple.gow.engine.asset.Loader.async() ? Integer.getInteger("gow.otherCache", 2) : 0; // 동기 = 예전 그대로
	private String otherSceneName;

	/** 다른 슬롯 레벨 그리기용 장면의 CPU 단계 시작 (이미 진행 중이면 그대로). */
	private void prepareOther(String wad) {
		java.nio.file.Path p = session.rom.resolve(wad + ".WAD");
		if (otherPrep.containsKey(wad) || !Files.exists(p)) {
			return;
		}
		otherPrep.put(wad, devple.gow.engine.asset.Loader.submit("other " + wad, () -> new LevelScene(p, -2, false).prepare()));
	}

	/** 다른 슬롯 레벨 지오메트리 (바뀌면 교체: 캐시 → 준비 끝난 장면 GL 올리기, 준비 중이면 다음 프레임). */
	private void renderOtherSlot(Engine e) {
		if (otherScenePending) {
			otherScenePending = false;
			if (otherScene != null) {
				otherCache.put(otherSceneName, otherScene); // 닫지 않고 캐시로
				otherScene = null;
				otherSceneName = null;
			}
			if (otherName != null) {
				LevelScene c = otherCache.remove(otherName);
				if (c != null) {
					c.clearPoses(); // 새로 읽은 것과 같은 정지 자세에서 (자세·숨김은 매 프레임 다시 넣음)
					otherScene = c;
					otherSceneName = otherName;
				} else {
					prepareOther(otherName);
				}
			}
			while (otherCache.size() > OTHER_CACHE) {
				var it = otherCache.entrySet().iterator();
				it.next().getValue().exitDeferred(e); // GL 해제는 다음 틱들로 (48 §9)
				it.remove();
			}
		}
		if (otherScene == null && otherName != null) {
			var f = otherPrep.get(otherName);
			if (f != null && f.isDone()) {
				otherPrep.remove(otherName);
				try {
					long t0 = System.nanoTime();
					LevelScene s = devple.gow.engine.asset.Loader.join(f, "other " + otherName);
					s.enter(e);
					otherScene = s;
					otherSceneName = otherName;
					devple.gow.engine.core.PerfMonitor.loadTime("otherScene " + otherName, t0);
				} catch (RuntimeException ex) {
					System.out.println("이웃 레벨 렌더 실패 " + otherName + ": " + ex.getMessage());
				}
			}
		}
		otherPrep.keySet().removeIf(k -> !k.equals(otherName)); // 지나간 요청 (끝나면 버려짐, GL 없음)
		if (otherScene != null) {
			otherScene.setRemoved(otherHidden); // 이웃 스크립트 Vis 숨김 (SceneNav.Host.neighbourVisible)
			otherPosed.forEach((k, v) -> otherScene.poseInstance(k, (float[][]) v[0], (boolean[]) v[1]));
			otherScene.renderNeighbour(e, cam);
		}
	}

	@Override
	public void exit(Engine e) {
		long exitT0 = System.nanoTime();
		if (lights != null && devple.gow.engine.light.LightServer.current() == lights) {
			devple.gow.engine.light.LightServer.setCurrent(null);
		}
		if (shadows != null && devple.gow.engine.light.ShadowCasters.current() == shadows) {
			devple.gow.engine.light.ShadowCasters.setCurrent(null);
		}
		if (shadowRenderer != null) {
			shadowRenderer.close();
		}
		if (otherScene != null) {
			otherScene.exitDeferred(e);
			otherScene = null;
		}
		otherCache.values().forEach(s -> s.exitDeferred(e)); // GL 해제는 다음 틱들로 (48 §9)
		otherCache.clear();
		otherPrep.clear(); // 진행 중인 CPU 단계는 끝나면 버려진다 (GL 없음)
		if (levelPrep != null) { // enter 없이 버려진 장면 (없어야 정상)
			levelPrep = null;
		}
		session.sound.stopAll();
		session.sound.unload(level.data().wad.name());
		kinds.values().stream().filter(k -> k != null).forEach(k -> session.sound.unload(k.assets().wad().name()));
		soundOnly.forEach(session.sound::unload);
		session.sound.trimFileCache();
		enemies.forEach(en -> en.model().close());
		enemyShader.close();
		magicFx.close();
		// 적·보스 종류의 WAD 자산 (텍스처·모델) — 이전에는 닫지 않아 레벨 전환마다 GL 텍스처가 남았다 (48 문서 §4). 같은 자산을 나눠 쓰는
		// 종류 (보스 템플릿 여럿 = 레벨 WAD 하나) 는 한 번만
		java.util.Set<WadAssets> kindAssets = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		kinds.values().stream().filter(k -> k != null).forEach(k -> kindAssets.add(k.assets()));
		if (boss != null) {
			boss.kinds().values().stream().filter(k -> k != null).forEach(k -> kindAssets.add(k.assets()));
		}
		kindAssets.forEach(WadAssets::close);
		// 대상 슬롯 (AiTargets.SLOTS, 정적 WeakHashMap): 값 Slot 이 담당 AiBrain → 적 → 영웅 Hittable (= 키) 을 강하게 잡아 키가 풀리지 않았다 →
		// 떠난 GameScene 전체 (레벨·WAD·모델) 가 남았다 (48 문서 §4). 슬롯은 이 레벨의 대상 것뿐이므로 비운다
		devple.gow.engine.combat.AiTargets.clear();
		devple.gow.engine.core.PerfMonitor.removeGauge("enemies");
		devple.gow.engine.core.PerfMonitor.removeGauge("level");
		if (filterRenderer != null) {
			filterRenderer.close();
		}
		hero.close();
		hud.close();
		if (levelHud != null) {
			levelHud.close();
		}
		level.exitDeferred(e); // GL 해제는 다음 틱들로 (48 §9)
		devple.gow.engine.core.PerfMonitor.loadTime("exit " + levelName, exitT0);
	}
}
