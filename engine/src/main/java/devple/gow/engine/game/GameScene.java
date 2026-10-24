package devple.gow.engine.game;

import devple.gow.tools.content.Content;
import devple.gow.tools.content.Stages;
import devple.gow.tools.content.Region;
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
	/** 적이 바닥 없는 곳으로 이만큼 떨어지면 사망 (영웅의 바닥 없는 낙하 사망과 같은 3000) [근사]. */
	static final float VOID_FALL = 3000f;

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
	private HudBinding hudBinding = new HudBinding();
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
	/**
	 * 체크포인트 때 영웅 방향 — 원작 스냅샷은 플레이어 월드 행렬 전체 (11 문서 §4 블록 0x00) 를 저장하고 재시작이 그대로 돌린다. 예전 엔진은 레벨 시작 방향
	 * (spawnYaw) 으로 되살렸다 (43 문서 §19, 영상 7053: ATHN05B 발코니 받침대 옆).
	 */
	private float checkpointYaw;
	/** 원작 GameMgr 메시지 0 의 같은 엔티티 5 초 거름 (0x303D74 엔티티 · 0x303D78 시각). */
	private ScriptEntity lastCheckpointSource;
	private float lastCheckpointTime;
	private float spawnYaw;
	private float camYaw;
	/** 영웅 입력 기준 (카메라 컷 뒤 스틱 방향 유지, 원작 0x1DCE80 / 0x1DD1E8 — 30 문서 §9.1). */
	private final devple.gow.engine.input.InputFrame inputFrame = new devple.gow.engine.input.InputFrame();
	private boolean wasIdle;
	private boolean paused;
	/** 파워업(업그레이드) 메뉴 열림: 메뉴 스크립트가 투자를 처리하므로 HUD 값 쓰기를 멈춘다. */
	private boolean powerUp;
	/** 세이브 포인트: 저장 질문(SaveGameMenu) 대기 중 / 메모리카드 화면 진행 중 (22 문서 §7). */
	private boolean saveAsk;
	private MemoryCardFlow saveFlow;
	/**
	 * 세이브 메뉴 닫기 대기 (초): 원작 상태 18 0x220740 은 프레임 카운터 0x29F468 (10) 이 다 돼야 HUD SaveGame_Event(+0x268) = 0
	 * (FLP SaveGameMenu Animate_Off — Ω 배경이 사라짐) 을 쓰고 상태 11 (놀이) 로 간다. 그동안 게임은 멈춰 있다.
	 * {@link #saveReask} 면 대신 저장 질문을 다시 띄운다 (상태 17 의 -1 갈래 0x22063C).
	 */
	private float saveCloseTimer;
	private boolean saveReask;
	private final devple.gow.engine.ui.MinigameIcon minigameIcon = new devple.gow.engine.ui.MinigameIcon();
	private float time;
	private float messageTimer;
	/** 다른 슬롯 레벨 렌더 (원작은 두 슬롯 모두 그림). */
	private LevelScene otherScene;
	/** 부서지는 물체 (Breakable / Brkb, 41 문서). */
	private Breakables breakables;
	/**
	 * 다른 슬롯 레벨의 부서지는 물체 — 원작은 두 슬롯 GO 가 한 세계에 있어 화살·공격이 다른 슬롯 물체도 맞힌다 (ATHN05B 발리스타 볼트 → ATHN05BB
	 * BrkbDoor, 43 §22) [확실: 두 슬롯 상주 / 근사: 엔진은 다른 슬롯 이펙트 GO 를 처음 부술 때 그 WAD 를 이펙트 라이브러리에 올린다].
	 */
	private Breakables otherBreakables;
	/** 떨어지는 중인 적의 수직 속도. */
	private final java.util.Map<EnemyFighter, Float> enemyFall = new java.util.IdentityHashMap<>();
	private boolean otherScenePending;
	/** 다른 슬롯 레벨 스크립트가 Vis 로 숨긴 인스턴스 (소문자, 렌더 스레드가 otherScene 에 건다). 다른 슬롯이 바뀌면 비운다. */
	private final java.util.Set<String> otherHidden = java.util.concurrent.ConcurrentHashMap.newKeySet();
	/** 다른 슬롯 레벨 Animator 오브젝트 자세 (이름 → 관절 월드, 숨김 관절) — 렌더 스레드가 otherScene 에 건다. */
	private final Map<String, Object[]> otherPosed = new java.util.concurrent.ConcurrentHashMap<>();
	/** 다른 슬롯 레벨의 스크립트 재질 애니 시간 (소유 GO → 재질 → 시간, {@link devple.gow.engine.nav.Platforms#materialTimes}). */
	private volatile Map<String, Map<String, Float>> otherMaterialTimes = Map.of();
	/** 표시 중인 메시지 번호 (-1 없음): 버튼으로 닫힐 때 이벤트 1040(번호) 를 플레이어가 보낸다 (원작 0x1B8ECC/0x1B8F98/0x1B9170). */
	private int messageId = -1;
	/** 표시 중인 메시지의 쪽 (줄 첫 두 글자 "--" 로 나눔, 쪽마다 1..3 줄 — 0x1B96D0) 과 지금 쪽 (HUD +0x1534), 쪽마다 시간 (+0x153C, 0 이하 = 버튼). */
	private List<List<String>> messagePages = List.of();
	private int messagePage;
	private float messageSeconds;
	/** 카메라 관리자 +0x808 / +0x820: 지금 / 바로 앞 카메라 이름 (Internal 0xD / 0xE) — 전환 0x1EEE04 때만 바뀐다 (엔진 Follow 는 원작에 없어 넣지 않음). */
	private String camNow = "", camPrev = "";
	/** HUD 모래시계 마지막 값 (원작 HUD +0x38 Timer_Value 경과 %, +0x3C Timer_Seconds, +0x15A0 보임) — 바뀔 때만 보낸다 (0x1A830C – 0x1A8340, 0x1A83A4 – 0x1A83D4). */
	private int timerPercent, timerSecs, timerVisible;
	/** 실패 화면 (Internal 0x13 FailedScreen → HUD FailedMenu, 0x21D808) 이 열려 있음. */
	private boolean failedMenu;
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
	/** 다른 슬롯 레벨에만 있는 적 리소스 ({@link #loadNeighbourKinds}, null = 전투 개체 아님). 이웃 스크립트 SpawnEnemy 전용. */
	private final Map<String, EnemyFactory.Kind> neighbourKinds = new HashMap<>();
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
	/** 자동 플레이: 시선 보유 시 봇 대상 (영웅 combatant 대상 = 광선 조준), 아니면 null. */
	private EnemyFighter botAim;
	/** 자동 플레이 시선: 지켜보는 대상, 그 동결 게이지, 광선이 켜졌는데 게이지가 안 줄어든 시간 (초). */
	private EnemyFighter gazeWatch;
	private float gazeMeter, gazeStall;

	/** p 가 영웅 정면 (fx, fz) 기준 cos ≥ minCos 안. */
	private boolean inFront(Vector3f p, float fx, float fz, float minCos) {
		float dx = p.x - hero.ctl.position.x, dz = p.z - hero.ctl.position.z, l = (float) Math.hypot(dx, dz);
		return l < 1e-3f || (dx * fx + dz * fz) / l >= minCos;
	}
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
	/** Creator 가 만든 R2 상자의 모델·충돌 (38 §21). */
	private final ChestProps chests = new ChestProps();

	public GameScene(GameSession session, SaveGame save) {
		this(session, save.level, null, 0);
		this.loadPos = save.pos;
		this.loadYaw = save.yaw;
	}

	/** 레벨을 새로 읽고 (pos, yaw) 에 둔다 — 실패 화면 다시 하기 ({@link #restartFailed}). */
	static GameScene at(GameSession session, String levelName, float[] pos, float yaw) {
		GameScene s = new GameScene(session, levelName, (String) null, 0);
		s.loadPos = pos;
		s.loadYaw = yaw;
		return s;
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
		java.nio.file.Path wad = Stages.wad(session.rom, this.levelName);
		java.nio.file.Path rom = session.rom;
		levelPrep = devple.gow.engine.asset.Loader.submit("level " + this.levelName, () -> {
			LevelScene s = new LevelScene(wad, -2, false).prepare();
			for (String name : EnemyFactory.resourceWads(s.data().wad)) {
				WadAssets.prefetch(Content.file(rom, name + ".WAD"), devple.gow.tools.fx.EffectLibrary::preparse); // + wireEffects 의 이펙트 GO 표
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
		WadAssets.prefetch(Content.file(session.rom, Region.of(session.rom).perm())); // HUD
		WadAssets.prefetch(Content.file(session.rom, Region.of(session.rom).perm()), devple.gow.tools.fx.EffectLibrary::preparse); // MagicFx 이펙트 GO (EffectSystem)
		int costume = session.player != null ? session.player.costume : session.costume;
		WadAssets.prefetch(Content.file(session.rom, "R_HERO" + costume + ".WAD"));
		if (session.player != null) { // 블레이드 모델 (HeroRig → BladeModels)
			WadAssets.prefetch(Content.file(session.rom, "R_WPN" + costume + "_" + Math.max(0, Math.min(5, session.player.level.getOrDefault(Weapon.BLADES, 1))) + ".WAD"));
		}
	}

	/** 블레이드 god 판 (0x1FA9A0, 45 문서 §2): 영웅 코스튬·블레이드 레벨의 R_WPN 이펙트 GO. */
	private void setGodBlades(devple.gow.engine.fx.MagicFx fx, HeroRig h) {
		fx.setGodBlades(Content.file(session.rom, "R_WPN" + h.player.costume + "_" + Math.max(0, Math.min(5, h.bladeLevel())) + ".WAD"),
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
	/** 페이드 동안 화면 밖 첫 그리기 (48 §9.7). -Dgow.warm=false 로 끔 (비교용). */
	private static final boolean WARM = !"false".equals(System.getProperty("gow.warm"));
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
				preHud = new UiLayer(new WadAssets(Content.file(session.rom, Region.of(session.rom).perm())), true, Region.of(session.rom).flpHud(), session.messages, preVars);
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
					var path = Content.file(session.rom, name + ".WAD");
					if (!preKinds.containsKey(name) && Files.exists(path)) {
						try {
							preKinds.put(name, EnemyFactory.load(path, session.permk, -1));
						} catch (RuntimeException ex) {
							preKinds.put(name, ex);
						}
					}
				}
				preStep = WARM ? preStep + 1 : -1;
				return "enemy kinds";
			}
			case 8 -> {
				// 48 §9.7: 1×1 화면 밖 FBO 에 새 레벨 배치를 한 번씩 그려 드라이버 첫 그리기를 페이드 안으로 (원작에 없는 엔진 전용)
				if (preLevel.warmGpu(e, deadline)) {
					preStep++;
				}
				return "warm level";
			}
			case 9 -> {
				// HUD 파트 메시·텍스처 (R_PERMK 해독·GL) 미리 — 첫 hud.render 가 만들던 것
				var w = e.renderer().warmup();
				w.begin();
				boolean done;
				try {
					done = preHud.warm(e, deadline) && (preLevelHud == null || preLevelHud.warm(e, deadline));
				} finally {
					w.end();
				}
				if (done) {
					preStep = -1;
				}
				return "warm hud";
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
					: new LevelScene(Stages.wad(session.rom, levelName), -2, false);
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
			hud = preHud != null ? preHud : new UiLayer(new WadAssets(Content.file(session.rom, Region.of(session.rom).perm())), true, Region.of(session.rom).flpHud(), session.messages, vars);
			levelHud = preLevelHudDone ? preLevelHud : loadLevelHud(vars);
			ph = devple.gow.engine.core.PerfMonitor.phase(". hud", ph);
			session.sound.load(level.data().wad);
			ph = devple.gow.engine.core.PerfMonitor.phase(". sound", ph);
			hero.combat.onSound((h, p) -> session.sound.play(h, p));
			// 첫 안내: 영웅 기술의 SCRIPT SCR_FirstTimeMsg (0x19CD20, idx = 안내 번호 — MAIMEWGRAB 1, MAIMIN01 7, BOLTPULLOUT 9 …)
			hero.combat.addEventListener(ev -> {
				if (ev.type() == 8 && ev.hash() == SCR_FIRST_TIME_MSG) {
					firstTimeHint(ev.index());
				}
			});
			hero.player.onHint = id -> {
				if (id == 18) {
					session.sound.play("SND_LEVELUPCARD"); // 0x1DBE88 (비트 18 이 없을 때만)
				}
				firstTimeHint(id);
			};
			vars.addListener((name, value) -> {
				if (System.getProperty("gow.debug.hudvars") != null && !name.startsWith("PS2_Sound")) {
					System.out.println("HUD 변수 " + name + " = " + value);
				}
				if (name.regionMatches(true, 0, "PS2_Sound", 0, 9) && value != null && value.startsWith("SND_")) {
					session.sound.play(value);
				}
				if (powerUp && name.equals("PS2_PSMDone") && "1".equals(String.valueOf(value).replace(".0", ""))) {
					onPowerUpClosed();
				} else if (questionId >= 0 && name.equals("PS2_Done") && "1".equals(String.valueOf(value).replace(".0", ""))) {
					answerQuestion(vars.get("PS2_Menu_Choice"));
				} else if (pickupOpen && name.equals("PS2_Done") && "1".equals(String.valueOf(value).replace(".0", ""))) {
					closePickupCard(); // FLP ClosePickUpMenu (○, Pause_Check 8 → 0)
				} else if (deadMenu == 2 && name.equals("PS2_Done") && "1".equals(String.valueOf(value).replace(".0", ""))) {
					onDeadMenuClosed(vars.get("PS2_Menu_Choice"));
				} else if (failedMenu && name.equals("PS2_Done") && "1".equals(String.valueOf(value).replace(".0", ""))) {
					onFailedMenuClosed(vars.get("PS2_Menu_Choice")); // FLP CloseFailedMenu (Menu_Choice 0 다시 / 1 나가기 → Done 1)
				} else if (saveAsk && name.equals("PS2_Done") && "1".equals(String.valueOf(value).replace(".0", ""))) {
					onSaveAnswered("1".equals(String.valueOf(vars.get("PS2_Menu_Choice")).replace(".0", "")));
				} else if (paused && !powerUp && !saveAsk && saveFlow == null && saveCloseTimer <= 0 && deadMenu == 0 && !failedMenu
						&& (name.equals("PS2_PauseDone") || name.endsWith("Menu_Choice"))) {
					onMenuClosed(name, value);
				}
			});
			hud.player().addCommandListener((cmd, arg) -> {
				if (saveFlow != null) {
					saveFlow.command(cmd, arg);
				}
			});
			chests.onItem = this::chestItem;
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
		if (System.getProperty("gow.debug.combat") != null) { // 전투 기록 (기술·적중) — 자동 플레이 QA
			hero.combat.onLog(x -> System.out.printf("[영웅 %.2f] %s%n", time, x));
		}
		grab.setPullers(() -> enemies.stream().map(Enemy::fighter).toList()); // 결합 없는 끌기 = 공격 대상이 영웅인 적 (0x2097A8)
		if (System.getProperty("gow.giveAll") != null) {
			for (int id = 3; id <= 7; id++) {
				hero.player.addItem(id, 1);
			}
			hero.player.level.put(Weapon.BLADES, 2);
			hero.player.mp = hero.player.maxMp();
			hero.combat.godMeter = 100; // 신들의 분노 게이지 가득 (L3+R3)
		}
		if (System.getProperty("gow.redOrbs") != null) { // [엔진 전용] 업그레이드 메뉴 검증: 붉은 오브 지급 (43 §17)
			hero.player.addRedOrbs(Integer.getInteger("gow.redOrbs", 0));
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
		bindPresentation(hero.combat);
		magicFx.concussionDamage = (owner, c, r, dmg) -> {
			// 범위 피해 (0x191720): 영웅 것은 반경 안의 적에게, 적 것은 영웅에게 — 템플릿 피해 × 주인 배율 (난이도 포함)
			if (owner == null || owner == hero.combat) {
				float v = dmg * hero.combat.damageScale();
				for (Enemy en : enemies) {
					EnemyFighter f = en.fighter();
					if (!f.dead() && !f.invulnerable() && f.pos.distance(c) < r + f.bodyRadius()) {
						f.selfHp(v);
					}
				}
			} else if (hero.player.hp > 0 && hero.ctl.position.distance(c) < r + 12f && !hero.combat.invulnerable()) {
				hero.player.hp = Math.max(0, hero.player.hp - dmg * owner.damageScale());
			}
		};
		session.scriptHost.difficulty = () -> session.difficulty; // 스크립트 Internal 48 DifficultyLevel
		session.scriptHost.players = () -> hero != null ? hero.player : session.player; // 스크립트 Internal 33..45 HP·MP·아이템 (메두사의 시선 획득 = Internal:39)
		magicFx.bindHero(hero.combat, hero::jointWorld);
		if (!preGodBlades) {
			setGodBlades(magicFx, hero);
		}
		hero.hideBlades = magicFx::godBladesOn;
		hero.onBladesSwapped = () -> { // 업그레이드로 무기 WAD 교체 (Upgrade_LoadWad 0x1D7890, 43 §17)
			setGodBlades(magicFx, hero); // 새 R_WPN 의 goMAIblade / goMAIgodblade 가 뒤에 등록돼 이긴다
			magicFx.reattachBlades();
		};
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

			public float blood(int side, int kind) {
				return hero.bladeBlood(side, kind);
			}
		});
		ph = devple.gow.engine.core.PerfMonitor.phase(". lights", ph);
		wireEffects();
		startSkyEffects();
		ph = devple.gow.engine.core.PerfMonitor.phase(". wireEffects", ph);
		hudBinding = newHudBinding();
		boolean seamless = arrival != null && (arrival.kind() == LevelLink.Kind.SEAMLESS || arrival.kind() == LevelLink.Kind.WALK);
		link = new LevelLink(levelName, seamless ? arrival.from() : null, w -> Stages.exists(session.rom, w));
		if (link.other() != null) {
			loadOther(link.other());
		}
		ph = devple.gow.engine.core.PerfMonitor.phase(". other", ph);
		cameraRig = new devple.gow.engine.level.CameraRig(level.data());
		cameraRig.setOther(otherData); // 다른 슬롯 레벨의 CMV·카메라도 질의 (원작 두 슬롯 상주)
		// 카메라 전환 → 전역 이벤트 1037 (인자0 = 카메라 이름, 원작 0x1EEE88)
		cameraRig.onSwitch(n -> {
			camPrev = camNow; // 0x1EEE04: +0x820 ← +0x808, +0x808 ← 새 이름 (Internal 0xE / 0xD)
			camNow = n;
			if (world != null) {
				world.postEvent(1037, n, 0, 0, null);
			}
		});
		ph = devple.gow.engine.core.PerfMonitor.phase(". cameraRig", ph);
		// 두 슬롯 세계 넘겨 쓰기 (GameSession.residentWorlds, 43 §18): 이음 (LoadCheck·걷기) 이면 들어가는 레벨과 떠나는 레벨이 둘 다 슬롯에 그대로라
		// 그 스크립트 세계 (LevelData·한 번 발동·지연·액터) 를 잇는다. Warp·Load 는 슬롯이 바뀌므로 새로.
		if (!seamless) {
			session.residentWorlds.clear();
		}
		ScriptWorld keptWorld = seamless ? session.residentWorlds.get(levelName.toUpperCase(java.util.Locale.ROOT)) : null;
		ScriptWorld keptOther = seamless && otherName != null ? session.residentWorlds.get(otherName.toUpperCase(java.util.Locale.ROOT)) : null;
		if (keptWorld != null) {
			world = keptWorld;
			world.rebind(listener());
		} else {
			// 넘겨 쓰는 이웃 세계가 세션 호스트를 쓰고 있으면 (떠나온 레벨이 그 호스트로 만든 현재 세계) 새 호스트 — LevelData 가 섞이지 않게
			boolean hostBusy = keptOther != null && keptOther.host() == session.scriptHost;
			world = new ScriptWorld(level.data(), hostBusy ? devple.gow.engine.script.ScriptHost.neighbour(session.scriptHost) : session.scriptHost, listener());
			ScriptWorld.Memory mem = session.enterLevel(levelName); // 레벨 기억 (원작 0x1B38A8 → 0x1B4618, GameSession.levelMemory)
			if (mem != null) {
				world.restore(mem);
				System.out.println("레벨 기억으로 되살림: " + levelName + " (LevelData " + mem.levelData().size() + ", Creator " + mem.created().size() + ")");
			}
		}
		nav = new SceneNav(level.data(), col, hero, world, session.scriptHost, orbTweaks, navHost());
		if (AutoPlay.ON) {
			if (session.autoCarry != null) { // 경로 중 이음 전환 (덧붙는 레벨 → 숙주): 같은 경로를 이어서
				auto = session.autoCarry;
				session.autoCarry = null;
			} else {
				auto = new AutoPlay(levelName);
				auto.plan(world, col);
			}
			if (auto != null) {
				auto.scriptPost = (id, a0) -> world.postEvent(id, a0, 0, 0, world.player());
				auto.camera = n -> { // 스크립트처럼 CMV 질의 끔 (Internal 0x9 false) + SwitchCamera, 빈 이름이면 질의 다시 켬
					cameraRig.setVolumeQuery(n.isEmpty());
					if (!n.isEmpty()) {
						cameraRig.request(n);
					}
				};
			}
		}
		buildRopeVisuals();
		ph = devple.gow.engine.core.PerfMonitor.phase(". script+nav", ph);
		if (otherName == null && keptWorld != null) {
			world.linkEvents(null); // 넘겨 쓴 세계의 예전 이벤트 짝 (버린 슬롯) 을 끊는다
		}
		if (otherName != null) {
			nav.setOther(otherName, otherData, otherCol, nav.neighbourListener(), keptOther, neighbourTweaks(otherData));
			otherBreakables = otherData != null ? new Breakables(otherData, neighbourTweaks(otherData)) : null;
			Resident left = resident(arrival, otherName);
			if (left != null) { // 이음: 떠나온 레벨 = 다른 슬롯 그대로 (생성 스크립트 결과보다 넘긴 상태가 우선)
				otherHidden.clear();
				otherHidden.addAll(left.hidden());
				nav.restoreNeighbourPlatforms(left.anims()); // + 이웃 R1/R2 오브젝트 (열린 해치·창살은 끝난 것으로)
				if (System.getProperty("gow.debug") != null) {
					System.out.println("다른 슬롯 상태 넘겨받음 " + otherName + ": 숨김 " + left.hidden().size() + ", 애니 " + left.anims());
				}
			}
		}
		String arrivalCam = placeSpawn();
		respawn(spawn);
		boolean warped = arrival != null && arrival.kind() == LevelLink.Kind.WARP;
		if (arrival == null || warped) {
			session.worldStart = session.playTime; // 레벨 적재 = 세계 초기화 (0x1A6EC0: 0x29D924 = 0), 걷기 이음은 이어진다
		}
		if (warped) {
			// Warp_Jump → 영웅 SetMatrix 0x1E79D0: 줄이면 놓기 0x1C4FF8(1) → 떨어짐 (+0x174 0x8), 다음 프레임 공중 줄 잡기 0x1C5C20 이
			// 도착점 줄을 잡는다 — 잡기 대기 없음 (ATRET01A WarpFrmhdes05 = 무덤 줄 gorope1 한가운데, 43 §13.9)
			nav.navigator.warpArrived();
		}
		ph = devple.gow.engine.core.PerfMonitor.phase(". spawn", ph);
		checkpoint.set(spawn);
		checkpointYaw = spawnYaw;
		cam.fovDeg = 60;
		cam.near = devple.gow.engine.fx.FxEnvironment.viewNear; // 원작 뷰포트 near (+0x384 = −10, 0x168DC8 — 43 §11)
		cam.far = 30000;
		bridge = new devple.gow.engine.boss.CombatScriptBridge(world);
		if (boss != null) {
			boss.attach(arena());
		}
		world.start(); // 넘겨 쓴 세계면 이미 돌았으므로 그대로 (ScriptWorld.start 는 한 번)
		if (keptWorld != null) {
			world.recreate(); // 상자·세이브 포인트·살아 있는 적을 이 씬에 다시
		}
		if (keptOther != null && nav.neighbour() == keptOther) {
			keptOther.recreate(); // 이웃 리스너: 적 (이웃 스폰 규칙) · 세이브 포인트·상자 Creator (연 상자는 연 채) · Vis 상태
		}
		if (keptWorld == null) {
			applyArriveState(world, levelName);
		}
		if (keptOther == null && nav.neighbour() != null) {
			applyArriveState(nav.neighbour(), otherName);
		}
		rememberResidentWorlds();
		ph = devple.gow.engine.core.PerfMonitor.phase(". script start", ph);
		// Warp 도 이미 서 있는 볼륨은 "안" 으로: 원작 영웅 볼륨 판정은 이전 위치 → 지금 위치 선분 (0x1A7FFC, 콜백 0x1A8508) 인데 순간이동
		// (0x1E0A18 → 0x1B3030 → 0x29D90C) 프레임은 선분을 건너뛰고 이전 위치 = 도착점 — 도착점을 덮는 Warp 볼륨이 곧바로 되돌리지 않는다 (43 §13.9)
		if (keptWorld == null) { // 넘겨 쓴 세계는 영웅 볼륨 기록이 이어진다 (다음 틱의 들어감·나감은 그대로 발동)
			Vector3f cp = hero.contactPointAt(spawn, new Vector3f()); // 접촉점 = pelvis (첫 틱 전이라 바인드 높이)
			world.placePlayer(cp.x, cp.y, cp.z, seamless || warped);
		}
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
		if (session.seamZone >= 0) { // 이음 직전 구역 제목을 새 HUD 에 (원작 HUD 는 하나 — 43 §19)
			int z = session.seamZone;
			session.seamZone = -1;
			zoneReport(z);
		}
		devple.gow.engine.core.PerfMonitor.loadTime("enter " + levelName, enterT0);
	}

	/** 레벨이 쓰는 적 리소스: RSRCS 태그(0x1F4)의 24바이트 이름들 → R_&lt;이름&gt;.WAD (11 문서 §6.3 Slots 힙). */
	private void loadEnemyKinds() throws IOException {
		for (String name : EnemyFactory.resourceWads(level.data().wad)) {
			var path = Content.file(session.rom, name + ".WAD");
			if (!kinds.containsKey(name) && Files.exists(path)) {
				try {
					Object pre = preKinds.remove(name);
					if (pre instanceof RuntimeException ex) {
						throw ex; // 페이드 동안 해 본 결과 그대로 (소리 전용 WAD)
					}
					EnemyFactory.Kind k = pre != null ? (EnemyFactory.Kind) pre : EnemyFactory.load(path, session.permk, -1);
					kinds.put(name, k);
					// 레벨 WAD 의 적용 외부 애니 그룹 (ANM_SKS_Athn01A — 523 SKSshipDismount 난간 넘어 오르기, hatchSpawn; ANM_SKC_Athn02B):
					// 적 ANM 의 외부 그룹 (예 R_SKS 그룹 6 "Athn01A", act 0) 이 레벨에서 채워진다. 영웅 ANM_hero_Athn01A 와 같은 구조 (05 §2.2)
					k.anims().attachAll(level.data().wad);
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

	/**
	 * 다른 슬롯 레벨의 적 리소스 (43 문서 §17): 원작 Tag_Resources 0x1BB928 은 RSRCS 이름을 <b>읽는 슬롯</b> (0x3372D8) 의 구조 +0xA + 10·i 에
	 * "R_%s" 로 둔다 (최대 4 개, 슬롯을 비우면 0x1BC028 이 지움) — 리소스는 슬롯마다라 이웃 레벨이 올라와 있으면 그 적 종류도 상주한다 [확실: 코드].
	 * 그래서 이웃 스크립트의 SpawnEnemy 가 현재 레벨에 없는 종류도 낸다: ATHN03A 거리에 놓인 ATHN03C RoadSpawner2 → RoadEnemies2 고르곤
	 * (템플릿 60 = R_MEW, ATHN03A RSRCS 는 R_SKA·R_MI2 뿐) — 영상 5130–5172 의 고르곤. 현재 레벨 종류 표 ({@link #kinds}) 와 따로 둔다
	 * (보스·특수 템플릿 대역 고르기가 바뀌지 않게).
	 */
	private void loadNeighbourKinds(LevelData other) {
		if (other == null) {
			return;
		}
		for (String name : EnemyFactory.resourceWads(other.wad)) {
			var path = Content.file(session.rom, name + ".WAD");
			if (kinds.containsKey(name) || neighbourKinds.containsKey(name) || !Files.exists(path)) {
				continue;
			}
			try {
				EnemyFactory.Kind k = EnemyFactory.load(path, session.permk, -1);
				k.anims().attachAll(other.wad); // 그 레벨의 적용 외부 애니 그룹 (loadEnemyKinds 와 같음)
				orbTweaks.load(k.assets().wad());
				session.sound.load(k.assets().wad());
				hero.attachExternalAnims(k.assets().wad()); // 잡기·처형 영웅 애니 (MAIMEW …)
				neighbourKinds.put(name, k);
				System.out.println("이웃 슬롯 적 리소스: " + name + " (" + other.wad.name() + ")");
			} catch (IOException | RuntimeException ex) {
				neighbourKinds.put(name, null); // 전투 개체가 아닌 리소스 (사운드 뱅크 로더) — 이웃 스폰은 하지 않는다
			}
		}
	}

	/** 레벨 WAD 의 FLP 오버레이 (예 FLP_Athn01E 히드라 체력 미터). 없으면 null. */
	private UiLayer loadLevelHud(UiVariables vars) {
		for (devple.gow.tools.wad.WadTag t : level.data().wad.tags()) {
			if (t.hasData() && t.name().startsWith("FLP_") && t.magic() == devple.gow.tools.ui.FlpFile.MAGIC) {
				try {
					UiLayer u = new UiLayer(new WadAssets(Stages.wad(session.rom, levelName)), true, t.name(), session.messages, vars);
					u.acceptInput = false;
					// HUD 변수 "WadName" (원작 0x1B8668 → 0x1B8704: 불러온 레벨 이름) — FLP_Athn01B/01C 의 SimKeyEvent 가 PS2_WadName 이
					// "Athn01B" 일 때만 미터 1 (HydraMeter1), 아니면 미터 2 를 갱신한다 (37 문서 §4.3). 이름 대소문자 = FLP 이름 그대로 [추정]
					vars.set("PS2_WadName", t.name().substring(4));
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
		boss.autoMinigames = AutoPlay.ON; // 자동 플레이: 싸움은 봇, 미니게임 버튼만 자동
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
		java.nio.file.Path p = Stages.wad(session.rom, wad);
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

	/** LoadForWarp 로 다른 슬롯에 꺼진 채 올라간 레벨 (Warp 대상, 스크립트·충돌·CMV·렌더 없음). null = 없음. */
	private String warpSlot;

	/**
	 * 검증 하네스 -Dgow.arrive.state=레벨:상태;레벨:상태 (엔진 전용, {@link ScriptWorld#presetState}) — --arrive 로 구간 중간에서 시작할 때 앞 구간이
	 * 남긴 스크립트 상태 (예 ATHN04A 사이클롭스 결투 끝) 를 새로 만든 세계에 둔다. 넘겨 쓴 세계에는 걸지 않는다.
	 */
	private static void applyArriveState(ScriptWorld w, String level) {
		String s = System.getProperty("gow.arrive.state");
		if (s == null || w == null || level == null) {
			return;
		}
		for (String part : s.split(";")) {
			int c = part.indexOf(':');
			if (c > 0 && part.substring(0, c).trim().equalsIgnoreCase(level)) {
				w.presetState(part.substring(c + 1));
				System.out.println("도착 상태 (" + level + "): " + part.substring(c + 1));
			}
		}
	}

	/** 지금 두 슬롯의 스크립트 세계 (다음 이음 씬이 넘겨 쓴다, GameSession.residentWorlds). */
	private void rememberResidentWorlds() {
		session.residentWorlds.clear();
		if (world != null) {
			session.residentWorlds.put(levelName.toUpperCase(java.util.Locale.ROOT), world);
		}
		if (nav != null && nav.neighbour() != null && otherName != null) {
			session.residentWorlds.put(otherName.toUpperCase(java.util.Locale.ROOT), nav.neighbour());
		}
	}

	/** 다른 슬롯 비우기 — 그 슬롯에 꺼진 레벨 (LoadForWarp) 이 들어온다. 렌더 장면은 캐시로 (같은 레벨을 다시 LoadWad 하면 재사용). */
	private void unloadOther() {
		if (otherName == null) {
			return;
		}
		if (lights != null) {
			lights.removeSource(otherName);
		}
		otherName = null;
		otherCol = null;
		otherData = null;
		otherBreakables = null;
		otherHidden.clear();
		otherPosed.clear();
		otherScenePending = true;
		if (nav != null) {
			nav.clearOther();
			rememberResidentWorlds();
		}
		if (cameraRig != null) {
			cameraRig.setOther(null);
		}
	}

	/** 다른 슬롯 레벨의 충돌만 읽어 둔다 (렌더·스크립트 없음). 원작은 백그라운드 로드, 여기서는 즉시 [근사]. */
	private void loadOther(String wad) {
		if (wad.equals(otherName)) {
			return;
		}
		warpSlot = null;
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
				otherData = new LevelData(devple.gow.tools.wad.WadFile.load(Stages.wad(session.rom, wad)));
				otherCol = new LevelCollision(otherData.sheets);
			}
			if (lights != null && System.getProperty("gow.noneighbourlights") == null) { // 다른 슬롯 레벨의 LGT 도 같은 서버에 (원작 두 슬롯 상주, 0x151F08)
				System.out.printf("라이트: 다른 슬롯 %s %d%n", wad, lights.addAll(otherData.wad, wad).size());
			}
		} catch (IOException | RuntimeException ex) {
			System.out.println("이웃 레벨 충돌 로드 실패 " + wad + ": " + ex.getMessage());
		}
		loadNeighbourKinds(otherData); // 이웃 스크립트가 처음부터 (SpawnAtStart) 낼 수 있으니 스크립트 세계보다 먼저
		if (nav != null) {
			nav.setOther(wad, otherData, otherCol, nav.neighbourListener(), null, neighbourTweaks(otherData));
			rememberResidentWorlds(); // 다른 슬롯이 새 레벨로 — 예전 슬롯 세계는 버린다 (0x1BC110)
		}
		otherBreakables = otherData != null ? new Breakables(otherData, neighbourTweaks(otherData)) : null;
		if (cameraRig != null) {
			cameraRig.setOther(otherData);
		}
		devple.gow.engine.core.PerfMonitor.loadTime("other " + wad, t0);
	}

	/** 다른 슬롯 레벨 TWK (영구 + 레벨) — 이웃 R1/R2 오브젝트 템플릿 (SceneNav.setOther). */
	private Tweaks neighbourTweaks(LevelData d) {
		if (d == null) {
			return null;
		}
		Tweaks t = new Tweaks();
		t.load(session.permk);
		t.load(d.wad);
		return t;
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
			public void neighbourSpawn(ScriptListener.SpawnRequest r) {
				if (boss != null) {
					System.out.printf("이웃 스폰 생략: %s (보스 레벨)%n", r.source().name()); // 보스 레벨 쫄은 BossFight 가 맡는다
					return;
				}
				spawn(r, true);
			}

			@Override
			public void neighbourFade(float r, float g, float b, float seconds, boolean out) {
				if (out) {
					screenFade.fadeOut(r, g, b, seconds);
				} else {
					screenFade.fadeIn(seconds);
				}
			}

			@Override
			public void neighbourIdle(boolean idle) {
				scriptIdle = idle; // 원작 전역 정지 0x29DB58 은 하나 (현재 레벨 리스너의 0xC 와 같음)
				devple.gow.engine.combat.AiTargets.globalHold = globalIdle();
			}

			@Override
			public void neighbourZoneReport(int zone) {
				zoneReport(zone); // 원작 GameMgr 구역 제목은 슬롯 구분 없음 (0x1B0A28, 43 §19)
			}

			@Override
			public String cameraName(boolean previous) {
				return previous ? camPrev : camNow;
			}

			@Override
			public void failedScreen() {
				GameScene.this.failedScreen();
			}

			@Override
			public void neighbourCreate(ScriptEntity source, Object name) {
				// 이웃 레벨 Creator "savepoint" (원작 두 슬롯 GO 상주): 자리·빛기둥을 이 씬에 — R2 저장 (0x1DC678 은 슬롯 구분 없이 가장 가까운 것).
				if (SceneNav.creatorIs(name, "savepoint") && source.owner != null) {
					float[] m = SceneNav.creatorMatrix(source); // 엔티티 행렬 (0x1A9CD0), 43 §19
					savePoints.add(new Vector3f(m[12], m[13], m[14]));
					boolean body = spawnSavePoint(m);
					System.out.printf("세이브 포인트 생성 (이웃 %s, %.0f, %.0f, %.0f)%s%n", otherName, m[12], m[13], m[14], body ? " + 빛기둥 savepoint" : "");
					return;
				}
				// 이웃 상자 ("chest"): 이웃 R2 오브젝트 + 몸 (충돌은 이웃 몸 묶음, CSM 은 이웃 TWK) — ATHN05C 항아리 방의 ATHN05D 깃털 상자 (43 §21)
				devple.gow.engine.nav.Interactables.Item chest = nav != null ? nav.createNeighbour(source) : null;
				if (chest != null && magicFx != null) {
					chests.spawn(chest, SceneNav.creatorMatrix(source), magicFx.effects(), nav.neighbourPlatforms(), nav.neighbourTweaks());
				}
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
				emitOrbs(orbTweaks.findTemplate("/TweakTemplates/OrbEmitter/", emitter), at);
			}

			@Override
			public ScriptWorld.Memory levelMemory(String level) {
				return session.enterLevel(level);
			}

			@Override
			public void levelLeft(String level, ScriptWorld w) {
				session.rememberLevel(level, w);
				if (nav != null) { // 내려가는 슬롯의 상자 몸 (이웃 R2 목록은 SceneNav 가 비운다)
					java.util.List<devple.gow.engine.nav.Interactables.Item> gone = nav.neighbourItems().items();
					chests.remove(p -> gone.contains(p.item));
				}
			}
		};
	}

	/** 떨어진 오브 (원작 Orb_Update 0x226710) — 날아가 떠 있다가 빨려 들어와 도착할 때 합산. */
	private final OrbPickups orbPickups = new OrbPickups(new java.util.Random(7));

	private final OrbPickups.Sink orbSink = new OrbPickups.Sink() {
		@Override
		public void credit(int type, float points) {
			hero.player.pickupOrb(type, points);
		}

		@Override
		public void sound(String name, Vector3f at) {
			session.sound.play(name);
		}

		@Override
		public devple.gow.engine.fx.EffectSystem.Effect show(String go, devple.gow.engine.fx.EffectSystem.Anchor anchor) {
			return magicFx != null ? magicFx.effects().play(go, anchor) : null;
		}
	};

	/** OrbEmitter 방출 (0x2258D8). @return 만든 오브 수 */
	private int emitOrbs(String emitterNode, Vector3f at) {
		List<OrbDrops.Orb> orbs = OrbDrops.rollNode(orbTweaks, emitterNode, rnd, hero.player.hp, hero.player.mp, hero.player.mpLevel > 0);
		orbPickups.emit(orbTweaks, emitterNode, orbs, new Vector3f(at), orbSink);
		return orbs.size();
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
			public String cameraName(boolean previous) {
				return previous ? camPrev : camNow; // Internal 0xE / 0xD
			}

			@Override
			public void fade(float r, float g, float b, float seconds, boolean out) {
				if (out) {
					screenFade.fadeOut(r, g, b, seconds); // Entity 0x5 (0x1AFA20)
				} else {
					screenFade.fadeIn(seconds); // Entity 0x6 (0x1AFAD4)
				}
				if (System.getProperty("gow.debug.script") != null) {
					System.out.printf(java.util.Locale.ROOT, "화면 페이드 %s %.2f초 (%.2f, %.2f, %.2f)%n", out ? "어둡게" : "밝게", seconds, r, g, b);
				}
			}

			@Override
			public void animate(ScriptEntity source, List<String> objects, String anim) {
				if (!createdObjects.isEmpty()) {
					List<String> rest = new ArrayList<>();
					for (String o : objects) {
						if (!animateCreated(source, o, anim)) { // Creator 오브젝트 (43 §19)
							rest.add(o);
						}
					}
					objects = rest;
				}
				if (nav != null) {
					nav.animate(source, objects, anim);
					startAnimatorEffects(objects); // 같은 GO 의 채널 이미터 (불덩이·폭발 연기, 43 §16)
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
					nav.setSolid(objects, values[3] == 1); // Attr11_0: 충돌 on/off ("collisionOFF", "DoorCollOff") [추정: 데이터 용례] + R2 잡기 대상
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
				if (func == 0x13) { // FailedScreen (0x1B0B58 → 0x29D958 → 0x21D808)
					failedScreen();
				}
				if (func == devple.gow.engine.script.GameScriptHost.ZONE_REPORT && !args.isEmpty()) {
					zoneReport((int) num(args.get(0)));
				}
				if (func == 0xC && !args.isEmpty()) {
					// Internal 0xC Idle(bool): 참이면 조작 잠금 (영웅은 대기) — ATHN01A PlayerStartZone 시작 연출 13 초 (IdleCam → Idle2Cam → VisIdleOff),
					// ATHN01F 진입·Warp 페이드
					Object a = args.get(0);
					scriptIdle = a instanceof Number n ? n.floatValue() != 0 : !(a instanceof Boolean b) || b;
					devple.gow.engine.combat.AiTargets.globalHold = globalIdle(); // 0x1BF080 → 전역 정지 0x29DB58
				}
				if (func == 0x9 && !args.isEmpty()) {
					Object a = args.get(0);
					cameraRig.setVolumeQuery(a instanceof Number n ? n.floatValue() != 0 : !(a instanceof Boolean b) || b); // Internal 0x9 CMV 질의 on/off
				}
			}

			@Override
			public void checkpoint(ScriptEntity source) {
				if (!acceptCheckpoint(source, hero.player.hp, session.playTime - session.worldStart)) {
					return;
				}
				checkpoint.set(hero.ctl.position);
				checkpointYaw = hero.ctl.yaw;
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
				if (link.other() != null && !link.otherActive()) {
					// LoadForWarp 는 다른 슬롯 레벨을 비우고 새 레벨을 읽되 켜지 않는다 (LevelLink.otherActive): 적재 끝 콜백 0x1B2AA8 이 인자 (슬롯 +0x64)
					// 0 (LoadWad) 이면 0x1BC2E0 (루트 컨텍스트 켬 0x1BEEB8 + 현재 슬롯 0x1BE4C0), 1 (LoadForWarp) 이면 Warp 준비 0x29D914 = 1 만 — 켜는 것은
					// Warp 0x1BC3A0. 꺼진 레벨은 스크립트 루프·충돌·CMV 질의에서 빠진다 (43 §13.9·§13.11). 그래서 ATHN01F heroEntry (CMV 질의 끔 +
					// AniRvlCam, 9.5 초 뒤 켬) 는 Warp 도착에서만 돈다 — 이웃으로 돌리면 되돌아가 ATHN01E 를 다시 LoadWad 할 때 질의가 꺼진 채 남아
					// 떠난 레벨 카메라에 갇혔다 (갑판 그물 검은 화면, 30 §7.4) [확실: 분기]
					if (otherName != null || !link.other().equals(warpSlot)) {
						System.out.println("다른 슬롯 레벨: " + link.other() + " (" + r.op() + ", 꺼진 채)");
					}
					unloadOther();
					warpSlot = link.other();
				} else if (link.other() != null && !link.other().equals(otherName)) {
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
						// LoadCheck: 원작은 "현재 레벨" 표시만 바꾼다 (0x1BCA10: 슬롯이 적재돼 있으면 상태 10 + 현재 레벨 이름 0x29DAE8 복사) — 두 슬롯의
						// 스크립트·GO 는 그대로 살아 있다. 엔진은 현재 레벨 것 (R2 오브젝트·적 생성·Creator) 만 다루므로, 이웃 레벨 바닥이 발밑에 있고 (좌표계가
						// 다른 Warp 짝 레벨에서 허공으로 떨어지지 않도록, 36 문서 §3) 지금 레벨 바닥은 발밑에 없을 때만 넘어간다. 두 레벨이 같은 바닥을
						// 겹쳐 가진 곳 (ATHN02B 가 가진 ATHN02C 발리스타 방 바닥) 의 LoadCheck (ATHN02C LoadCheckB2) 로 넘어가 버리면 ATHN02C 의 회전판 레버·
						// 발리스타 크랭크가 이웃 것이 되어 R2 가 먹지 않았다 (43 문서 §14). 겹친 바닥을 벗어나면 이음 규칙 (다른 슬롯 바닥만) 이 넘긴다
						if (otherCol != null && !Float.isNaN(otherCol.groundHeight(p.x, p.y + 32, p.z, 600))
								&& Float.isNaN(col.groundHeight(p.x, p.y + STEP, p.z, 64))) {
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
				// 1019 EvtForce → 영웅: 인자1 = 기술 ID (ATHN01A PlayerStartZone-EvtForce 512 → M512 maiGameIntro → M80 IDLECOMBAT)
				if (e.event() == ScriptWorld.EVENT_FORCE && e.actor() != null && e.actor() == world.player() && hero != null) {
					if (!hero.combat.request(e.arg1())) {
						hero.combat.forceMove(e.arg1());
					}
					return;
				}
				if (e.event() == EVENT_DIE && e.actor() != null && destroyCreated(e.actor())) {
					return;
				}
				boolean handled = boss != null ? boss.objectEvent(e) : bridge != null && bridge.objectEvent(e);
				if (!handled) {
					objectEventToGo(e);
				}
			}

			@Override
			public void spawnEnemy(SpawnRequest r) {
				spawn(r, false);
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
				// Creator 엔티티: 이름 슬롯 "savepoint" → 세이브 포인트를 엔티티 행렬 (원작 0x1ACCA8 → 0x1A9CD0, 소유 GO 인스턴스 아님) 에 생성 (43 §19)
				Object name = world.slot(source, "Name0");
				if (SceneNav.creatorIs(name, "savepoint") && source.owner != null) {
					float[] m = SceneNav.creatorMatrix(source);
					savePoints.add(new Vector3f(m[12], m[13], m[14]));
					boolean body = spawnSavePoint(m);
					System.out.printf("세이브 포인트 생성 (%.0f, %.0f, %.0f)%s%n", m[12], m[13], m[14], body ? " + 빛기둥 savepoint" : "");
				} else {
					// "chest"/"Chest" → R2 상자 (원작 Creator 0x1ACCA8: R_PERMK 오브젝트 chest 를 엔티티 행렬에 새 GO 로, 38 §21)
					devple.gow.engine.nav.Interactables.Item chest = nav != null ? nav.create(source) : null;
					if (chest != null) {
						if (magicFx != null) {
							chests.spawn(chest, SceneNav.creatorMatrix(source), magicFx.effects(), nav.navWorld.platforms(), orbTweaks);
						}
					} else if (name instanceof String n && !n.isEmpty()) {
						spawnCreated(source, n);
					}
				}
			}
		};
	}

	/** Creator 가 만든 그 밖의 오브젝트 (액터 이름 → 이펙트 GO): Animator (모드 3, 같은 Filter) 가 act 를 튼다. */
	private final Map<String, devple.gow.engine.fx.EffectSystem.Effect> createdObjects = new HashMap<>();

	/**
	 * 상자·세이브 포인트가 아닌 Creator (원작 0x1ACCA8 은 이름을 가리지 않는다): Name0 앞에 "go" 를 붙인 오브젝트 (레벨 WAD 의 원점에 둔 원형 —
	 * ATHN05A goaored · goaosks · goaoskb · goaoxy1, 전 게임 aoRED 41 · aoSKS 27 · aoREDCAGE 17 · aoHAR 13 …) 를 {@link SceneNav#creatorMatrix 엔티티 행렬} 에
	 * 새 GO 로 만들고, 처음 act = Name1 (0x1AD110), 그 오브젝트 그룹 = Creator 의 Filter (슬롯 16, 0x1A5130 인자) 로 생성을 알린다 (CreationSensor)
	 * [확실: 생성 경로]. 같은 Filter 의 Animator (모드 3) 가 그 GO 에 act 를 다시 튼다 — ATHN05A 덩굴 통로·발코니의 "아테네 병사 대 해골병" 싸움 여섯 쌍
	 * (aoRED/aoSKS act "aoRED1a05Hl05"/"aoSKS1a05Hl05" 4.33 초 반복, 지연 0.5–2 초, 영상 6575–6584 의 발코니 병사들) 이 예전엔 하나도 없었다 (43 문서 §19).
	 * 엔진: 이펙트 GO (모델·관절 애니·관절 이미터·SEM) [근사: 충돌 CDV 없음, Animator 속도·역재생 비트는 무시].
	 */
	private void spawnCreated(ScriptEntity source, String name0) {
		if (magicFx == null || source.owner == null) {
			return;
		}
		devple.gow.engine.fx.EffectSystem fx = magicFx.effects();
		devple.gow.tools.fx.EffectLibrary.EffectDef def = fx.library().resolve(name0.startsWith("go") ? name0 : "go" + name0);
		if (def == null || def.model() == null) {
			return;
		}
		float[] m = SceneNav.creatorMatrix(source);
		String key = source.name().toLowerCase(java.util.Locale.ROOT); // Name1 은 묶음마다 같다 (aoRED1a05Hl05) → 엔티티 이름이 키
		int group = (int) num(world.slot(source, "Filter"));
		devple.gow.engine.script.ScriptActor actor = null;
		for (devple.gow.engine.script.ScriptActor a : world.actors()) {
			if (a.name.equals(key) && !a.destroyed()) {
				actor = a; // 넘겨 쓴 세계의 recreate: 액터는 그대로, GO 만 새 씬에
			}
		}
		if (actor == null) {
			actor = world.addActor(key, group, new float[]{m[12], m[13], m[14]});
		}
		devple.gow.engine.fx.EffectSystem.Effect e = fx.play(def.go(),
				devple.gow.engine.fx.EffectSystem.Anchor.of(new org.joml.Matrix4f().set(m)), Float.POSITIVE_INFINITY);
		if (e == null) {
			return;
		}
		e.scale = 1f / Math.max(def.scale(), 1e-3f);
		e.jointEmitters = true;
		// 처음 act = Name1 (0x1AD110 이 오브젝트 애니에서 Name1 해시 (대문자, 0x1758E0) 와 같은 act 번호를 찾아 GO 생성 0x1A5130 에 넘김) [확실]
		Object n1 = world.slot(source, "Name1");
		if (n1 instanceof String s && !s.isEmpty()) {
			e.setAct(s);
		}
		if (actor.act != null) {
			e.setAct(actor.act); // 이음 뒤: 마지막으로 튼 act 를 이어서
			e.loopAct = actor.actLoop;
		}
		createdObjects.put(key, e);
		System.out.printf("Creator 오브젝트 %s → %s (그룹 %d) at (%.0f, %.0f, %.0f)%n", source.name(), def.go(), group, m[12], m[13], m[14]);
	}

	/** 이벤트 1008 (0x3F0) "죽음" — 스크립트 이름 EvtDie · instantDie · KillEnemies · oraclFallDIE. */
	static final int EVENT_DIE = 1008;

	/**
	 * EventTransmitter 의 1008 이 Creator 가 만든 GO 에 가면 그 GO 를 지운다 (원작 0x1AC154 – 0x1AC198: 메시지 0x3F0 이고 대상에 전투 컴포넌트가 없고 핸들러가
	 * Creator GO 의 0x1ADEE0 이면 보내지 않고 0x1AE448 — GO 제거 + 레지스트리 제거 통지 0x1A6010 → DestructionSensor) [확실]. 예 ATHN06C killOrclonWIN
	 * (구출 동영상 뒤 줄에 매달린 오라클 aoXOR1a06hl02 을 지움) · oraclFallDIE (실패: 매단 오라클을 지우고 떨어지는 오라클 aoXOR1a06hl02a 를 새로 만듦).
	 * 예전 엔진은 1008 을 버려 구출 뒤에도 줄에 오라클이 남았다. @return Creator GO 였으면 true
	 */
	private boolean destroyCreated(devple.gow.engine.script.ScriptActor a) {
		devple.gow.engine.fx.EffectSystem.Effect fx = createdObjects.remove(a.name);
		if (fx == null) {
			return false;
		}
		fx.stop();
		world.destroy(a);
		System.out.println("Creator 오브젝트 제거 (1008): " + a.name);
		return true;
	}

	/** Animator 가 Creator 오브젝트를 가리키면 그 GO 에 act (슬롯 1 비트 0x2 = 반복, Platforms.play 와 같은 규칙). @return 처리했으면 true */
	private boolean animateCreated(ScriptEntity source, String object, String anim) {
		devple.gow.engine.fx.EffectSystem.Effect e = createdObjects.get(object);
		if (e == null) {
			return false;
		}
		int[] mode = new int[1];
		float[] speed = new float[1], start = new float[1];
		SceneNav.animatorArgs(world, source, mode, speed, start);
		if (e.setAct(anim)) {
			e.loopAct = (mode[0] & 0x2) != 0;
			for (devple.gow.engine.script.ScriptActor a : world.actors()) {
				if (a.name.equals(object)) {
					a.act = anim;
					a.actLoop = e.loopAct;
				}
			}
		}
		return true;
	}

	/**
	 * 컷신 재생 (33 문서): 씬 스택에 MovieScene 을 올려 게임을 멈추고, 끝나면 음악을 다시 켜고
	 * 스크립트에 종료 이벤트 501(인자 0 = 동영상 이름)을 보낸다 (36 문서: ATHN01D TwinKickDoor-main-movieDone 이 EventArg(0) == "Semo02" 를 검사).
	 */
	private void playMovie(Engine e, String name) {
		if (System.getProperty("gow.skipmovies") != null || devple.gow.tools.video.MovieNames.skipped(name)) {
			// --skip-movies 또는 movies.properties skip (49 문서 §10): 재생 없이 바로 끝 이벤트 (자동 연출·검증용)
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

	/** 씬을 떠난다: 두 슬롯 세계를 레벨 기억에 (이음으로 넘겨 쓰는 세계는 그대로 이어지고, 버려지는 세계는 다시 읽을 때 되살아난다 — GameSession.levelMemory). */
	private void rememberLevels() {
		session.rememberLevel(levelName, world);
		if (nav != null && nav.neighbour() != null && otherName != null) {
			session.rememberLevel(otherName, nav.neighbour());
		}
	}

	private void goToLevel(String wad, String entryName) {
		session.warpLeft = null; // LoadWad: 다른 슬롯이 새 레벨로 바뀐다
		if (leaving) {
			return;
		}
		leaving = true;
		rememberLevels();
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
		if (auto != null && !(auto.routing() && a.kind() != LevelLink.Kind.WARP)) { // 자동 플레이: 다음 레벨이면 성공으로 끝, 아니면 막는다
			if (auto.allow(wad, hero.ctl.position)) {
				leaving = true;
				pendingEngine.window().requestClose();
			}
			return;
		}
		if (auto != null) {
			session.autoCarry = auto; // 경로 중 이음 전환: 새 씬이 이어 받음
		}
		leaving = true;
		rememberLevels();
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
			// 이음 직전에 띄운 구역 제목은 새 씬 HUD 가 이어 띄운다 (원작 HUD 하나, 43 §19)
			session.seamZone = zoneShown >= 0 && time - zoneShownAt < ZONE_TITLE_SECS ? zoneShown : -1;
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
	/** -Dgow.auto: 레벨 단위 자동 플레이 검증 (AutoPlay) [엔진 전용]. */
	private AutoPlay auto;
	/** -Dgow.bot: 시연 녹화용 자동 전투 (BotInput) [엔진 전용]. */
	private final devple.gow.engine.input.BotInput bot = System.getProperty("gow.bot") != null ? new devple.gow.engine.input.BotInput(0) : null;

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
	 *
	 * @param neighbour 다른 슬롯 레벨 스크립트의 SpawnEnemy ({@link SceneNav.Host#neighbourSpawn}) — 현재 레벨 적 종류 또는 그 슬롯의 RSRCS 종류
	 *                  ({@link #loadNeighbourKinds}, 43 §17) 의 템플릿만 (레벨 템플릿·대역 없음), 그 적의 스크립트 이벤트·제거는 이웃 스크립트 세계로 (43 문서 §14.5)
	 */
	private void spawn(ScriptListener.SpawnRequest r, boolean neighbour) {
		int tpl = r.templateId();
		// 보스 리소스 (레벨 WAD 안) 가 먼저 (37 문서)
		if (boss != null && boss.silent(tpl)) {
			return; // 소리 전용 가짜 적 (FakeBHAsnd 등)
		}
		EnemyFactory.Kind bossKind = boss != null && !neighbour ? boss.kindFor(tpl) : null;
		// 레벨 템플릿 종류(키 "레벨:오브젝트")는 레벨 WAD 의 모든 AI1 을 가지므로 1단계에서 뺀다
		EnemyFactory.Kind kind = bossKind != null ? bossKind : kinds.entrySet().stream().filter(e -> !e.getKey().contains(":") && e.getValue() != null
				&& e.getValue().hasTemplate(tpl)).map(Map.Entry::getValue).findFirst().orElse(null);
		if (kind == null && neighbour) { // 다른 슬롯 레벨의 리소스 (loadNeighbourKinds — 슬롯마다 상주, 0x1BB928)
			kind = neighbourKinds.values().stream().filter(k -> k != null && k.hasTemplate(tpl)).findFirst().orElse(null);
		}
		if (kind == null && neighbour) {
			System.out.printf("이웃 스폰 생략: %s 템플릿 %d (올린 적 종류에 없음)%n", r.source().name(), tpl);
			return;
		}
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
		// 스폰 엔티티 방향으로 (원작 0x1AC850 → 0x1A4DB0 이 엔티티 월드 행렬로 생성) — 보스: 히드라 고정 방향·잡기 카메라·동기 정렬 기준 (37 문서 §4),
		// 해골 ClimbSkellys: Z 축이 난간 바깥이라 523 SKSshipDismount 가 배 밖 아래에서 올라와 난간을 넘는다 (28 §7)
		f.yaw = (float) Math.atan2(w[8], w[10]);
		f.setDifficulty(session.difficulty);
		if ((bossKind == null || boss.usesNavWorld()) && f.brain().tree != null && nav != null) {
			f.setNavWorld(devple.gow.engine.combat.EnemyNav.of(nav.navWorld)); // 바닥·중력·벽·줄 이동 (32 문서 §적 자신의 벽·줄 이동)
		}
		f.setCrowd(() -> bodies); // 생물끼리 밀어내기 0x1E2DD8 — 보스도 (Sold +0x1C 비트 1·BMI Think 이면 자기는 밀지 않음)
		f.setFoe(hero.hittable());
		bindPresentation(f.combat());
		EnemyFighter self = f;
		// 다른 적도 맞을 수 있다 (접촉 0x203AF0 은 편을 거르지 않음) — 같은 편 피해는 공격 플래그 0x8 일 때만 (Combatant.bystanderHits)
		f.combat().setBystanders(() -> enemies.stream().map(Enemy::fighter).filter(o -> o != self && !o.dead()).toList(), true);
		if (bossKind == null) { // 보스는 BossFight 가 처리
			EnemyFighter ef = f;
			f.combat().addEventListener(ev -> {
				if (ev.type() == 10) {
					ef.selfHp(ev.b()); // SELF_HP
				} else if (ev.type() == 48) {
					// CONCUSSION (0x20704C → 0x204690, 0x2 비트라 무적 검사 없음): 자기 피해 idx — 해골 머리치기 222 @0.05 idx 400 → 즉사 → 227 MAISKB06 마무리
					ef.selfHp(ev.index());
				} else if (ev.type() == 8 && ev.hash() == SCR_FIRST_TIME_MSG) {
					firstTimeHint(ev.index()); // 0x19CD20: 적 기술의 첫 안내 (R_SKB 방패 막기 M70 SKBBLOCK idx 19 → 8 번째에 519 "마법 방패는 …")
				} else if (ev.type() == 8 && ev.hash() == SCR_EXPLODE_DEATH && ef.hp > 0) {
					// SCR_ExplodeDeath: 사망 (△ 찢기 224 @0.80, 마무리 227 @0.80; 피격 착지의 것은 조건 6 = 이미 죽었을 때만) [근사: 터지는 연출 없음]
					ef.selfHp(ef.hp);
				} else if (ev.type() == 2) {
					// ORB_EMIT (0x206030 → OrbEmitter_Emit 0x2258D8, idx = 적 WAD 의 OrbEmitter 템플릿) — 해골 머리치기 222 → 503 "SKS Decap", MAISKB06 504
					emitOrbs(orbTweaks.findNode(String.format("/TweakTemplates/OrbEmitter/%03d/", ev.index())), ef.pos);
				} else if (ev.type() == 8 && ev.hash() == SCR_ARCHER_SHOOT) {
					// SCR_ArcherShoot (0x18DAA8 → 매 프레임 0x18D500): 겨눈 뒤 b 에서 ArrowEmitter idx 발사 — 궁수 SHOOT (20, 부정확·퍼짐 21–24, 폭발 33)
					archerShoot(ef, ev.index(), ev.b());
				} else if (ev.type() == 1) {
					// TARGET_FX (0x206030 형식 1 → 0x206788): ArrowEmitter idx 를 대상에게 지금 — 궁수 ASHOOT3X 폭발 화살 33 ×3, 퍼짐 35
					fireEnemyArrow(ef, ev.index());
				}
			});
		}
		f.brain().setSightBlocked((eye, to) -> { // 인지 시선 가림 (17 문서 §인지)
			Vector3f d = new Vector3f(to).sub(eye);
			float len = d.length();
			return len > 1f && col.raycast(eye, d, len - 1f) != null;
		});
		f.combat().onSound((h, p) -> session.sound.play(h, p));
		if (magicFx != null) {
			magicFx.addWad(kind.assets()); // 적 WAD 의 이펙트 GO·PlayFX/MFX 템플릿 (40 문서 §9)
			combatCamera().addWad(kind.assets().wad()); // 처형 카메라 act (ANM_camera 외부 그룹 MaiCy2 / MaiMin …)
			magicFx.bind(f.combat(), devple.gow.engine.fx.MagicFx.joints(f), false); // 적 기술 PlayFX·적중 피 (40 문서)
			f.setStoneLook(magicFx.stoneLook()); // 석화 = 석상 GO 로 바꿔 끼움 (Sold +0x20/+0x38, 35 문서 §2.2)
		}
		f.setIncomingScale(hero::incomingScale);
		EnemyFactory.Kind fk = kind;
		f.onShatter(() -> stoneKill(f, fk));
		if (bossKind == null || boss.minionTemplate(r.templateId())) {
			// 스폰 슬롯 7: SPAWN / SKSWALLSPAWN / 도발 (32 문서 §벽 스폰). 보스전 쫄도 (아기 히드라 30/31 BHABABYEMERGE — 갑판을 뚫고 나옴, 37 §4.4)
			f.startSpawnAction(r.spawnAction());
		}
		AnimatedModel em = kind.buildModel(enemyShader);
		em.shadowMode = devple.gow.engine.light.ShadowVolume.MODE_SLAB; // AI 생물 (0x1C13E0 → 0x1EBD78 → 0x1BF680, +0x174 bit 31 없음), 47 문서 §7
		em.shadowKey = r.actor() != null ? r.actor().name : null;
		if (System.getProperty("gow.debug.combat") != null) {
			int ai = kind.ai1();
			f.combat().onLog(x -> System.out.printf("[적 %d %.2f] %s%n", ai, time, x));
			if (System.getProperty("gow.debug.group") != null) {
				f.brain().onLog(x -> System.out.printf("[AI %d %.2f] %s%n", System.identityHashCode(f) % 1000, time, x));
			}
		}
		enemies.add(new Enemy(f, em, r.actor(), kind));
		if (neighbour) {
			if (r.actor() != null) {
				neighbourActors.add(r.actor());
			}
			neighbourBridge().attach(f, r.actor());
		} else if (boss != null) {
			boss.onSpawn(f, r.actor(), r.templateId(), arena());
		} else {
			bridge.attach(f, r.actor());
		}
		System.out.printf("%s스폰: %s (템플릿 %d, 순찰 %s) at (%.0f, %.0f, %.0f)%n", neighbour ? "이웃 적 " : "적 ", kind.assets().wad().name(),
				r.templateId(), r.patrol(), f.pos.x, f.pos.y, f.pos.z);
	}

	/** 이웃 스크립트가 만든 적의 스크립트 액터 (제거·피해 이벤트는 그 세계로). */
	private final java.util.Set<devple.gow.engine.script.ScriptActor> neighbourActors =
			java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
	private devple.gow.engine.boss.CombatScriptBridge neighbourBridge;
	private ScriptWorld neighbourBridgeWorld;

	/** 이웃 스크립트 세계의 전투 다리 (이웃이 바뀌면 새로). 시작 큐 스폰 (SpawnAtStart) 이면 아직 이웃 자리에 오르기 전 세계 ({@link SceneNav#spawningNeighbour}). */
	private devple.gow.engine.boss.CombatScriptBridge neighbourBridge() {
		ScriptWorld nw = nav != null ? nav.spawningNeighbour() : null;
		if (neighbourBridge == null || neighbourBridgeWorld != nw) {
			neighbourBridge = new devple.gow.engine.boss.CombatScriptBridge(nw);
			neighbourBridgeWorld = nw;
		}
		return neighbourBridge;
	}

	/** 적 액터가 속한 스크립트 세계 (이웃이 만든 적이면 그 이웃 세계 — 다른 슬롯이 바뀌었으면 떨어져 나간 그 세계라 무해). */
	private ScriptWorld worldOf(devple.gow.engine.script.ScriptActor a) {
		return neighbourActors.contains(a) && neighbourBridgeWorld != null ? neighbourBridgeWorld : world;
	}

	/** 석상 파괴 보너스 (Bonus 023 "Stone Kill" / 공중 024 "Air Stone Kill": #eed31206 붉은 오브, #2af48b79 메시지). */
	private void stoneKill(EnemyFighter f, EnemyFactory.Kind k) {
		String node = String.format("/TweakTemplates/Bonus/%03d/", hero.ctl.grounded ? 23 : 24);
		var orbs = k.tweaks().rawHash(node, 0xeed31206);
		hero.player.addRedOrbs(orbs.isPresent() ? (int) Float.intBitsToFloat(orbs.getAsInt()) : 15);
		if (!f.statueShown()) {
			magicFx.effect("shatter", f.pos); // 석상이 뜨기 전에 부서짐 → 근사 파편. 석상이면 Brkb 파괴 act 가 조각을 흩뿌린다
		}
		session.sound.play("SND_PICKUPS_ORBS_E");
		if (bridge != null) {
			bridge.stoneKill(f); // 1046 Stone Kill → 스크립트 (0x265E04, ATHN02D EventSmash1)
		}
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
				k = EnemyFactory.load(Stages.wad(session.rom, levelName), session.permk, ai1, obj);
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
		// 0x1B9F34: Done(+0x27C) = 0 [확실] — 앞 메뉴 (세이브 질문 등) 가 남긴 PS2_Done 1 이면 ○ 의 1 이 변화로 들어오지 않아
		// 질문이 닫히지 않았다 (ATHN04A 저장 뒤 해치 열쇠 질문 1007, 43 문서 §18)
		v.set("PS2_Done", 0);
		hideMinigameIcon();
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


	private int hudHits = -1;

	/**
	 * 히트 카운터 HUD (원작 0x1DB608 매 프레임 → Hud_SetHitCounter 0x1BA070): 이벤트 1 = 히트 수 (HitCounter AnimateOn, 1 타는 알리지 않음),
	 * 2 = 마일스톤 (HitCounterShake·ComboMilestone·ComboEXP — Combo_Message = 보너스 템플릿 메시지, Combo_Amount = 붉은 오브),
	 * 값이 바뀔 때만 (0x1BA0D8: 같은 이벤트·값이면 건너뜀). 히트 0 도 이벤트 1 로 보내 숨긴다. 이벤트 0 은 메뉴·메시지 쪽 리셋 (0x1BA150).
	 */
	private void hitCounterHud() {
		UiVariables v = hud.variables();
		int hits = hero.hitCounter.hits();
		HitCounter.Reward r = hero.comboReward;
		if (r != null) {
			hero.comboReward = null;
			String msg = session.messages != null && r.messageId() >= 0 ? session.messages.get(r.messageId()) : null;
			v.set("PS2_HitCounter_Value", hits);
			v.set("PS2_Combo_Amount", r.redOrbs());
			v.set("PS2_Combo_Message", msg != null ? msg.replace("----", " ").replace('\n', ' ').trim() : "");
			v.event("PS2_HitCounter_Event", 2);
			hudHits = hits;
		} else if (hits != hudHits && hits != 1) {
			// 0 = 리셋 시간 (/Player/Hit Counter Reset Time) 이 지나 끊김: HUD 스크립트가 HitCounter 를 프레임 0 (Off) 으로 두고 0 &lt; 값이 아니라 다시 켜지 않음 → 사라짐
			v.set("PS2_HitCounter_Value", hits);
			v.event("PS2_HitCounter_Event", 1);
			hudHits = hits;
		}
	}

	/**
	 * Internal 0x10 ZoneReport(구역) — 구역 제목 (GameMgr_OnMessage 0x1B0A28 → 0x1B60D0 → 0x1B8BA0) [확실: 디스어셈블]:
	 * 메시지 = 800 + 구역 의 첫 줄 (0x1B95D0(…, 번호, 줄 0) — 803 "아테네로 향한 도로\n0" → "아테네로 향한 도로", 없으면 "Unknown zone")
	 * 을 HUD +0x84 (FLP 변수 ZoneReport_Title) 에, +0x264 (ZoneReport_Event) = 1. HUD FLP 루트가 StatsZoneReport 를 "On" 으로 —
	 * 9 프레임 페이드 인, Loop_Stats 를 GetTime 기준 10 초 반복, 28–35 페이드 아웃 (FLP_HUDK clip 129). 한 번만 띄우는 것은 스크립트
	 * (ZoneReportGroup: GlobalData:0 ≠ 구역 일 때만, 띄운 뒤 GlobalData:0 = 구역).
	 */
	void zoneReport(int zone) {
		String title = zoneTitle(session.messages, zone);
		if (hud != null) {
			hud.variables().set("PS2_ZoneReport_Title", title);
			hud.variables().event("PS2_ZoneReport_Event", 1);
		}
		zoneTitle = title;
		zoneShown = zone;
		zoneShownAt = time;
		System.out.println("구역 제목 " + zone + ": " + title);
	}

	/** 마지막으로 띄운 구역 제목 (검증용, 없으면 null). */
	String zoneTitle;
	/** 마지막 구역 제목 번호와 띄운 씬 시각 — 이음으로 넘긴다 ({@link GameSession#seamZone}). */
	private int zoneShown = -1;
	private float zoneShownAt;
	/** HUD StatsZoneReport 표시 시간 (페이드 인 9 프레임 + Loop_Stats 10 초, 21 문서) — 이 안에 이음이면 새 씬이 다시 띄운다. */
	static final float ZONE_TITLE_SECS = 10f;

	/** 구역 제목 = 메시지 800 + 구역 의 첫 줄 (0x1B95D0 줄 0), 없으면 "Unknown zone" (0x1B8BA0). */
	static String zoneTitle(devple.gow.tools.ui.MessageTable messages, int zone) {
		String text = messages != null ? messages.get(800 + zone) : null;
		return text == null || text.isEmpty() ? "Unknown zone" : text.split("\n", -1)[0].trim();
	}

	/** 아이템 카드 (HUD PickUpInfoMenu) 가 열려 있음 — 게임 멈춤, ○ 로 닫힘 (FLP PS2_Done). */
	private boolean pickupOpen;
	/** 카드로 띄운 메시지 번호 (닫힐 때 1040), 없으면 −1. */
	private int pickupMessage = -1;

	/**
	 * 아이템 카드 (원작 메시지 큐 0x21E728 → HUD 0x21E520 / 0x1B96D0) [확실]: PS2_PickUpInfo_Event = 종류 (3 고르곤의 눈, 4 불사조 깃털, 8 그림 메시지
	 * "GenericPickUp"), PS2_MeterBarGrow = 레벨 업 (1 HP / 2 MP / 0), 그림 메시지는 줄을 PS2_MessageTemplate_Line1..3 에 (+0x88/+0x8C/+0x90) 두고
	 * GenericPickUp 메시 텍스처를 그 그림으로 갈아 끼운다 (0x1B8178). 카드 동안 게임은 멈추고 (0x1B9F60), ○ 로 닫히면 메시지면 1040 (0x1B8EC0).
	 */
	private void showPickupCard(int kind, int grow, int msgId, java.util.function.Supplier<devple.gow.engine.render.Texture> picture) {
		UiVariables v = hud.variables();
		if (msgId >= 0) {
			String text = session.messages != null ? session.messages.get(msgId) : null;
			String[] lines = text == null ? new String[0] : text.replace("----", "\n").split("\n");
			for (int i = 1; i <= 3; i++) {
				v.set("PS2_MessageTemplate_Line" + i, i <= lines.length ? lines[i - 1].trim() : "");
			}
		}
		v.typeLines(null, 0, 0); // 카드 글은 타자 효과 없이
		hud.renderer().swapTexture(hud.renderer().clipTexture("GenericPickUp"), picture);
		v.set("PS2_MeterBarGrow", grow);
		v.set("PS2_Done", 0); // 닫힘 (FLP ClosePickUpMenu → 1) 을 변화로 받도록
		hideMinigameIcon();
		v.event("PS2_PickUpInfo_Event", kind);
		pickupOpen = true;
		pickupMessage = msgId;
		System.out.printf("아이템 카드: 종류 %d, 레벨 업 %d, 메시지 %d%s%n", kind, grow, msgId, picture != null ? " + 그림" : "");
	}

	/**
	 * 아이템 상자의 아이템 (원작 0x21602C → 0x1D9700) [확실]: 15 = HP 가득 (Entity_SetHP 최대), 16 = MP 가득, 그 밖 = 지금 개수 &lt; 최대면
	 * AddItem(아이템, 1) (0x1D8CF0) — 고르곤의 눈 (10) / 불사조 깃털 (11) 은 카드 0x21DA08 (종류 3 / 4, 6 개째에 레벨이 오르면 1 HP / 2 MP).
	 * 예전엔 상자가 열리기만 하고 아무것도 주지 않았다 (ATHN04A Chest5 MagicShard, 영상 5953–5956).
	 */
	private void chestItem(devple.gow.engine.nav.Interactables.Item it) {
		PlayerState p = hero.player;
		switch (it.chestItem) {
			case 15 -> p.hp = p.maxHp();
			case 16 -> p.mp = p.maxMp();
			default -> {
				if (it.chestItem == 10 && p.gorgonEyes >= GameRules.MAX_SHARDS || it.chestItem == 11 && p.phoenixFeathers >= GameRules.MAX_SHARDS) {
					return;
				}
				int hp0 = p.hpLevel, mp0 = p.mpLevel;
				p.addItem(it.chestItem, 1);
				System.out.printf("상자 아이템 %d: %s → 고르곤의 눈 %d, 불사조 깃털 %d%n", it.chestItem, it.owner, p.gorgonEyes, p.phoenixFeathers);
				if (it.chestItem == 10 || it.chestItem == 11) {
					hudBinding.update(p, hud.variables()); // 카드가 읽을 개수 (PS2_*Shard_Count) 먼저
					showPickupCard(it.chestItem == 10 ? 3 : 4, p.hpLevel > hp0 ? 1 : p.mpLevel > mp0 ? 2 : 0, -1, null);
				}
			}
		}
	}

	private void closePickupCard() {
		pickupOpen = false;
		hud.variables().set("PS2_Done", 0); // 다음 메뉴 (질문 1014 …) 의 Done 1 이 변화로 들어오게 — 남겨 두면 열쇠 질문에 답하지 못했다
		if (pickupMessage >= 0) {
			world.postEvent(1040, pickupMessage, 0, 0, world.player()); // 메시지 끝 (ATHN04A SuicideSparkle-EvtMsgDone 125 → 카메라·Idle 풀기)
		}
		System.out.println("아이템 카드 닫힘 (메시지 " + pickupMessage + ")");
		pickupMessage = -1;
	}

	/**
	 * 메시지 그림 (원작 0x1B1228: "MAT_FLASHMAT%d" 를 부른 레벨 슬롯 자원에서 찾음 → 0x21D980 요청의 그림) [확실]: 지금 레벨, 없으면 다른 슬롯 레벨의
	 * MAT_FlashMat&lt;번호&gt; 텍스처. 전 WAD 19 레벨 (ATHN04A 125 열쇠, ATHN02D 141 메두사의 시선, PAND01BB 257 …). 없으면 null.
	 */
	private java.util.function.Supplier<devple.gow.engine.render.Texture> flashPicture(int id) {
		String name = "MAT_FlashMat" + id;
		for (int side = 0; side < 2; side++) {
			devple.gow.tools.level.LevelData d = side == 0 ? level.data() : otherData;
			if (d == null || d.wad == null) {
				continue;
			}
			for (devple.gow.tools.wad.WadTag t : d.wad.resources()) {
				if (t.name().equalsIgnoreCase(name)) {
					String mat = t.name();
					if (side == 0) {
						String txr = level.assets().materialTexture(mat);
						return txr == null ? null : () -> level.assets().texture(txr);
					}
					return () -> {
						LevelScene o = otherScene;
						String txr = o != null ? o.assets().materialTexture(mat) : null;
						return txr == null ? null : o.assets().texture(txr);
					};
				}
			}
		}
		return null;
	}

	private void showMessage(int id, float seconds) {
		java.util.function.Supplier<devple.gow.engine.render.Texture> pic = flashPicture(id);
		if (pic != null) {
			showPickupCard(8, 0, id, pic); // 그림 메시지 = PickUpInfoMenu 8 "GenericPickUp" (0x1B9954), 시간과 무관하게 ○ 까지
			return;
		}
		String text = session.messages != null ? session.messages.get(id) : null;
		messageId = id;
		messageWait = false;
		messageSeconds = seconds;
		if (text == null) {
			messagePages = List.of();
			messageTimer = seconds > 0 ? seconds : 4f;
			return;
		}
		UiVariables v0 = hud.variables();
		boolean wait = seconds <= 0;
		messageButtonLate = wait && id >= 500 && id < 600;
		v0.set("PS2_MessageTemplate_Style", wait && !messageButtonLate ? 1 : 0); // +0x9C (0x1B9A80–0x1B9AD4)
		messagePages = messagePages(text);
		messagePage = 0;
		if (!showMessagePage(true)) {
			System.out.println("메시지 " + id + ": 쪽 줄 수가 1..3 이 아님 → 바로 닫음 (0x1B97A0)");
			closeMessage(false);
			return;
		}
		messageTimer = wait ? 0 : seconds;
		messageWait = wait;
		messageWaitTime = 0;
		messagePressTime = 0;
		devple.gow.engine.combat.AiTargets.globalHold = globalIdle();
		System.out.println("메시지 " + id + ": " + text.replace('\n', ' ') + (messagePages.size() > 1 ? " (" + messagePages.size() + " 쪽)" : ""));
	}

	/** 메시지 본문 → 쪽 (원작 0x1B96D0: 줄의 첫 두 글자가 '-' 면 쪽 구분, 그 줄은 보이지 않음) [확실]. */
	static List<List<String>> messagePages(String text) {
		List<List<String>> pages = new ArrayList<>();
		pages.add(new ArrayList<>());
		for (String line : text.split("\n", -1)) {
			if (line.startsWith("--")) {
				pages.add(new ArrayList<>());
			} else {
				pages.getLast().add(line.trim());
			}
		}
		return pages;
	}

	/**
	 * 메시지 한 쪽을 HUD MessageTemplates 에 (원작 0x1B96D0) [확실]: 쪽 줄 수가 1..3 이 아니면 거짓 (원작은 상자를 닫는다). 한 쪽짜리 = Line1..n + Event n
	 * (FLP GotoFrame2(n + 1) — 프레임 n 이 줄 1..n 배치), 여러 쪽 = 첫 쪽에서만 Event 3 (세 줄 틀) — 줄 하나인 쪽은 가운데 줄 (Line2) 에. 쪽마다 글자를
	 * 타자처럼 (0x1B9B00 → {@link UiVariables#typeLines}, 초당 50 자). 예: 오라클 342 "문을 통해 사막으로 가세요 …" 세 줄 + 둘째 쪽 "유일한 방법입니다."
	 * (PS2 영상 9885–9890) — 예전 엔진은 "----" 를 빈 줄로 바꿔 다섯 줄 틀에 넣어 둘째 쪽 줄이 잘렸다.
	 */
	private boolean showMessagePage(boolean first) {
		List<String> lines = messagePages.get(messagePage);
		int n = lines.size();
		if (n < 1 || n > 3) {
			return false;
		}
		boolean multi = messagePages.size() > 1;
		String[] l = {"", "", "", "", ""};
		int from = 1;
		if (multi && n == 1) {
			l[1] = lines.get(0);
			from = 2;
		} else {
			for (int i = 0; i < n; i++) {
				l[i] = lines.get(i);
			}
		}
		UiVariables v = hud.variables();
		for (int i = 1; i <= 5; i++) {
			v.set("PS2_MessageTemplate_Line" + i, l[i - 1]);
		}
		v.typeLines("PS2_MessageTemplate_Line", from, multi && n == 1 ? 2 : n);
		if (first) {
			v.event("PS2_MessageTemplate_Event", multi ? 3 : n);
		}
		messagePressTime = 0;
		return true;
	}

	/** 다음 쪽 (원작 0x1B8D7C / 0x1B8E5C: +0x1534 + 1 < 쪽 수 → 0x1B96D0), 마지막 쪽이었으면 닫는다 — 버튼으로 닫을 때만 1040 (0x1B8EC0). */
	private void nextMessagePage(boolean button) {
		messagePage++;
		if (messagePage < messagePages.size() && showMessagePage(false)) {
			messageTimer = button ? 0 : messageSeconds;
			return;
		}
		closeMessage(button);
	}

	/**
	 * 메시지 상자 닫기: Event 0 · 타자 끔. 1040 (0x410, 인자 0 = 번호, 보낸 쪽 = 영웅) 은 버튼으로 닫을 때만 (0x1B8EC0) — 시간이 다 된 메시지는 조용히
	 * 닫힌다 (0x1B8DCC) [확실]. 표에 없는 번호 (원작은 "Msg %d" 를 띄우고 버튼을 기다림) 는 엔진이 시간으로 닫되 버튼 메시지면 1040 을 보낸다.
	 */
	private void closeMessage(boolean post1040) {
		UiVariables v = hud.variables();
		v.event("PS2_MessageTemplate_Event", 0);
		v.typeLines(null, 0, 0);
		messageWait = false;
		messageTimer = 0;
		messagePages = List.of();
		devple.gow.engine.combat.AiTargets.globalHold = globalIdle();
		if (messageId >= 0 && post1040) {
			world.postEvent(1040, messageId, 0, 0, world.player()); // 메시지 끝 (아레나 "완료" 메시지 → 워프 등)
		}
		messageId = -1;
	}

	/**
	 * 세이브 포인트 거리 (원작 0x1DC678: 영웅 GO 위치 − 세이브 포인트 GO 위치 의 길이 × 1/16, 가장 가까운 것 → 영웅 +0x90; 0x1DC3F8 이 &lt; 4.0 m 이면
	 * R2 안내 (아이콘 슬롯 0 = 14) 와 R2 → 저장) [확실: 디스어셈블]. 4 m = 64 단위 (이전 엔진 45 [근사]).
	 */
	static final float SAVE_POINT_RANGE = 4f * 16f;

	private boolean nearSavePoint() {
		for (Vector3f sp : savePoints) {
			if (sp.distance(hero.ctl.position) < SAVE_POINT_RANGE) {
				return true;
			}
		}
		return false;
	}

	/** 스크립트 화면 페이드 (Entity 0x5 FadeOut / 0x6 FadeIn, {@link ScreenFade}). 레벨마다 새로 (0x1AF600). */
	final ScreenFade screenFade = new ScreenFade();

	/** Animator 가 튼 레벨 GO 의 채널 이미터 (소유 GO 이름 소문자 → 이펙트) 와 멈춘 뒤 경과 시간. */
	private final Map<String, devple.gow.engine.fx.EffectSystem.Effect> animatorFx = new HashMap<>();
	private final Map<String, Float> animatorFxIdle = new HashMap<>();
	/** 멈춘 Animator 이펙트를 끄기까지 (초) — 나온 파티클은 수명까지 남는다 [근사]. */
	static final float ANIMATOR_FX_LINGER = 4f;

	/**
	 * 스크립트 Animator 가 레벨 GO 를 틀면 그 GO 의 FXC 이미터 중 <b>채널 링크</b> (노드 +0x62 = 원작 채널 id, 0x130240) 가 이 act 에 곡선을 가진 것을 같이 돌린다 —
	 * 원작은 GO 의 애니 컴포넌트가 매 프레임 타입 10 채널로 이미터 P 블록 (생성률 스트림 8 · 속력 4) 을 덮어쓰므로, act 가 돌 때만 불덩이·연기가 나온다
	 * (40 §3, 이펙트 GO 와 같은 구성) [확실: 코드 0x100EA8/0x130240 + 데이터]. 관절 자세·채널은 원작 디스크립터 배치 (05 §2.8.1). ATHN03A 성문 cine1FireBall:
	 * GateExplosion 의 관절 2 Fireball03GE 가 0.43 초 동안 성문으로 날아가며 FBFemit2GE 150/초 · FBSemit4GE 50/초 · FXC_GE 100/초 (PS2 영상 4978 의 불덩이),
	 * 같은 구조의 건물 폭발 GO (group3251/3278/3284, TallBuildExplode2, CornerRip00, group3289) 도. 메시는 레벨이 그대로 그리고 (hideMesh), 링크 없는 상수
	 * 이미터는 LevelEmitters 몫. 시간은 발판 몸 (Platforms) 의 act 시간에 맞춘다.
	 */
	void startAnimatorEffects(List<String> objects) {
		if (magicFx == null || nav == null) {
			return;
		}
		devple.gow.engine.fx.EffectSystem fx = magicFx.effects();
		for (String o : objects) {
			devple.gow.engine.nav.Platforms.Body b = nav.navWorld.platforms().body(o);
			devple.gow.tools.fx.EffectLibrary.EffectDef def = fx.library().resolve(o);
			if (b == null || b.anim() == null || def == null || def.emitters().isEmpty()) {
				continue;
			}
			devple.gow.tools.anim.Animation act = def.act(b.anim().name);
			if (!def.hasRateChannel(act)) {
				continue;
			}
			String key = o.toLowerCase(java.util.Locale.ROOT);
			devple.gow.engine.fx.EffectSystem.Effect e = animatorFx.get(key);
			if (e == null || e.done()) {
				e = fx.play(o, devple.gow.engine.fx.EffectSystem.Anchor.of(new org.joml.Matrix4f().set(b.instanceMatrix())), Float.POSITIVE_INFINITY);
				if (e == null) {
					continue;
				}
				devple.gow.engine.fx.EffectSystem.Effect fe = e;
				e.hideMesh = true; // 메시는 레벨 (Platforms 자세) 이 그린다
				e.jointEmitters = true;
				e.hold = true;
				e.scale = 1f / Math.max(def.scale(), 1e-3f); // 인스턴스 스케일은 행렬에 이미 들어 있다
				e.emitterOff = ed -> {
					devple.gow.tools.anim.Animation a = def.act(fe.actName());
					return a == null || !a.hasChannel(ed.channel());
				};
				animatorFx.put(key, e);
				System.out.println("Animator 이펙트: " + o + " act " + act.name);
			}
			e.setAct(act.name);
			e.setActTime(b.time());
			animatorFxIdle.put(key, 0f);
		}
	}

	/** 틱마다 Animator 이펙트의 act 시간을 발판 몸에 맞추고, 멈춘 지 {@link #ANIMATOR_FX_LINGER} 초면 끈다. */
	private void updateAnimatorEffects(float dt) {
		if (animatorFx.isEmpty()) {
			return;
		}
		for (java.util.Iterator<Map.Entry<String, devple.gow.engine.fx.EffectSystem.Effect>> it = animatorFx.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<String, devple.gow.engine.fx.EffectSystem.Effect> en = it.next();
			devple.gow.engine.fx.EffectSystem.Effect e = en.getValue();
			devple.gow.engine.nav.Platforms.Body b = nav != null ? nav.navWorld.platforms().body(en.getKey()) : null;
			if (e.done() || b == null || b.anim() == null) {
				e.stop();
				it.remove();
				continue;
			}
			e.setAct(b.anim().name);
			e.setActTime(b.time());
			float idle = b.playing() ? 0 : animatorFxIdle.getOrDefault(en.getKey(), 0f) + dt;
			animatorFxIdle.put(en.getKey(), idle);
			if (idle > ANIMATOR_FX_LINGER) {
				e.stop();
				it.remove();
			}
		}
	}

	/** 지금 도는 Animator 이펙트 (검증용, 소문자 GO 이름). */
	java.util.Set<String> animatorEffects() {
		return animatorFx.keySet();
	}

	/** 하늘 GO 이미터 이펙트와 그 GO (act 시간 동기). */
	private final List<devple.gow.engine.fx.EffectSystem.Effect> skyFx = new ArrayList<>();
	private final List<LevelScene.SkyGo> skyFxGo = new ArrayList<>();

	/**
	 * 하늘 GO (SCR_Sky 소유자) 의 이미터를 하늘 공간에서 돌린다 — 원작 0x218848 은 GO 를 매 프레임 카메라로 옮기므로 그 파티클 컴포넌트의 이미터도 따라간다
	 * [확실: GO 이동 / 근사: 이미 나온 파티클은 월드에 남고 깊이는 일반 파티클 패스]. 기준 = 인스턴스 행렬을 (눈 − 관절 0) 만큼 옮긴 것, 관절 붙은 이미터,
	 * 반복 시작 act 는 레벨 렌더와 같은 시간 (시작 시간 + 효과 시간). 메시는 LevelScene 이 하늘 단계에서 그린다.
	 * ATHN03A/03AA: group2 화톳불 (도시가 불타는 평원), 거대한 아레스의 불붙은 바위·불덩이·재.
	 */
	void startSkyEffects() {
		if (magicFx == null || level == null) {
			return;
		}
		devple.gow.engine.fx.EffectSystem fx = magicFx.effects();
		for (LevelScene.SkyGo s : level.skyGos()) {
			devple.gow.tools.fx.EffectLibrary.EffectDef def = fx.library().resolve(s.name());
			if (def == null || def.emitters().isEmpty()) {
				continue;
			}
			Matrix4f inst = new Matrix4f().set(s.inst());
			Vector3f off = new Vector3f(s.off());
			devple.gow.engine.fx.EffectSystem.Effect e = fx.play(s.name(), out -> {
				out.translation(cam.eye.x - off.x, cam.eye.y - off.y, cam.eye.z - off.z).mul(inst);
				return true;
			}, Float.POSITIVE_INFINITY);
			if (e == null) {
				continue;
			}
			e.hideMesh = true;
			e.jointEmitters = true;
			e.scale = 1f / Math.max(def.scale(), 1e-3f);
			e.silent = true; // 하늘 GO 의 SEM (아레스 SNDAthn3Ares*) 은 레벨 소리 쪽 [미확인: 재생 시점]
			if (s.act() != null && def.act(s.act().name) != null) {
				e.setAct(s.act().name);
				e.hold = true;
				e.setActTime(s.start() + level.fxTime());
			}
			e.sky = true; // 파티클은 하늘 단계에서 (세계보다 먼저 — 0x218848 이 파티클 부품도 층 1 로, 43 §17)
			skyFx.add(e);
			skyFxGo.add(s);
			System.out.println("하늘 이펙트: " + s.name() + " 이미터 " + def.emitters().size() + (s.act() != null ? " act " + s.act().name : ""));
		}
	}

	private void updateSkyEffects() {
		for (int i = 0; i < skyFx.size(); i++) {
			LevelScene.SkyGo s = skyFxGo.get(i);
			if (s.act() != null) {
				skyFx.get(i).setActTime(s.start() + level.fxTime());
			}
		}
	}

	/** 생성한 세이브 포인트 몸 (빛기둥). */
	private final List<devple.gow.engine.fx.EffectSystem.Effect> savePointBodies = new ArrayList<>();

	/**
	 * Creator Name0 "savepoint" 의 몸 (원작 0x1ACCA8 은 상자와 같은 경로로 그 이름의 오브젝트를 소유 GO 행렬에 새 GO 로 만든다 — 38 §21) [확실]:
	 * R_PERMK 오브젝트 {@code savepoint} = MDL_SavePoint (빛기둥 SFXlightBeam7/10/11/12 · starBurst, 관절 6) + ANM_savepoint "SavePoint" 4 초 반복 +
	 * SEM_SNDSavePoint (GO 템플릿 gosavepoint, 클래스 SavePoint 0x22E260). R_SAVEPT.WAD 는 메모리카드 아이콘 텍스처 (TXR_SavePt01..41) 뿐이다.
	 *
	 * @return 몸을 만들었으면 true
	 */
	private boolean spawnSavePoint(float[] inst) {
		if (magicFx == null) {
			return false;
		}
		devple.gow.engine.fx.EffectSystem fx = magicFx.effects();
		devple.gow.tools.fx.EffectLibrary.EffectDef def = fx.library().resolve("savepoint");
		if (def == null) {
			return false;
		}
		devple.gow.engine.fx.EffectSystem.Effect e = fx.play("savepoint",
				devple.gow.engine.fx.EffectSystem.Anchor.of(new org.joml.Matrix4f().set(inst)), Float.POSITIVE_INFINITY);
		if (e == null) {
			return false;
		}
		e.scale = 1f / Math.max(def.scale(), 1e-3f); // 인스턴스 스케일은 inst 에 이미 들어 있다 (ChestProps 와 같음)
		savePointBodies.add(e);
		return true;
	}

	/**
	 * 세이브 포인트 (원작 상태 15 SavePoint → 16 SaveGameMenu → 17 SaveWrite): HUD 의 "저장하시겠습니까?"(PS2_SaveGame_Event) →
	 * 예 → 원작 메모리카드 화면을 {@link MemoryCardFlow} 로 진행 (카드 → 파일 → 덮어쓰기/새 파일 → 저장 중 → 완료).
	 */
	/** 현재 미니게임 아이콘 (35 문서 §6): 타이밍 프롬프트 = 종류 아이콘, 연타 = XButtonPress [추정: 연타 아이콘 선택], 없으면 숨김. */
	/** 잡은 동안 던질 대상 (원작 combatant +0x80 — 잡은 적 +0x84 는 제외) 과 그 위 조준 표식 goTargetLK (Player +0x80). */
	private EnemyFighter throwTarget;
	private devple.gow.engine.fx.EffectSystem.Effect reticle;
	private final org.joml.Matrix4f reticleAt = new org.joml.Matrix4f();
	static final float MANUAL_TARGET_RANGE = 50f * 16f, AUTO_AIM_DAMPING = 0.1f;

	/**
	 * 플레이어 갱신 0x1D8470 (영웅 현재 기술 플래그 +0x0E): 0x18 둘 다 = 잡은 적을 뺀 가장 가까운 적을 고름 (Manual Targeting Range 50,
	 * 후보 콜백 0x1DA038 이 +0x84·죽은 적 제외) — 해골 MAISKB00 220·MAISKB01 223 (잡고 있는 동안), 0x8 만 = 유지 (MAISKB02 221 던지기),
	 * 0x8 이면 대상 위에 표식 (높이 = 대상 CombatFileSet #a58b9446 × 16, 크기 = Reticle Scale), 아니면 숨김.
	 * 자동 조준 0x2077A0: 잡은 동안 영웅이 대상 쪽으로 돈다 (Auto Aim Damping 0.1, FLAG_1 (이벤트 15) 창이면 생략) → 던지기가 그 적을 향한다.
	 * [근사: 왼쪽 스틱으로 다른 후보 넘기기 없음, 표식 틴트 (/Player/Reticle) 없음]
	 */
	private void updateThrowTarget(float dt) {
		var hc = hero.combat;
		int fl = hc.active() ? hc.move().flags() : 0;
		EnemyFighter partner = grab.partner();
		if ((fl & 0x18) == 0x18) {
			EnemyFighter best = null;
			float bd = MANUAL_TARGET_RANGE * MANUAL_TARGET_RANGE;
			for (Enemy en : enemies) {
				EnemyFighter f = en.fighter();
				float d = f.pos.distanceSquared(hero.ctl.position);
				if (f != partner && !f.dead() && d < bd) {
					bd = d;
					best = f;
				}
			}
			throwTarget = best;
		} else if ((fl & 0x8) == 0) {
			throwTarget = null;
		}
		if (throwTarget != null && (throwTarget.dead() || !enemies.stream().anyMatch(en -> en.fighter() == throwTarget))) {
			throwTarget = null;
		}
		if (throwTarget != null && partner != null && !hc.eventOn(15)) {
			float want = (float) Math.atan2(-(throwTarget.pos.x - hero.ctl.position.x), -(throwTarget.pos.z - hero.ctl.position.z));
			float diff = (float) Math.IEEEremainder(want - hero.ctl.yaw, Math.PI * 2);
			hero.ctl.yaw += diff * AUTO_AIM_DAMPING;
		}
		boolean show = throwTarget != null && (fl & 0x8) != 0;
		if (show) {
			EnemyFighter t = throwTarget;
			Enemy te = enemies.stream().filter(x -> x.fighter() == t).findFirst().orElse(null);
			Tweaks tw = te != null ? te.kind().tweaks() : null;
			String set = tw != null ? tw.firstNode("/TweakTemplates/CombatFileSet/") : null;
			float h = set != null ? Float.intBitsToFloat(tw.rawHash(set, 0xa58b9446).orElse(0)) * 16f : 0f;
			float sc = set != null ? tw.f(set, "Reticle Scale", 1f) : 1f;
			reticleAt.translation(t.pos.x, t.pos.y + h, t.pos.z).scale(sc);
			if (reticle == null && magicFx != null) {
				reticle = magicFx.effects().play("goTargetLK", out -> {
					out.set(reticleAt);
					return true;
				});
			}
		} else if (reticle != null) {
			reticle.stop();
			reticle = null;
		}
	}

	private static final int SCR_NO_SHADOW = 0xBFC016FE, SCR_EXPLODE_DEATH = 0xBA76AD70;
	/** SCR_ArcherShoot (Hash127Upper, 등록 0x18DB88 → 처리 0x18DAA8) — 궁수 SHOOT 의 SCRIPT 이벤트. */
	static final int SCR_ARCHER_SHOOT = 0x63F805BF;
	/** Hash127Upper("SCR_FirstTimeMsg") — 기술 SCRIPT 이벤트, idx = 첫 안내 번호 (0x19CD20 → 0x1DBEB0). */
	static final int SCR_FIRST_TIME_MSG = 0x42077801;

	/** 첫 안내 (0x1DBEB0): 비트·누적·플레이 2 초 규칙을 지나면 메시지 500 + id (있을 때만, 0x1B9528) — 버튼 대기 메시지, 버튼은 2.5 초 뒤 (0x1B8EF8). */
	private void firstTimeHint(int id) {
		if (hero == null || !hero.player.firstTimeHint(id, session.playTime)) {
			return;
		}
		int msg = 500 + id;
		boolean has = session.messages != null && session.messages.get(msg) != null;
		System.out.println("첫 안내 " + id + (has ? " → 메시지 " + msg : " (메시지 없음)"));
		if (has) {
			showMessage(msg, 0);
		}
	}

	/** 스크립트 Idle(true) (Internal 0xC) 동안 조작 잠금. */
	private boolean scriptIdle, hudHidden;
	/**
	 * 버튼을 기다리는 메시지 (ShowMessage 초 ≤ 0, 원작 0x1B96D0 → 0x1B9F60(gm, 1) → 0x1BF080(슬롯 1, 1) = 전역 정지의 둘째 슬롯) [확실: 코드].
	 * 확정 버튼 (한국판 ○ — 0x1B8C00: 0x29DB5C 가 4·5 면 패드 비트 5, 아니면 비트 6 ×) 을 누르면 다음 쪽 / 닫고 1040 (0x410, 인자 = 메시지 번호),
	 * 슬롯 1 해제. 메시지 500–599 는 버튼 표시를 2.5 초 늦춘다 (+0x1578 → MessageTemplate_Event 10), 그 밖은 바로 (MessageTemplate_Style 1 →
	 * HUD XButtonOnOff 프레임 1). 예전 엔진은 4 초 뒤 저절로 닫았다 (ATHN02A 아테나 석상 218/322, ATHN01F 쌍둥이 317/319).
	 */
	private boolean messageWait, messageButtonLate;
	private float messageWaitTime;
	/** 지금 쪽을 띄우거나 ○ 를 누른 뒤 지난 시간 (자동 플레이의 ○ 간격). */
	private float messagePressTime;
	/** 지난 틱의 실제 입력 (Idle 로 바꾸기 전 — 메시지 확정 버튼은 전역 정지 중에도 읽는다). */
	private Input lastRawInput;
	/** R2 (1024) 로 버튼 안내 아이콘을 지웠음 — 안내 볼륨을 나갈 때까지 다시 안 띄운다 (아테나 석상 대사 상자 위 R2). */
	private boolean promptCleared;

	/** 전역 정지 0x29DB58 = 슬롯 0 (스크립트 Idle) 또는 슬롯 1 (버튼을 기다리는 메시지). */
	private boolean globalIdle() {
		return scriptIdle || messageWait;
	}
	/**
	 * 지난 틱 영웅이 받은 입력이 창 입력이 아닐 때 (디버그 스크립트·봇·AUTO) 그 입력 — R2 사용 버튼 (1024·세이브) 도 같은 입력으로 본다.
	 * 예전에는 창 입력만 봐서 봇·스크립트 R2 가 버튼 안내 센서에 닿지 않았다 (ATHN01F 쌍둥이 teaseTwins-volumeR-talky) [엔진 전용].
	 */
	private Input lastInput;
	/** AUTO 검증의 결합 미니게임 자동 입력 [엔진 전용]. */
	private final devple.gow.engine.boss.AutoPad autoPad = new devple.gow.engine.boss.AutoPad();
	private static final Input IDLE_INPUT = new devple.gow.engine.input.ScriptedInput(0, List.of());

	/** 재질 피해 타이머 (원작 +0x2B0): 영웅 하나, 적은 개체마다. */
	private float surfTimer;
	private final java.util.Map<EnemyFighter, Float> enemySurfTimer = new java.util.IdentityHashMap<>();

	/**
	 * 바닥 재질 피해 (원작 0x1EAC70 → 0x1EADC8, 공용 내비 0x1EBD78 매 프레임): 밟은 면의 시트 속성 "Damage" (영웅) / "AIDamage" (적) 를
	 * 0..최대 HP 로 자르고, 타이머가 0 이면 값 × AIDamageMult (난이도 0.5/1/2.5/5) × 코스튬 AIDamage × 신 모드 감소 를 입히고 타이머 = 1 초.
	 * 예 PAND04J visualsPand04J_Damage 10 → 초당 쉬움 5 / 보통 10 / 어려움 25 / 신 50. [근사: 벽 면 접촉은 보지 않음]
	 */
	private void surfaceDamage(float dt) {
		surfTimer = Math.max(0, surfTimer - dt);
		Vector3f p = hero.ctl.position;
		int d = hero.ctl.grounded ? surfaceValue(p, false) : 0;
		if (d > 0 && surfTimer <= 0 && hero.player.hp > 0) {
			hero.player.hp = Math.max(0, hero.player.hp - Math.min(d, hero.player.maxHp()) * hero.incomingScale());
			surfTimer = 1f;
		}
		for (Enemy en : enemies) {
			EnemyFighter f = en.fighter();
			float t = Math.max(0, enemySurfTimer.getOrDefault(f, 0f) - dt);
			int ad = !f.dead() && f.onGround() ? surfaceValue(f.pos, true) : 0;
			if (ad > 0 && t <= 0) {
				f.selfHp(Math.min(ad, f.maxHp()) * hero.incomingScale());
				t = 1f;
			}
			enemySurfTimer.put(f, t);
		}
		enemySurfTimer.keySet().removeIf(f -> enemies.stream().noneMatch(en -> en.fighter() == f));
	}

	private int surfaceValue(Vector3f p, boolean ai) {
		int best = 0;
		for (LevelCollision c : new LevelCollision[]{col, otherCol}) {
			if (c == null) {
				continue;
			}
			LevelCollision.Floor fl = c.floorAt(p.x, p.y + STEP, p.z, STEP + 4);
			var info = fl != null ? fl.tri().info() : null;
			if (info != null) {
				best = Math.max(best, ai ? info.aiDamage() : info.damage());
			}
		}
		return best;
	}

	private int minigameIconIndex() {
		if (!grab.active()) {
			// 아이콘 슬롯 8 개 중 번호가 큰 것 하나 (0x21E908): 2 = 잡은 TAndF·크랭크 연타 (0x1E17F0, 열리면 끔 0x1C38A8),
			// 1 = 버튼 안내 센서 (0x1AAD40), 0 = R2 로 잡힐 가까운 오브젝트의 "Show R2" (0x1DC3F8) — ATHN01A 해치 HydraHatchDoor (TAndF 051)
			var nv = hero.nav;
			var held = nv != null ? nv.item() : null;
			if (held != null && held.showPrompt && held.progress < 1f && (held.kind == devple.gow.engine.nav.Interactables.Kind.LIFT || held.crank)) {
				return held.crank ? devple.gow.engine.ui.MinigameIcon.WIGGLE_BACK_FORTH : devple.gow.engine.ui.MinigameIcon.R2_PRESS;
			}
			boolean prompt = world.promptActive();
			if (!prompt) {
				promptCleared = false; // 볼륨을 나감 → 다음 진입에 다시 표시
			} else if (!promptCleared && !globalIdle()) {
				return devple.gow.engine.ui.MinigameIcon.PROMPT;
			}
			var near = nv != null && held == null ? nv.promptItem() : null;
			if (near != null) {
				return near.showPrompt ? devple.gow.engine.ui.MinigameIcon.PROMPT : devple.gow.engine.ui.MinigameIcon.NONE;
			}
			// 0x1DC3F8: 세이브 포인트 4 m 안이면 슬롯 0 = 14 (R2) — 가까운 R2 오브젝트 (0x1C3F18) 가 있으면 그 "Show R2" 가 덮는다
			return nearSavePoint() && !globalIdle() ? devple.gow.engine.ui.MinigameIcon.PROMPT : devple.gow.engine.ui.MinigameIcon.NONE;
		}
		if (grab.timed() != null) {
			return grab.timed().iconIndex();
		}
		var q = grab.qte();
		return q != null && q.state() == devple.gow.engine.combat.RepeatPressQte.State.RUNNING
				? devple.gow.engine.ui.MinigameIcon.BUTTON_PRESS : devple.gow.engine.ui.MinigameIcon.NONE;
	}

	/**
	 * 저장 질문 열기 — 원작 0x21D8B0 (상태 11 → 15): 0x29F47C = 1, HUD SaveGame_Event(+0x268) = 1, Menu_Choice(+0x278) = 0,
	 * Done(+0x27C) = 0 [확실]. Done 을 0 으로 되돌려야 FLP 가 ○ 에서 쓰는 PS2_Done = 1 이 다시 "바뀜" 으로 들어온다
	 * (두 번째 세이브 포인트에서 질문이 안 닫히던 문제 — 43 문서 §18).
	 */
	private void askSave() {
		paused = saveAsk = true;
		saveReask = false;
		saveCloseTimer = 0;
		hud.variables().set("PS2_Menu_Choice", 0).set("PS2_Done", 0);
		hideMinigameIcon(); // 0x21D8B0 → 0x2207F8 → 0x1BA150
		hud.variables().event("PS2_SaveGame_Event", 1);
	}

	/**
	 * HUD 메뉴·메시지 리셋 0x1BA150 은 끝에서 늘 0x1BA338(−1) 로 미니게임 아이콘을 뗀다 [확실] — 저장 질문 (0x21D8B0 → 0x2207F8),
	 * 질문 메뉴 (0x1B9F14), 메시지·아이템 카드 (0x1B96D0) 가 부른다. 아이콘을 다시 고르는 0x21E908 은 놀이 상태 11 (0x21FF90) 에서만,
	 * 그것도 메시지·질문 (+0x1528/+0x152C/+0x1530) 이 없을 때만 돈다 → 메뉴가 닫힐 때까지 숨김. 예전엔 세이브 포인트 "R2" 가 저장 질문·
	 * 메모리카드 화면 위에 남았다 (영상 6452–6456 에는 없음, 43 문서 §18) [근사: 멈추지 않는 메시지 동안의 숨김은 아직 안 함].
	 */
	private void hideMinigameIcon() {
		minigameIcon.show(hud.player(), devple.gow.engine.ui.MinigameIcon.NONE);
	}

	/**
	 * 세이브 메뉴 닫기 예약 (원작 상태 18 0x220740): 0x29F478 가 서 있으면 첫 프레임에 MemoryCard_Event = 0, 그다음 10 프레임 뒤
	 * SaveGame_Event = 0 → 상태 11. "아니오" (상태 15 0x220524) 는 곧바로 상태 18 로 (1 + 10 프레임), 저장 끝 (상태 17 0x220680) 은
	 * 10 프레임 기다린 뒤 상태 18 로 (10 + 1 + 10 프레임) [확실: 프레임 수 / 근사: 60 Hz 로 초 환산, 0x21FD00 카드 확인 대기 생략].
	 */
	private void closeSaveMenu(float frames) {
		saveCloseTimer = frames / 60f;
	}

	private void onSaveAnswered(boolean yes) {
		saveAsk = false;
		if (!yes) {
			closeSaveMenu(11); // FLP 가 ○ 에서 SaveGameMenu "Off" (질문만 숨김) — Ω 배경은 SaveGame_Event 0 까지 남는다
			return;
		}
		Vector3f p = new Vector3f(hero.ctl.position);
		float yaw = hero.ctl.yaw;
		// 원작 World_Serialize 0x1B3E48: 올라 있는 두 슬롯도 0x1B4080(…, 0) 으로 표 칸에 쓴 뒤 표 전체를 쓴다 → 지금 레벨 상태도 세이브에 (43 §21)
		rememberLevels();
		saveFlow = MemoryCardFlow.save(hud.variables(), session.messages, SaveGame.dir(),
				() -> SaveGame.capture(session, levelName, new float[]{p.x, p.y, p.z}, yaw));
		saveFlow.start();
	}

	private void updateSaveFlow(float dt) {
		saveFlow.update(dt);
		switch (saveFlow.result()) {
			case SAVED -> {
				checkpoint.set(hero.ctl.position);
				checkpointYaw = hero.ctl.yaw;
				System.out.println("저장 완료: " + MemoryCardFlow.file(SaveGame.dir(), saveFlow.fileIndex()));
				saveFlow = null;
				closeSaveMenu(21); // 상태 17: MemoryCard_Event 0 (흐름이 이미 보냄) → 10 프레임 → 상태 18 (1 + 10 프레임) → SaveGame_Event 0
			}
			case CANCELLED -> {
				// 카드 선택에서 △: 쓰기 흐름 0x230A98 이 -1 (0x2A2730 ≠ 1, 0x230D80) → 상태 17 이 10 프레임 뒤 SaveGame_Event = 0x29F47C (1),
				// Menu_Choice·Done = 0, 상태 15 — 저장 질문으로 돌아간다 [확실]
				saveFlow = null;
				saveReask = true;
				closeSaveMenu(10);
			}
			default -> {
			}
		}
	}

	private void heroDied() {
		if (auto != null) {
			auto.died(hero.ctl.position);
		}
		hero.player.deaths++;
		orbPickups.clear(); // 영웅 HP ≤ 0 이면 떠 있던 오브는 사라진다 (0x226710)
		if (auto == null && bot == null && debugInput == null && System.getProperty("gow.nodeadmenu") == null) {
			openDeadMenu();
			return;
		}
		restartAtCheckpoint();
	}

	private void restartAtCheckpoint() {
		hero.player.onCheckpointRestart(hero.player.hp);
		respawnAtCheckpoint();
	}

	/** 체크포인트 자리·방향으로 (원작 스냅샷의 플레이어 행렬) [확실: 위치·방향 / 근사: 세계 상태는 되돌리지 않음]. */
	private void respawnAtCheckpoint() {
		respawn(checkpoint);
		hero.ctl.yaw = checkpointYaw;
	}

	/**
	 * 원작 CheckPoint (GameMgr 메시지 0, 0x1B0234) 의 거름 [확실]: 플레이어 체력 (+0x178) ≥ 1, 레벨 시작 (0x29D924) 뒤 1 초 이상, 그리고 보낸 엔티티 (0x29D928) 가
	 * 지난번 (0x303D74) 과 같으면 5 초 (0x303D78 + 5) 가 지나야 — 통과하면 로그 "CheckPoint" 와 0x29D960 = 1 (프레임 끝 스냅샷).
	 */
	private boolean acceptCheckpoint(ScriptEntity source, float hp, float now) {
		if (!checkpointPasses(source, lastCheckpointSource, lastCheckpointTime, hp, now)) {
			return false;
		}
		lastCheckpointSource = source;
		lastCheckpointTime = now;
		return true;
	}

	/** {@link #acceptCheckpoint} 의 판정만 (시험용): now = 레벨 시작 뒤 초. */
	static boolean checkpointPasses(Object source, Object lastSource, float lastTime, float hp, float now) {
		if (hp < 1f || now < 1f) {
			return false;
		}
		return source != lastSource || now - 5f >= lastTime;
	}

	/** 사망 메뉴 상태: 0 없음, 1 "죽었습니다" 화면 (DeadMenu On), 2 선택 대기. */
	private int deadMenu;
	private float deadMenuTime;

	/**
	 * 사망 메뉴 (FLP_HUDK DeadMenu, 원작 0x220360): PS2_DeadMenu_Event 1 = "죽었습니다" (SND_IG_YOUAREDEAD, Pause_Check 7), 잠시 뒤 2 = 선택
	 * (다시 하기 / 나가기), 3 = 그 위에 "쉬움으로 다시 하기" (0x1DBF90 EasyOffer: 보통·어려움이고 사망 수 % "Deaths Before Easy Offer"(5) == 0,
	 * 레벨 이름이 Arena 로 시작하지 않음). 확정 → PS2_Done 1 + PS2_Menu_Choice 0 다시 / 1 나가기 / 2 쉬움으로 (0x29F484 → 체크포인트 로드 때 난이도 0).
	 */
	private void openDeadMenu() {
		paused = true;
		deadMenu = 1;
		deadMenuTime = 0;
		hud.variables().set("PS2_Menu_Choice", 0);
		hud.variables().event("PS2_DeadMenu_Event", 1);
	}

	private boolean easyOffer() {
		int d = session.difficulty;
		int every = (int) orbTweaks.f("/Player/", "Deaths Before Easy Offer", 5f);
		return (d == 1 || d == 2) && every > 0 && hero.player.deaths % every == 0 && !levelName.toUpperCase(java.util.Locale.ROOT).startsWith("ARENA");
	}

	private void updateDeadMenu(float dt) {
		if (deadMenu == 1 && (deadMenuTime += dt) > 2f) {
			deadMenu = 2;
			hud.variables().event("PS2_DeadMenu_Event", easyOffer() ? 3 : 2);
		}
	}

	private void onDeadMenuClosed(Object choice) {
		deadMenu = 0;
		paused = false;
		int c = (int) Float.parseFloat(String.valueOf(choice));
		if (c == 1 && pendingEngine != null) {
			leaving = true;
			pendingEngine.scenes().transitionTo(new ShellScene(session.rom)); // 나가기 → 주 메뉴
			return;
		}
		if (c == 2) {
			// 쉬움으로 다시 하기: 난이도 0 (세이브에도 0), 적 배율·회복 시간도 새 난이도로
			session.difficulty = 0;
			hero.difficulty = 0;
			for (Enemy en : enemies) {
				en.fighter().setDifficulty(0);
			}
			System.out.println("쉬움으로 다시 하기 (사망 " + hero.player.deaths + ")");
		}
		restartAtCheckpoint();
	}

	/**
	 * GameMgr 스크립트 타이머 (Internal 0x14–0x19, {@link devple.gow.engine.script.ScriptTimers}) 한 프레임 — 스크립트 세계 갱신과 같이 (원작 0x1A7A78 의
	 * 0x1A81F8, 게임 상태 0xB 에서만: 일시정지·질문·카드·세이브·사망/실패 메뉴 동안은 멈춤). 다 된 타이머는 전역 이벤트 1050 (인자 0 = 번호, 보낸 쪽 = 영웅),
	 * 표시 타이머는 HUD 모래시계.
	 */
	private void scriptTimers(float dt) {
		devple.gow.engine.script.ScriptTimers t = world.host().timers;
		t.tick(dt, id -> {
			if (System.getProperty("gow.debug.script") != null) {
				System.out.printf(java.util.Locale.ROOT, "[스크립트 %.2f] 타이머 %d 끝 → 1050%n", world.time(), id);
			}
			world.postEvent(devple.gow.engine.script.ScriptTimers.EVENT, id, 0, 0, world.player());
		});
		timerHud(t.hud());
	}

	/**
	 * HUD 모래시계 (0x1A827C – 0x1A83D4) [확실]: 보임 = 메시지 (+0x1528) · 질문 (+0x152C) · 카드 (+0x1530) 가 없을 때 1 — 표시 타이머의 경과 % · 남은 초 · 보임 중 하나라도
	 * 바뀌면 PS2_Timer_Value / _Seconds 를 쓰고 PS2_Timer_Event = 보임 (FLP_HUDK: 0 → HourGlassMeter "Off", 양수 → GotoFrame2(2 + 경과 %)).
	 * 표시 타이머가 없으면 셋 중 하나라도 0 이 아닐 때 모두 0 + Event 0. ATHN06C 오라클 70 초: PS2 영상 9699.6 "67" … 9760.5 "6" (프레임 92 부터 붉은 깜박임).
	 */
	private void timerHud(int[] v) {
		if (hud == null) {
			return;
		}
		UiVariables vars = hud.variables();
		if (v != null) {
			int vis = messageId < 0 && questionId < 0 && !pickupOpen ? 1 : 0;
			if (vis != timerVisible || v[0] != timerPercent || v[1] != timerSecs) {
				timerVisible = vis;
				timerPercent = v[0];
				timerSecs = v[1];
				vars.set("PS2_Timer_Value", v[0]);
				vars.set("PS2_Timer_Seconds", v[1]);
				vars.event("PS2_Timer_Event", vis);
			}
		} else if (timerVisible != 0 || timerPercent != 0 || timerSecs != 0) {
			timerVisible = timerPercent = timerSecs = 0;
			vars.set("PS2_Timer_Value", 0);
			vars.set("PS2_Timer_Seconds", 0);
			vars.event("PS2_Timer_Event", 0);
		}
	}

	/**
	 * Internal 0x13 FailedScreen (0x1B0B58 → 0x29D958 = 1 → 0x1B3070 → 0x21D808) [확실]: 게임 상태 0xB 면 전역 정지, HUD FailedMenu_Event = 1
	 * (FLP_HUDK FailedMenu "On" + SND_IG_YOUAREDEAD, Pause_Check 12), Menu_Choice · Done = 0, 상태 0xE. 선택 0 = 다시 하기, 1 = 나가기.
	 * 데이터: ATHN06C 오라클이 떨어지면 6.4 초 뒤 (oraclPuzzle-heroDIE) · 구출 중 06B 쪽으로 나가면 (failOracleEntry), 아레나 9 곳, ATHN08B 가족.
	 * 자동 플레이·봇은 메뉴 없이 다시 하기 [엔진 전용].
	 */
	void failedScreen() {
		if (failedMenu || deadMenu > 0 || leaving) {
			return;
		}
		System.out.println("실패 화면 (FailedScreen)");
		if (auto != null || bot != null || debugInput != null || System.getProperty("gow.nodeadmenu") != null || hud == null) {
			restartFailed();
			return;
		}
		paused = true;
		failedMenu = true;
		hud.variables().set("PS2_Menu_Choice", 0);
		hud.variables().set("PS2_Done", 0);
		hud.variables().event("PS2_FailedMenu_Event", 1);
	}

	private void onFailedMenuClosed(Object choice) {
		failedMenu = false;
		paused = false;
		int c = (int) Float.parseFloat(String.valueOf(choice));
		if (c == 1 && pendingEngine != null) {
			leaving = true;
			pendingEngine.scenes().transitionTo(new ShellScene(session.rom)); // 나가기 → 주 메뉴
			return;
		}
		restartFailed();
	}

	/**
	 * 실패 뒤 다시 하기: 원작은 체크포인트 스냅샷 복원 (0x22D440 — 플레이어 블록 0x1DC130 이 타이머 표를 비우고 0x1B15E0 이 GameMgr 블록 0x3368F8 을
	 * 되돌림, 레벨 스크립트 상태 · 영웅 자세 (덩굴에 매달린 채) 도 스냅샷 것). 엔진은 세계·영웅 상태 스냅샷이 없어 이 레벨을 처음 상태로 새로 읽고 (레벨 기억
	 * 지움) 마지막 체크포인트 자리·방향에 둔다 — 떨어진 오라클 · 시작된 아레나처럼 실패가 바꾼 세계를 되돌리려면 세계를 새로 시작해야 하고, 오라클
	 * 체크포인트 (a06Mp03Done) 는 덩굴 위라 진행 중 LevelData 로 되살리면 떨어지자마자 heroFAILS 로 다시 실패한다 [근사: 이 레벨 진행이 처음으로].
	 */
	private void restartFailed() {
		hero.player.onCheckpointRestart(hero.player.hp);
		world.host().timers.reset();
		if (pendingEngine == null || leaving) {
			respawnAtCheckpoint();
			return;
		}
		session.levelMemory.remove(levelName.toUpperCase(java.util.Locale.ROOT));
		if (auto != null) {
			auto.died(hero.ctl.position);
			session.autoCarry = auto;
		}
		leaving = true;
		System.out.printf(java.util.Locale.ROOT, "실패 → %s 다시 (체크포인트 %.0f, %.0f, %.0f)%n", levelName, checkpoint.x, checkpoint.y, checkpoint.z);
		pendingEngine.scenes().transitionTo(at(session, levelName, new float[]{checkpoint.x, checkpoint.y, checkpoint.z}, checkpointYaw));
	}

	/** 게임 씬의 HUD 바인딩: 미터 자동 표시, 마지막으로 본 값은 세션 것 (레벨을 넘어 유지). */
	private HudBinding newHudBinding() {
		HudBinding b = new HudBinding();
		b.castable = hero.magic::has;
		b.autoMeter = true;
		b.seen = session.meterSeen;
		return b;
	}

	/**
	 * 원작 상태 13 PowerUpMenu 종료 (PSMDone): 메뉴가 바꾼 HUD 값을 진행도로 되돌려 쓴다 (0x1D7700 투자 오브 복사,
	 * Upgrade_LoadWad 0x1D7890 레벨 복사). 블레이드 레벨이 바뀌면 HeroRig 가 다음 그리기에서 R_WPN 을 그 레벨 것으로 바꾼다
	 * ({@code swapBlades}, 43 §17). 마법 WAD (R_LGHTN&lt;단계&gt; 등) 는 MagicFx 가 시전 때 그 단계 것을 등록한다 ({@code stage}).
	 */
	private void onPowerUpClosed() {
		UiVariables v = hud.variables();
		PlayerState ps = hero.player;
		ps.meterPokes++; // 0x1D7700 (HUD → 진행도 복사) 끝에 +0x1C0 = 1
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
			respawnAtCheckpoint();
		}
	}

	@Override
	public void update(Engine e, float dt) {
		screenFade.tick(); // 스크립트 화면 페이드 0x1AF648 (프레임마다, 43 §16)
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

	/** 카메라 흔들림 (이벤트 5) · 전역 시간 배율 (이벤트 7: 0x337C1C 남은 시간 (실시간), 0x337C20 배율). */
	private final devple.gow.engine.render.CameraShake shake = new devple.gow.engine.render.CameraShake();
	private int shakeSerial;
	/** 전투 카메라 이벤트 23 (처형 클로즈업 goCamera, 30 문서 §9.3). */
	private devple.gow.engine.combat.CombatCamera combatCam;
	private float warpTimer, warpScale = 1;

	/** 전투원의 흔들림·전역 시간 이벤트를 장면에 연결 (0x206030: 영웅이든 적이든). */
	private void bindPresentation(devple.gow.engine.combat.Combatant c) {
		c.onCameraShake(i -> shake.start(orbTweaks, i));
		c.onCameraFrame(b -> {
			if (cameraRig != null) {
				cameraRig.frameTarget(b); // 잡기 처형 클로즈업 (이벤트 11)
			}
		});
		c.onGlobalTime((a, b) -> {
			warpTimer = a;
			warpScale = b;
		});
	}

	private void updateTick(Engine e, float real) {
		// 0x204AB0: 타이머 > 0 이면 실시간으로 줄이고 배율 = max(b, 0.001), 아니면 1 → 게임 dt (0x17DA78) 에 곱함 [근사: HUD·소리도 같이 느려짐]
		float dt = real;
		if (warpTimer > 0) {
			warpTimer -= real;
			dt = real * Math.max(warpScale, 0.001f);
		}
		pendingEngine = e;
		if (session.openingPending && levelName.equals(GameFlow.START_LEVEL)) {
			session.openingPending = false;
			playMovie(e, "Semo01"); // 새 게임 오프닝
			return;
		}
		time += dt;
		devple.gow.engine.mod.Plugins.frame(dt); // 플러그인 매 프레임 훅 (49 문서 §13)
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
			for (Enemy en : enemies) { // 적 위치·내비 상태 (그물·벽 위 적 확인)
				if (!en.fighter().dead()) {
					Vector3f ep = en.fighter().position();
					var ec = en.fighter().combat();
					System.out.printf("[디버그]   적 %d (%.0f, %.0f, %.0f) %s HP %.1f 기술 %s %s 무적=%s 잡기=%s%n", en.kind().ai1(), ep.x, ep.y, ep.z,
							en.fighter().nav().mode(), en.fighter().hp(), ec.move() == null ? "-" : ec.move().id(), ec.clipName(),
							en.fighter().invulnerable(), en.fighter().grabbable());
				}
			}
		}
		if (System.getProperty("gow.debug.group") != null && ((int) (time * 60)) % 15 == 0) {
			groupDebug();
		}
		if (globalIdle()) {
			// 전역 정지 (Idle) 동안 HUD 리셋 (Progress_UpdateHud 0x1D71A0 → 0x1BA150(hud, 1)): 미터를 내리고 갱신하지 않는다 —
			// ATHN01A 시작 연출 13 초 동안 HUD 없음. 조작이 풀린 뒤 미터는 값이 바뀌거나 싸울 때만 다시 뜬다 (HudBinding.meter;
			// 새 게임은 마지막으로 본 값이 0 이라 첫 프레임에 뜬다)
			if (!hudHidden) {
				hudHidden = true;
				hudBinding.hideMeter(hud.variables());
				hud.variables().event("PS2_HitCounter_Event", 0);
			}
		} else if (!powerUp) {
			if (hudHidden) {
				hudHidden = false;
				hudBinding = newHudBinding(); // 값 다시 (미터는 내려간 채)
			}
			hudBinding.update(hero.player, hud.variables());
			hudBinding.meter(hero.player, hud.variables(), hero.combat.active(), real);
			hitCounterHud();
		}
		if (debugInput != null && (questionId >= 0 || powerUp || pickupOpen)) {
			debugInput.advance(dt); // 질문·업그레이드 메뉴·아이템 카드 중에는 게임 틱이 멈추므로 스크립트 입력을 여기서 진행해 HUD 로 (검증용, 43 §17)
			hud.update(debugInput);
		} else if (auto != null && (questionId >= 0 || pickupOpen || saveAsk || saveFlow != null)) {
			// 자동 플레이: 질문·카드·세이브 질문·메모리카드 화면은 ○ 로 기본 선택지, 메모리카드 예/아니오 (기본 아니오) 는 ↑ 뒤 ○ (검증용)
			hud.update(auto.menu(dt, saveFlow != null && saveFlow.yesNoScreen()));
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
		if (messageId >= 0) {
			hud.variables().typeTick(dt); // 타자 경과 (0x29CCD4)
		}
		if (messageWait) {
			messageWaitTime += dt;
			messagePressTime += dt;
			if (messageButtonLate && messageWaitTime > 2.5f) { // 0x1B8EF8: 2.5 초 (0x40200000) 뒤 버튼 표시
				messageButtonLate = false;
				hud.variables().event("PS2_MessageTemplate_Event", 10);
			}
			// AUTO 검증·봇은 쪽마다 1.5 초 읽고 ○ — 타자 중이면 끝 표시와 넘기기를 한 번에 [엔진 전용]
			boolean botOk = (auto != null || bot != null) && messagePressTime > 1.5f && !messageButtonLate;
			if (botOk) {
				hud.variables().typeComplete();
			}
			if (botOk || e.input().pressed(PadButton.CIRCLE) || lastRawInput != null && lastRawInput.pressed(PadButton.CIRCLE)) {
				messagePressTime = 0;
				// 0x1B8E40: 첫 ○ 는 글자를 다 보이게만 (0x148360), 0x1B8E50: 500–599 의 버튼이 아직 안 보이면 (+0x1578) 무시, 그 뒤 다음 쪽 / 닫고 1040
				if (hud.variables().typeComplete() && !messageButtonLate) {
					nextMessagePage(true);
				}
			}
		}
		if (messageTimer > 0 && (messageTimer -= dt) <= 0) {
			if (messagePages.isEmpty()) {
				closeMessage(messageSeconds <= 0); // 표에 없는 번호 [엔진: 원작은 "Msg %d" 를 띄우고 버튼을 기다림]
			} else {
				nextMessagePage(false); // 시간 메시지: 쪽마다 같은 시간, 마지막 쪽 뒤 1040 없이 닫힘 (0x1B8D44 – 0x1B8DEC)
			}
		}
		if (saveFlow != null) {
			updateSaveFlow(dt);
		}
		if (saveCloseTimer > 0 && (saveCloseTimer -= dt) <= 0) {
			saveCloseTimer = 0;
			if (saveReask) {
				askSave();
			} else {
				hud.variables().event("PS2_SaveGame_Event", 0); // FLP: SaveGameMenu Animate_Off, Pause_Check 10 → 0
				paused = false;
			}
		}
		if (deadMenu > 0) {
			updateDeadMenu(dt);
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
		boolean use = e.input().pressed(PadButton.USE) || lastInput != null && lastInput.pressed(PadButton.USE);
		if (use && !hero.combat.active() && nearSavePoint()) {
			askSave();
			return;
		}
		if (questionId >= 0 || pickupOpen) {
			return; // 질문 메뉴·아이템 카드가 열려 있는 동안 게임은 멈춘다 (원작 HUD Pause_Check 5 / 8, 입력은 HUD FLP 가 받음)
		}
		// 전투 대기 IDLECOMBAT (M80, ANY_INPUT → 끝) 은 막지 않는다 — 원작 0x1DC3F8 은 전투 기술을 보지 않고 (전역 정지·+0x10·0x29DC70 만),
		// 엔진은 R2 가 같은 틱에 M80 을 끝내기 전에 여기를 지나 싸운 뒤에는 R2 가 먹지 않았다 (ATHN02A 아테나 석상) [근사: 공격 중은 여전히 막음]
		if (use && (!hero.combat.active() || hero.combat.move() != null && hero.combat.move().id() == 80)) {
			world.postEvent(SceneNav.EVENT_USE, 0, 0, 0, world.player()); // 버튼 안내 센서 (mode 2 = 볼륨 안일 때만 반응)
			promptCleared = true; // 0x1DC3F8: 1024 뒤 아이콘 슬롯 1 지움 — 다시 켜는 것은 볼륨 진입 (0x1AAC88) 뿐
		}
		if (e.input().pressed(PadButton.SELECT) || debugInput != null && debugInput.pressed(PadButton.SELECT)) { // gow.script 도 (메뉴 검증, 43 §17)
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
		EnemyFighter target = nearestEnemy();
		if (bot != null) {
			bot.steer(hero.ctl.position, target != null ? target.pos : null, inputFrame.yaw(camYaw), dt);
			in = bot;
		}
		if (auto != null) {
			auto.redOrbs = hero.player.redOrbs;
			float[] tp = auto.takeTeleport();
			if (tp != null) {
				float yaw = hero.ctl.yaw;
				respawn(new Vector3f(tp[0], tp[1], tp[2]));
				hero.ctl.yaw = yaw;
			}
			// HP 32767 = 부술 수 없는 소품 (ATHN01F 침대 쌍둥이 aoxpt 305) — 싸울 대상이 아니다
			float[] obj = boss != null ? boss.autoObjective() : null;
			EnemyFighter at = target;
			if (at != null && boss != null && boss.autoIgnores(at)) {
				at = enemies.stream().map(Enemy::fighter).filter(f -> !f.dead() && !boss.autoIgnores(f))
						.min(java.util.Comparator.comparingDouble(f -> f.pos.distanceSquared(hero.ctl.position))).orElse(null);
			}
			if (hero.player.owned(Weapon.MEDUSA)) {
				// 석상은 지속 (AI1 +0x40, 5 초) 안에 부숴야 센다 — 가까운 석상부터 [엔진 전용 봇]
				EnemyFighter stone = enemies.stream().map(Enemy::fighter).filter(f -> !f.dead() && f.petrified()
						&& f.pos.distance(hero.ctl.position) < 400).min(java.util.Comparator.comparingDouble(
						f -> f.pos.distanceSquared(hero.ctl.position))).orElse(null);
				// 광선이 켜져 있으면 끄지 말고 옆의 아직 돌이 아닌 적으로 넘긴다 (한 번 들어 모두 얼림 — 사람 플레이처럼). 하나씩 얼려 부수면 나머지 둘이
				// 그동안 영웅을 넘어뜨려 (hitBall/hitLand 3–4 초) 6 번째 파괴가 늦었다 [엔진 전용 봇]
				// 다음 대상은 몸 정면 ±60° 안만 (USEHEAD 조준은 ±80°, 봇은 광선 중 몸을 돌리지 않는다)
				float fx = (float) -Math.sin(hero.ctl.yaw), fz = (float) -Math.cos(hero.ctl.yaw);
				EnemyFighter fresh = hero.magic.medusa.beamOn() ? enemies.stream().map(Enemy::fighter).filter(f -> !f.dead() && !f.petrified()
						&& f.freezeMax() < 60000 && f.pos.distance(hero.ctl.position) < devple.gow.engine.input.BotInput.GAZE_SWEEP
						&& inFront(f.pos, fx, fz, 0.5f)).min(java.util.Comparator.comparingDouble(
						f -> f.pos.distanceSquared(hero.ctl.position))).orElse(null) : null;
				if (fresh != null) {
					at = fresh;
				} else if (stone != null) {
					at = stone;
				}
			}
			auto.unstick = at != null && boss != null && at != boss.boss();
			// 잡기를 기다리는 소품 (HP 32767, 자세 태그 설정 중 — ATHN01F 쌍둥이 M69 SET_1B0 305: 영웅 GRAB → 846) 이 가까우면 목표에서 ○ [엔진 전용]
			auto.grabCue = enemies.stream().map(Enemy::fighter).anyMatch(f -> !f.dead() && f.maxHp() >= 32767
					&& f.combat().stanceTag() >= 0 && f.pos.distance(hero.ctl.position) < 120);
			// 석상은 잡지 않는다 (예전 봇은 석상에 ○ → 200 grab → 204 grabReject 로 1 초를 버렸다, ATHN02D) [엔진 전용 봇]
			auto.grabTarget = at != null && at.maxHp() < 32767 && !at.petrified() && waitsForGrab(at);
			auto.stoneTarget = at != null && at.petrified();
			// 메두사의 시선 (원작 조작 L2 + □, 메시지 595): 보유 + MP + 석화 가능한 대상 (게이지 < 60000, 메두사·갑옷병 면역) 이 아직 돌이 아님 [엔진 전용 봇]
			boolean gazeOwned = hero.player.owned(Weapon.MEDUSA);
			auto.mp = gazeOwned ? hero.player.mp : -1;
			// 광선이 대상을 못 맞히면 (원뿔·시선 밖 — 동결 게이지가 0.4 초 동안 그대로) 떼고 다시 다가가 몸을 돌린다: 예전엔 GAZE_MAX 동안 헛되이
			// 켜 두었다 (MP·시간 낭비) [엔진 전용 봇]
			if (hero.magic.medusa.beamOn() && at != null && at == gazeWatch && at.freezeMeter() >= gazeMeter - 1e-3f) {
				gazeStall += dt;
			} else {
				gazeStall = 0;
			}
			gazeWatch = at;
			gazeMeter = at != null ? at.freezeMeter() : 0;
			auto.gaze = gazeOwned && hero.player.mp >= AutoPlay.GAZE_MP && at != null && !at.dead() && !at.petrified()
					&& at.freezeMax() < 60000 && !grab.active() && gazeStall < 0.4f;
			auto.selectGaze = gazeOwned && hero.player.selectedMagic != Weapon.MEDUSA;
			// 광선 조준 (MedusaGaze.aim = 영웅 combatant 대상) 은 봇이 고른 대상 — 가장 가까운 적이 석상이면 광선이 석상 쪽으로 가 산 미노타우로스를
			// 못 맞혔다 [엔진 전용 봇]
			botAim = gazeOwned && at != null && !at.dead() ? at : null;
			in = obj != null ? auto.reach(hero.ctl.position, obj, inputFrame.yaw(camYaw), dt)
					: auto.steer(hero.ctl.position, at != null && at.maxHp() < 32767 ? aimPoint(at) : null, boss != null, inputFrame.yaw(camYaw), dt);
			if (auto.done()) {
				e.window().requestClose();
			}
		}
		lastRawInput = in == e.input() ? null : in;
		if (globalIdle()) {
			in = IDLE_INPUT; // 스크립트 Idle(true)·버튼 대기 메시지: 입력 없음
		}
		lastInput = in == e.input() ? null : in;
		if (time < autoRun) {
			in = new Input(e.window().handle()) {
				@Override
				public float leftY() {
					return 1f;
				}
			};
		}
		bodyTick++;
		// 입력 기준 = 지금 카메라, 단 컷 뒤 스틱을 같은 쪽으로 밀고 있으면 컷 전 카메라 (0x1DD1E8). 전역 정지가 풀리면 유지 지우고 2 번의 선택 동안 컷 무시 (0x1BF080 → 0x1EF2F0(2))
		boolean idleNow = globalIdle();
		if (wasIdle && !idleNow) {
			inputFrame.release(2);
		}
		wasIdle = idleNow;
		float inYaw = inputFrame.update(camYaw, in.leftX(), in.leftY());
		if (System.getProperty("gow.debug.inyaw") != null && bodyTick % 30 == 0) {
			System.out.printf(java.util.Locale.ROOT, "[입력] t=%.1f stick (%.2f, %.2f) cam %.1f ref %.1f hold %d grab %s idle %s%n", time, in.leftX(), in.leftY(),
					Math.toDegrees(camYaw), Math.toDegrees(inYaw), inputFrame.hold(), grab.active(), globalIdle());
		}
		if (!grab.active()) {
			hero.combat.setTarget(botAim != null ? botAim : target);
			heroAim = selectHeroTarget(in, inYaw); // 스틱 월드 방향 = 입력 기준 (컷 뒤 유지 포함)
			hero.autoAim = true;
			hero.update(in, inYaw, dt, heroAim != null ? aimCenter(heroAim) : null);
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
		if (neighbourBridge != null && neighbourBridgeWorld != null && nav.neighbour() == neighbourBridgeWorld) {
			neighbourBridge.tick(); // 이웃 스크립트가 만든 적의 피해 1039
		}
		if (target != null && (boss == null || !boss.claimsGrab(target))) {
			Enemy te = enemies.stream().filter(x -> x.fighter() == target).findFirst().orElse(null);
			boolean was = grab.active();
			EnemyFighter held = grab.partner(); // 처형 대상 = 잡고 있던 적 (끝나는 틱의 가장 가까운 적이 아님)
			Input gi = in;
			if (boss != null) {
				gi = boss.minigameInput(in, grab.timed(), grab.qte());
			} else if (auto != null && grab.active()) {
				autoPad.drive(grab.timed(), grab.qte()); // AUTO 검증: 결합 미니게임 (ATHN01F 침대 846–851 TimedPress) 자동 입력 [엔진 전용]
				gi = autoPad;
			}
			grab.update(gi, target, te != null ? te.kind().tweaks() : null, dt);
			if (grab.active()) {
				grabWalls();
			}
			// HP 32767 소품 (ATHN01F 쌍둥이) 은 미니게임 성공 (853 MAIXPTBEDMOUNTWIN → 875) 뒤에도 그대로 — 처형이 아니다
			// 예전엔 끝나는 틱의 target (가장 가까운 적) 을 죽였다: ATHN02D 여왕 머리 뜯기 (보스 → claimsGrab, 이 경로는 여왕 사망 뒤 영웅 Idle 동안
			// 결합이 남아 있음) 가 끝나는 순간 막 스폰된 미노타우로스 (68.88 초) 가 HP 0 → 0.5 초 뒤 MinoDestruction1 + 7 초 Respawn 이 한 번 더 돌았다
			if (was && !grab.active() && grab.executionSucceeded() && held != null && held.maxHp() < 32767) {
				held.hp = 0; // 처형 성공 → 사망 처리 (원작은 처형 기술의 피해/스크립트) [근사]
			}
		}
		updateThrowTarget(dt);
		if (minigameIcon.show(hud.player(), minigameIconIndex())) {
			session.sound.play("SND_ICON");
		}
		Vector3f p = hero.ctl.position;
		Vector3f contact = hero.contactPoint(new Vector3f()); // 스크립트 볼륨 접촉점 = 영웅 pelvis (HeroRig.contactJoint, 0x1ADD74)
		nav.update(dt, p, contact);
		for (Enemy en : enemies) { // 디딘 동적 발판에 실려 감 (원작 생물 +0x24C = 바닥 질의가 맞은 발판 객체, 32 문서, 30 §6.6) — ATHN02A 부두로 들어오는 배 위 아테나 석상
			EnemyFighter ef = en.fighter();
			if (!ef.dead() && ef.nav() != null && ef.nav().mode() == devple.gow.engine.combat.EnemyNav.Mode.GROUND) {
				nav.carry(ef.pos, true);
			}
		}
		chests.update(dt);
		updateAnimatorEffects(dt);
		updateSkyEffects();
		poseRopes();
		for (var b : nav.platformBodies()) {
			if (b.anim() != null && b.scripted || b.follows()) { // 정지 CDV 오브젝트의 반복 act 는 렌더가 따로 돌린다 (반복 재생 배치). 부모 링크로 옮겨진 몸은 그 자세로
				level.poseInstance(b.owner, b.joints(), b.hidden()); // 움직인 발판·문·회전방은 애니 자세로 (관절 숨김 포함: DMC 결계)
			}
		}
		for (var b : nav.neighbourPlatforms().bodies()) { // 다른 슬롯 레벨 문·발판 (43 §13.8)
			if (b.anim() != null && b.scripted || b.follows()) {
				otherPosed.put(b.owner, new Object[]{b.joints(), b.hidden()});
			}
		}
		level.setMaterialTimes(nav.navWorld.platforms().materialTimes()); // 스크립트 재질 애니 ("$", Platforms.MatClock)
		otherMaterialTimes = nav.neighbourPlatforms().materialTimes();
		breakables.update(hero.combat, world, this::onBreak);
		if (otherBreakables != null && nav.neighbour() != null) {
			otherBreakables.update(hero.combat, nav.neighbour(), this::onBreakOther);
		}
		if (!archerAims.isEmpty()) {
			updateArcherAims(dt);
		}
		if (!arrows.shots().isEmpty()) {
			arrows.update(dt, new ArrowShots.Targets(breakables, world, this::onBreak, enemies.stream().map(Enemy::fighter).toList(), hero.combat,
					col, otherCol, nav.navWorld.platforms(), nav.neighbourPlatforms(), magicFx != null ? magicFx.effects() : null,
					hero.hittable(), this::enemyArrowHit, otherBreakables, nav.neighbour(), this::onBreakOther));
		}
		combatCameraTick();
		cameraRig.setHeroState(heroStateBits(), enemies.stream().anyMatch(en -> !en.fighter().dead() && en.fighter().brain() != null
				&& en.fighter().brain().state() == devple.gow.engine.combat.AiBrain.State.COMBAT));
		cameraRig.update(dt, p, hero.ctl.yaw, !hero.ctl.grounded);
		inputFrame.selected(cameraRig.cutThisTick() && !globalIdle()); // 0x1EF310 → 0x1DCE80 (전역 정지 0x1BF108 중엔 없음)
		var sr = session.sound.shakeRequest(); // SND_SHAKE_* (DPMS 0x16 모터 1) → 0x228C70: 새 id 면 0x1F09C8 (진행 중이면 무시) + 배율 0x1F0A28 (30 문서 §9.2)
		if (sr != null && sr.serial() != shakeSerial) {
			shakeSerial = sr.serial();
			shake.start(orbTweaks, sr.template());
			shake.scale(sr.scale());
			if (System.getProperty("gow.debug.cam") != null) {
				System.out.printf(java.util.Locale.ROOT, "[흔들림] 소리 템플릿 %d 배율 %.2f t=%.1f%n", sr.template(), sr.scale(), time);
			}
		}
		shake.update(dt, cam);
		session.sound.setUnderwater(cameraRig.underwater()); // 수중 카메라 → 소리 수중 처리 (29 문서 §10)
		world.tick(dt, contact.x, contact.y, contact.z);
		scriptTimers(dt);
		orbPickups.update(dt, p, hero.player.hp > 0, orbSink);
		hero.tickBlades(dt); // 블레이드 사슬 (0x1FDB18): 이번 틱 자세가 정해진 뒤, 블레이드 GO 앵커보다 먼저
		magicFx.update(hero.magic, p, hero.ctl.yaw, enemies.stream().map(Enemy::fighter).toList(), dt, cam.eye);
		for (BladeChain.Side s : hero.chain.sides) { // 블레이드 벽 불꽃 (0x1FAFC8 → 0x202248, 45 문서 §3.7)
			if (s.wallHit) {
				magicFx.bladeSpark(s.index, s.wallSheetFx, s.wallPoint, s.wallNormal, (n, at) -> session.sound.play(n, at));
			}
		}
		magicFx.weaponWorld(hero.combat, (n, at) -> session.sound.play(n, at)); // 무기-월드 베기 Slice Hit (40 문서 §6)
		surfaceDamage(dt);
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

	/** 영웅 GRAB 200 의 자세 태그 (SET_1B0 200) — 적 기술의 피격 분기가 이 태그로 잡기 미니게임에 들어간다. */
	private static final int HERO_GRAB_TAG = 200;

	/**
	 * 자동 플레이: 대상의 지금 기술이 영웅 ○ GRAB 피격 (태그 200) 으로 다른 기술에 들어가는 분기를 가짐 = 잡기를 기다림
	 * (선창 히드라 기절 101/107 → 830 BHAENC1GRAB) [엔진 전용].
	 */
	private static boolean waitsForGrab(EnemyFighter f) {
		var m = f.combat().active() ? f.combat().move() : null;
		// 분기의 잡기 조건도 맞아야 한다 (Combatant 분기 점수: 0x40 = 무시, 아니면 +0x1B8 잡기 가능 == 0x10). 메두사 여왕은 모든 기술에
		// GRAB(200) → 260 MAIMEWGRAB (0x148212 = 잡기 원 필요) 이 있어, 예전 봇은 원이 뜨기 전부터 ○ 만 눌러 204 GRABREJECT 를 반복했다
		return m != null && m.branches().stream().anyMatch(b -> b.input() == devple.gow.engine.combat.CombatInput.EV_HIT
				&& b.matchB() == HERO_GRAB_TAG && b.target() >= 0
				&& ((b.flags() & 0x40) != 0 || ((b.flags() & 0x10) != 0) == f.grabbable()));
	}

	/**
	 * 자동 플레이가 다가갈 점: 보통 적 위치, 제자리 적 (선창 히드라 — 뿌리가 물속 바닥) 은 영웅에 가장 가까운 CDV 구 (머리) [엔진 전용].
	 */
	private Vector3f aimPoint(EnemyFighter f) {
		if (!f.stationary) {
			return f.pos;
		}
		java.util.List<float[]> sp = f.pushSpheres();
		if (sp == null || sp.isEmpty()) {
			return f.pos;
		}
		float[] best = null;
		float bd = Float.MAX_VALUE;
		for (float[] s : sp) {
			float d = new Vector3f(s[0], s[1], s[2]).distanceSquared(hero.ctl.position);
			if (d < bd) {
				bd = d;
				best = s;
			}
		}
		return new Vector3f(best[0], best[1], best[2]);
	}

	/**
	 * -Dgow.debug.group: 무리 전투 요약 (0.25 초마다) [엔진 전용 QA] — 전투 중 적 수, 토큰 보유, 공격 중 (비용 있는 공격 / 전체),
	 * 대상 슬롯 예산·공격 슬롯 A/B, 적끼리 최소 거리, 영웅까지 거리 목록.
	 */
	private void groupDebug() {
		int combat = 0, tok = 0, atk = 0, costed = 0;
		float minPair = Float.MAX_VALUE;
		StringBuilder ds = new StringBuilder();
		devple.gow.engine.combat.AiTargets.Slot slot = null;
		List<EnemyFighter> live = enemies.stream().map(Enemy::fighter).filter(f -> !f.dead() && f.brain() != null).toList();
		for (EnemyFighter f : live) {
			var b = f.brain();
			if (b.state() != devple.gow.engine.combat.AiBrain.State.COMBAT) {
				continue;
			}
			combat++;
			slot = b.slot();
			tok += b.hasToken() ? 1 : 0;
			var a = b.currentAttack();
			if (a != null) {
				atk++;
				costed += a.costA() + a.costB() > 0 ? 1 : 0;
			}
			ds.append(String.format(" %.0f%s%s", f.pos.distance(hero.ctl.position), b.hasToken() ? "T" : "",
					a == null ? (f.combat().active() ? "m" : "") : a.costA() > 0 ? "A" : "a")); // m = 공격 아닌 기술 (피격 반응·잡힘 등)
			for (EnemyFighter g : live) {
				if (g != f && g.brain().state() == devple.gow.engine.combat.AiBrain.State.COMBAT) {
					minPair = Math.min(minPair, (float) Math.hypot(f.pos.x - g.pos.x, f.pos.z - g.pos.z));
				}
			}
		}
		if (combat > 0) {
			System.out.printf("[무리 %.2f] 전투 %d 토큰 %d 공격 %d(비용 %d) 예산 %d A %d B %d 최소간격 %.0f 거리%s%n", time, combat, tok, atk, costed,
					slot != null ? slot.tokenBudget() : -1, slot != null ? slot.usedA() : -1, slot != null ? slot.usedB() : -1,
					minPair == Float.MAX_VALUE ? -1 : minPair, ds);
			if (((int) (time * 60)) % 300 == 0 && slot != null) {
				System.out.print("[무리 지도]\n" + slot.grid.map(c -> live.stream().anyMatch(f -> f.brain().cell() == c)));
			}
			if (((int) (time * 60)) % 60 == 0) {
				for (EnemyFighter f : live) {
					var c = f.combat();
					System.out.printf("[무리]   %s 기술 %s%n", f.brain().debugString(), c.active() ? c.move().id() + " " + c.clipName() : "-");
				}
			}
		}
	}

	/** 영웅 대상 (combatant +0x80) — 자동 조준이 향하는 적 ({@link devple.gow.engine.combat.HeroTargeting}, 0x1D9CB0). */
	private EnemyFighter heroAim;

	/**
	 * 영웅 대상 고르기 0x1D9CB0 — 스틱 방향 ±60° 안 |d|/c² 최소, 스틱 ≤ 0.8 이면 이전 대상 유지. 예전 엔진은 스틱과 무관하게 가장 가까운 적을
	 * 대상으로 삼고 기술 시작 때만 즉시 돌았다 → 무리 속에서 스틱으로 다른 적을 고를 수 없었다.
	 */
	private EnemyFighter selectHeroTarget(Input in, float camYaw) {
		Vector3f hp = hero.ctl.position;
		java.util.List<EnemyFighter> cands = new ArrayList<>();
		for (Enemy en : enemies) {
			EnemyFighter f = en.fighter();
			if (!f.dead() && !f.bodiless && aimCenter(f).distance(hp) <= devple.gow.engine.combat.HeroTargeting.RANGE + f.bodyRadius()) {
				cands.add(f); // 구 질의 = 몸이 범위 구에 닿으면 후보 [근사: 몸 반경만큼 여유]
			}
		}
		float[] st = devple.gow.engine.combat.HeroTargeting.stickWorld(in.leftX(), in.leftY(), camYaw);
		return devple.gow.engine.combat.HeroTargeting.select(heroAim, cands, GameScene::aimCenter, hp, st[0], st[1]);
	}

	/** 생물 중심 (vslot 0xE8) ≈ 발 + 키 / 2 [근사]. */
	private static Vector3f aimCenter(EnemyFighter f) {
		return new Vector3f(f.pos).add(0, f.bodyHeight() * 0.5f, 0);
	}

	private EnemyFighter nearestEnemy() {
		EnemyFighter best = null;
		float bd = Float.MAX_VALUE;
		for (Enemy en : enemies) {
			if (en.fighter().dead() || en.fighter().bodiless || en.fighter().targetWeight <= 0) {
				continue; // 몸 CDV 없는 말하는 석상 (ATHN02A aoxsa) · 조준 가중 0 인 NPC (오라클 310 · 무덤지기 309) 는 싸울 대상이 아니다
			}
			float d = en.fighter().pos.distanceSquared(hero.ctl.position);
			if (d < bd) {
				bd = d;
				best = en.fighter();
			}
		}
		return best;
	}

	/** 부서진 석상의 스크립트 제거 (메시지 6) 시점, 초 — 1046 전달 (world.tick) 뒤. */
	public static final float SHATTER_DESTROY = 0.05f;

	private void updateEnemies(float dt) {
		collectBodies();
		for (Enemy en : enemies) {
			EnemyFighter f = en.fighter();
			boolean wasDead = f.dead();
			f.update(dt);
			// 바닥: 계단 높이 안이면 붙이고, 더 아래면 중력으로 떨어진다 (높은 스폰 지점 — ATHN08B 분신 파도 등) [근사: 원작 적 낙하 물리]
			// 질의 시작 = 발 + 6.4 (바닥 질의 0x1E3298 선분 시작) — 발보다 6.4 넘게 높은 면으로 올라 붙지 않는다. 예전 STEP 14 는 ATHN01F 쌍둥이
			// (y 96, 루트 = 바닥 기준, 골반 +22) 를 매트리스 윗면 107 로 올려 이불이 공중에 뜨고 twinscam1 / TwinCamLeft 화면 위로 벗어났다 (30 §6.6)
			float g = f.nav().world() != null ? Float.NaN : ground(f.pos.x, f.pos.y + devple.gow.engine.combat.EnemyNav.FLOOR_FOOT, f.pos.z, 4000); // EnemyNav 가 직접 처리
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
			if (!f.dead() && f.nav().fallDepth(f.pos) > VOID_FALL) {
				f.hp = 0; // 바닥 없는 곳 (배 밖 바다) 으로 떨어짐 → 사망 = 스폰 엔티티 Respawn·DestructionSensor 가 센다 (영웅 규칙과 같음) [근사]
			}
			if (en.actor() != null) {
				en.actor().moveTo(f.pos.x, f.pos.y, f.pos.z);
			}
			// 부서진 석상은 곧바로 (1046 Stone Kill 이 전달된 다음 틱): ATHN02D EventSmash1 (1046) 이 LevelData5 = 참으로 MinoDestruction1
			// (제거 센서, 조건 LevelData5 == 거짓) 을 막고 0.51 초 뒤 HulkSmashy 가 푼다 — 제거가 그 창 안이어야 한 번만 센다. 예전 0.5 초 뒤 제거는
			// 0.5 초 안에 둘을 부수면 앞 석상의 HulkSmashy 가 뒤 석상의 창을 닫아 두 번 세어 (LevelData3 ≠ 4) 문이 영영 안 열렸다
			// [추정: 원작 제거 시점 — 0x265C48 의 0x405 처리가 0x416 을 보내고 0x26B5D0 로 정리]
			boolean shatterGone = f.shattered() && f.deadTime() > SHATTER_DESTROY;
			if (f.dead() && f.deadTime() == 0 && !wasDead || (f.dead() && en.actor() != null && !en.actor().destroyed()
					&& (f.deadTime() > 0.5f || shatterGone))) {
				if (en.actor() != null && !en.actor().destroyed()) {
					worldOf(en.actor()).destroy(en.actor()); // DestructionSensor (예: 튜토리얼 메시지) — 이웃 스크립트가 만든 적은 이웃 세계에서
					String emitter = en.kind().assets().wad().name().replace(".WAD", "").substring(2) + " Death";
					int so = f.shattered() && f.statueShown() ? f.statueOrbEmitter(!hero.ctl.grounded) : -1;
					if (so >= 0) {
						// 부서진 석상: 석상 Breakable 의 OrbEmitter (517 → 771 "MIN Shatter", 공중 772) — 0x1DDDF0 [확실: 템플릿], 소유자 "MIN Death" 대신 [추정]
						emitOrbs(orbTweaks.findNode(String.format("/TweakTemplates/OrbEmitter/%03d/", so)), f.pos);
					} else {
						emitOrbs(orbTweaks.findTemplate("/TweakTemplates/OrbEmitter/", emitter), f.pos); // 적 사망 0x1DDDF0
					}
				}
			}
		}
		enemies.removeIf(en -> {
			// 죽은 뒤 이어지는 기술 (선창 히드라 150 BHAENC1GRAB @0.83 — 830 이 끝난 뒤에야 시작) 은 끝까지 [엔진 전용 정리 시점]
			if (en.fighter().dead() && en.fighter().deadTime() > 4f && (!en.fighter().combat().active() || en.fighter().deadTime() > 30f)) {
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
	private devple.gow.engine.combat.CombatCamera combatCamera() {
		if (combatCam == null) {
			combatCam = new devple.gow.engine.combat.CombatCamera(session.rom == null ? null
					: devple.gow.tools.content.Content.file(session.rom, devple.gow.tools.content.Region.of(session.rom).perm()));
		}
		return combatCam;
	}

	/**
	 * 이벤트 23 창이 있는 전투원 (영웅 → 적 순, 먼저 잡은 쪽 유지) 의 goCamera — 몸 회전 (0x205280) 과 카메라 덮어쓰기 (0x1F46D0).
	 */
	private void combatCameraTick() {
		if (System.getProperty("gow.nocombatcam") != null) {
			return; // 비교용: 이벤트 23 전투 카메라 끔
		}
		var cc = combatCamera();
		float w = cameraRig.combatWeight();
		float[] y = {hero.ctl.yaw};
		boolean on = cc.update(hero, hero.combat, hero.ctl.position, y, hero.ctl.animator(), 1f, cam.eye, w);
		hero.ctl.yaw = y[0];
		for (Enemy en : enemies) {
			if (on) {
				break;
			}
			EnemyFighter ef = en.fighter();
			y[0] = ef.yaw;
			on = cc.update(ef, ef.combat(), ef.pos, y, ef.animator(), ef.scale, cam.eye, w);
			ef.yaw = y[0];
		}
		if (!on && cc.owner() != null) {
			cc.clear(); // 잡은 전투원이 사라짐
		}
		var f = on ? cc.frame() : null;
		if (f != null) {
			cameraRig.combatCamera(f.aim(), f.radius(), f.up());
			if (System.getProperty("gow.debug.cam") != null && (time % 0.5f) < 1 / 60f) {
				System.out.printf(java.util.Locale.ROOT, "[전투 카메라] %s w=%.2f aim=%s R=%.1f%n", cc.actName(), w, f.aim(), f.radius());
			}
		}
	}

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
		// 0x1FAFC8 벽 질의 (45 문서 §3.8): 두 슬롯 정적 시트 + CDV GO 몸체 (문·발판) + 적 피격 구 (범주 1|2)
		hero.setBladeWalls(new BladeWalls(() -> nav != null ? nav.navigator.world() : null,
				() -> enemies.stream().map(Enemy::fighter).filter(f -> !f.dead()).map(devple.gow.engine.combat.EnemyFighter::hitVolumes).toList()));
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

	/** 스크립트 EventTransmitter → 레벨 GO 이벤트 (원작 GO 메시지 처리, 43 문서 §14). */
	public static final int EVENT_RESET = 1015, EVENT_ARROW = 1020;
	/** GO 화살 이미터 발사체 (발리스타 goBigarrow). */
	private final ArrowShots arrows = new ArrowShots();
	/** 적 화살 퍼짐·부정확 무작위 (원작 0x13E600 난수, 결정적 녹화를 위해 고정 씨앗). */
	private final java.util.Random arrowRnd = new java.util.Random(0x2223C8);

	/** 겨누는 중인 SCR_ArcherShoot (궁수, 이미터, 남은 초, 이벤트 때 기술 id — 피격으로 기술이 바뀌면 취소). */
	private record ArcherAim(EnemyFighter fighter, int emitter, float[] left, int move) {
	}

	private final List<ArcherAim> archerAims = new ArrayList<>();

	/**
	 * SCR_ArcherShoot 시작 (0x18DAA8: 겨눔 +0x2CC = 0, 당김 +0x2C8 = 0 → 매 프레임 0x18D500). 1 단계: 대상 수평 각 / 25° (0.43633) 를 [−1, 1] 로 자른
	 * 겨눔 값까지 프레임당 0.02 씩 (닿는 프레임에 2 단계로), 그동안 당김 += 0.01 (0 을 넘으면 −1 → 첫 프레임 −0.99). 2 단계: 당김 += 0.05 / 프레임,
	 * 이벤트 b (궁수 SHOOT 0.2, 폭발 화살 33 은 0) 이상이면 쏜다 (0x18D6B8 → 0x18D800 = 0x222710) [확실: 상수]. 바로 앞이면 25 프레임 ≈ 0.42 초,
	 * 25° 옆이면 64 프레임. 겨누는 동안 궁수가 돌아서는 것은 반영하지 않는다 [근사].
	 */
	private void archerShoot(EnemyFighter f, int emitter, float b) {
		Vector3f to = new Vector3f(hero.ctl.position).sub(f.pos);
		float rel = (float) Math.atan2(-to.x, -to.z) - f.yaw;
		rel = (float) Math.atan2(Math.sin(rel), Math.cos(rel));
		archerAims.add(new ArcherAim(f, emitter, new float[]{archerShotDelay(rel, b)}, f.combat().active() ? f.combat().move().id() : -1));
	}

	/** {@link #archerShoot} 의 겨눔 + 당김 프레임 / 60 (초). rel = 대상까지 수평 각 (라디안). */
	static float archerShotDelay(float rel, float b) {
		float d = Math.max(-1f, Math.min(1f, rel / 0.43633f));
		int n0 = Math.max(1, (int) Math.ceil(Math.abs(d) / 0.02f - 1e-4f));
		float draw = -1f + 0.01f * n0;
		int n1 = Math.max(1, (int) Math.ceil((b - draw) / 0.05f - 1e-4f));
		return (n0 + n1) / 60f;
	}

	/** 겨누는 궁수 갱신: 시간이 되면 쏜다. 죽었거나 기술이 바뀌었으면 (맞아서 끊김) 취소 [추정]. */
	private void updateArcherAims(float dt) {
		for (java.util.Iterator<ArcherAim> it = archerAims.iterator(); it.hasNext(); ) {
			ArcherAim a = it.next();
			int move = a.fighter().combat().active() ? a.fighter().combat().move().id() : -1;
			if (a.fighter().dead() || move != a.move() || !enemies.stream().anyMatch(en -> en.fighter() == a.fighter())) {
				it.remove();
				continue;
			}
			a.left()[0] -= dt;
			if (a.left()[0] <= 0) {
				it.remove();
				fireEnemyArrow(a.fighter(), a.emitter());
			}
		}
	}

	/**
	 * 적 ArrowEmitter 를 영웅에게 (0x222710 대상 있음): 시작 = 이미터 "Aim Joint" (궁수 rWrist), 대상점 = 발 + 몸 높이 ½ (0x18D708: vt+0xB8 × 0.5 × 16,
	 * 크라토스 2.2 m [추정: vt+0xB8 = 몸 높이]), 대상 속도 = 이번 틱 이동 (대상 +0xE0) [근사].
	 */
	private void fireEnemyArrow(EnemyFighter f, int emitter) {
		if (hero.player.hp <= 0) {
			return;
		}
		String en = String.format(java.util.Locale.ROOT, "/TweakTemplates/ArrowEmitter/%03d/", emitter);
		String joint = orbTweaks.s(en, "Aim Joint", null);
		Vector3f origin = null;
		if (joint != null) {
			Matrix4f m = new Matrix4f();
			if (devple.gow.engine.fx.MagicFx.joints(f).world(joint, m)) {
				origin = m.getTranslation(new Vector3f());
			}
		}
		if (origin == null) {
			origin = new Vector3f(f.pos).add(0, f.bodyHeight() * 0.5f, 0);
		}
		Vector3f target = new Vector3f(hero.ctl.position).add(0, devple.gow.engine.nav.NavTuning.UNITS_PER_METER * 2.2f * 0.5f, 0);
		Vector3f vel = new Vector3f(hero.ctl.position).sub(hero.ctl.prevPosition).mul(60f);
		var shots = arrows.fireAt(orbTweaks, emitter, origin, target, vel, f.combat(), magicFx != null ? magicFx.effects() : null, arrowRnd);
		if (!shots.isEmpty() && System.getProperty("gow.debug.combat") != null) {
			System.out.printf(java.util.Locale.ROOT, "적 화살: 이미터 %d × %d (%.0f, %.0f, %.0f) → (%.0f, %.0f, %.0f)%n", emitter, shots.size(),
					origin.x, origin.y, origin.z, target.x, target.y, target.z);
		}
	}

	/**
	 * 적 화살이 영웅에 맞음 (0x223920 → 0x2045B8 → 0x204690): 피해 = Arrow "Damage" × 쏜 적 배율 (AI Damage Mult) [근사: 배율 위치],
	 * 밀기 = Ground/Air Impulse (Right, Up, Away), 막으면 "Block Sound" (Block Down) [근사], Concussion ≥ 0 이면 그 자리 범위 피해·이펙트
	 * (폭발 화살 33 → Concussion 33 goSKAxplode, 0x2244D8 [추정]).
	 */
	private void enemyArrowHit(ArrowShots.Shot s, Vector3f at) {
		String an = s.arrowNode;
		devple.gow.engine.combat.Combatant owner = s.owner;
		float dmg = s.damage() * (owner != null ? owner.damageScale() : 1f);
		float[] imp = {orbTweaks.f(an, "Ground Impulse Right", 0f), orbTweaks.f(an, "Ground Impulse Up", 0f), orbTweaks.f(an, "Ground Impulse Away", 0f),
				orbTweaks.f(an, "Air Impulse Right", 0f), orbTweaks.f(an, "Air Impulse Up", 0f), orbTweaks.f(an, "Air Impulse Away", 0f),
				0f, 0f, orbTweaks.f(an, "Ground Impulse Away", 0f)};
		int mfx = orbTweaks.i16(an, "MFX Template", -1);
		var attack = new devple.gow.tools.combat.CombatFile.Attack(0, 0, imp, 0, 1, mfx, -1, Math.round(dmg), 0, 0, 0, 0);
		var r = owner != null ? hero.hittable().receive(attack, owner, dmg, new Vector3f()) : devple.gow.engine.combat.Hittable.Result.IGNORED;
		String snd = r == devple.gow.engine.combat.Hittable.Result.BLOCKED ? orbTweaks.s(an, "Block Sound", null) : orbTweaks.s(an, "Hit Sound 4", null);
		if (snd != null && snd.startsWith("SND_")) {
			session.sound.play(snd, at);
		}
		int conc = orbTweaks.i16(an, "Concussion", -1);
		if (r != devple.gow.engine.combat.Hittable.Result.BLOCKED && conc > 0 && magicFx != null) {
			magicFx.concussion(conc, null, at, owner);
		}
		if (System.getProperty("gow.debug.combat") != null) {
			System.out.printf(java.util.Locale.ROOT, "적 화살 명중: %s 피해 %.1f %s%n", an, dmg, r);
		}
	}

	/**
	 * 1015 = PushPull 리셋 (0x266040 → 0x2100E0: 크랭크 되감기 → 다시 잡힘, 레버 −4), 1020 = 화살 이미터 (Creator 비헤이비어, 0x267698 → 0x222710)
	 * [확실: 디스어셈블]. 예: ATHN02C 회전판 레버 Gunlever-EventTmid, 발리스타 BigGun1Crank-EvtArrow / EvtReset.
	 */
	private void objectEventToGo(ScriptListener.ObjectEvent e) {
		if (e.actor() != null || e.objectName() == null || nav == null) {
			return;
		}
		String go = e.objectName();
		if (e.event() == EVENT_RESET) {
			var it = nav.items.byOwner(go);
			if (it != null) {
				System.out.println("오브젝트 리셋 1015: " + it);
				nav.navigator.scriptReset(it);
			}
		} else if (e.event() == EVENT_ARROW) {
			Integer emitter = level.data().arrowEmitters.get(go);
			if (emitter == null) {
				return;
			}
			String node = String.format(java.util.Locale.ROOT, "/TweakTemplates/ArrowEmitter/%03d/", emitter);
			String joint = orbTweaks.s(node, "Aim Joint", null);
			var ps = nav.navWorld.platforms();
			float[] aim = ps.jointWorld(go, joint);
			if (aim == null) { // 몸이 없는 GO: 배치 관절 (애니 전)
				for (var p : level.data().layout.placements()) {
					if (p.instance().name().equalsIgnoreCase(go)) {
						var sk = p.object().skeleton();
						for (int j = 0; j < sk.joints.size(); j++) {
							if (sk.joints.get(j).name().equalsIgnoreCase(joint)) {
								aim = p.jointWorld()[j];
							}
						}
					}
				}
			}
			arrows.fire(orbTweaks, emitter, aim, ps.family(go), magicFx != null ? magicFx.effects() : null);
		}
	}

	/** 부서지는 물체 (41 문서): 정적 메시 숨김 → 물체 GO 파괴 애니·먼지·소리, Brkb OrbEmitter 오브. */
	private void onBreak(String owner) {
		level.hideInstance(owner);
		if (nav != null) {
			nav.navWorld.platforms().setSolid(owner, false); // 부서진 뒤에는 지나갈 수 있다 (41 §2.1)
		}
		Breakables.Item it = breakables.item(owner);
		if (it == null) {
			return;
		}
		if (it.matrix != null) {
			magicFx.breakObject(owner, new org.joml.Matrix4f().set(it.matrix), it.fadeDelay, it.fadeTime, it.debrisPhysics);
		}
		int orbs = emitOrbs(it.orbNode(), it.matrix != null ? new Vector3f(it.matrix[12], it.matrix[13], it.matrix[14]) : hero.ctl.position); // 0x215DA0
		System.out.println("파괴: " + owner + " 오브 " + orbs);
	}

	/**
	 * 다른 슬롯 레벨 물체가 부서짐 ({@link #otherBreakables}): 그 슬롯 렌더에서 숨기고 (otherHidden) 충돌을 끄고, 파괴 이펙트 (GO 첫 act · FXC ·
	 * SEM, {@link #onBreak} 와 같음) 를 그 WAD 에서 재생한다. Marker 파괴 (DestructionSensor) 는 그 슬롯 스크립트 세계로 갔다.
	 */
	private void onBreakOther(String owner) {
		otherHidden.add(owner.toLowerCase());
		if (nav != null) {
			nav.neighbourPlatforms().setSolid(owner, false);
		}
		Breakables.Item it = otherBreakables != null ? otherBreakables.item(owner) : null;
		if (it == null) {
			return;
		}
		if (it.matrix != null && magicFx != null && otherName != null) {
			magicFx.addWad(Stages.wad(session.rom, otherName)); // 다른 슬롯 WAD 의 이펙트 GO (처음 한 번 해석)
			magicFx.breakObject(owner, new org.joml.Matrix4f().set(it.matrix), it.fadeDelay, it.fadeTime, it.debrisPhysics);
		}
		int orbs = emitOrbs(it.orbNode(), it.matrix != null ? new Vector3f(it.matrix[12], it.matrix[13], it.matrix[14]) : hero.ctl.position);
		System.out.println("파괴 (다른 슬롯): " + owner + " 오브 " + orbs);
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
			heroBody = new devple.gow.engine.combat.BodyPush.HeroBody(hero.ctl, () -> !hero.ctl.grounded, hero.combat,
					() -> nav != null ? nav.navigator.state() : null);
		}
		heroBody.refresh(bodyTick); // 관절 상자 0x1E6DF0 (머리, 틱마다 한 번 — 조건 비트 32 문서)
		bodies.add(heroBody);
		for (Enemy en : enemies) {
			bodies.add(en.fighter()); // 생물 범주 1|2 = 모든 적 (보스 포함, 32 문서 §생물끼리 밀어내기 — 보스)
		}
	}

	private devple.gow.engine.combat.BodyPush.HeroBody heroBody;
	/** 영웅 관절 상자 갱신 틱 (collectBodies 가 한 틱에 여러 번 불려도 0x1E6DF0 은 한 번). */
	private long bodyTick;

	/**
	 * 영웅 몸 질의 면 제외 마스크 ({@link devple.gow.engine.nav.NavWorld#bodyExclude}): 이 경로는 지상·공중 (0x1EA5F0, 벽이 아님) 또는
	 * 자유 이동 기술 (stateMask &amp; 0x1C = 0 → 0x1EA200). CombatGuide 는 피격 반응 (+0x13 = 2) 이 아닌 전투 기술 중에만 막는다.
	 */
	private long heroBodyExclude() {
		var m = hero.combat.active() ? hero.combat.move() : null;
		return devple.gow.engine.nav.NavWorld.bodyExclude(true, m != null && (m.stateMask() & 0x1C) == 0, false, m != null,
				m != null ? m.flags() : 0, m != null ? m.guard() : 0);
	}

	/** 자유 이동 전투 기술 중 (기술 stateMask &amp; 0x1C = 0 → 0x1EA200, 천장 질의를 늘 한다). */
	private boolean heroFreeMove() {
		var m = hero.combat.active() ? hero.combat.move() : null;
		return m != null && (m.stateMask() & 0x1C) == 0;
	}

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
		// 바닥 질의는 이번 틱 시작 높이와 현재 높이 중 높은 쪽에서 (원작 바닥 0x1E3298 은 이동 선분으로 "바닥 아래로만" 막는다, 32 문서).
		// 현재 높이에서만 보면 기술 루트 모션이 한 틱에 STEP 넘게 내려간 때 (ATHN01A 갑판 위 공격 −13.6) 지나친 바닥을 놓쳐 아래층으로 빠진다
		float g = ground(p.x, Math.max(p.y, hero.ctl.prevPosition.y) + STEP, p.z, 4000);
		var c0 = hero.ctl;
		boolean steep = false;
		if (nav != null && c0.grounded && c0.vy <= 0 && (Float.isNaN(g) || p.y - g > 2f)) {
			// 원작 바닥 질의 0x1E3298: 선분 = 발 + max(d − 6.4, 0) → 발 − (6.4 + d) (d = max(이번 변위, 3.2)), 처음 맞은 면이 법선 · 위 > 0.1 이면
			// 그 위에 선다 [확실: 0x1E3308–0x1E3330, 0x1E3568]. 엔진 바닥 (법선 Y ≥ 0.7) 은 그보다 가파른 비탈을 구멍으로 봐서
			// ATHN02B 사이클롭스 바닥의 945 → 937 경사 띠 (법선 Y 0.696, 폭 8) 를 밟으면 아래 데스존으로 떨어졌다 (자동 플레이)
			float d = Math.max(p.distance(c0.prevPosition), 3.2f), top = p.y + Math.max(d - 6.4f, 0f);
			float s = nav.navWorld.steepGround(p.x, top, p.z, top - (p.y - 6.4f - d));
			if (!Float.isNaN(s) && (Float.isNaN(g) || s > g)) {
				g = s;
				steep = true;			}
		}
		if (!Float.isNaN(g) && (p.y - g <= 2f || steep) && c0.vy <= 0) {
			p.y = g;
			c0.vy = 0;
			c0.grounded = true;
		} else {
			c0.vy = Math.max(c0.vy - GRAVITY * dt, -devple.gow.engine.nav.HeroNavigator.FALL_MAX);
			p.y += c0.vy * dt;
			c0.grounded = false;
			// 천장 질의 0x1E36B0 (38 §22): 오르는 동안 (0x1EA5F0 은 수직 속도 > 0 이면 바닥 대신, 자유 이동 0x1EA200 은 늘) 머리를 아래 보는 면
			// 밑으로 맞춘다. 없던 때는 ATHN01B 선창에서 2단 점프하면 머리가 ATHN01A 선창 천장 (−64) 과 갑판 (0) 을 지나 갑판 위에 내렸다 (사용자 보고)
			if (nav != null && (c0.vy > 0 || heroFreeMove())) {
				nav.navWorld.ceiling(p, hero.ctl.prevPosition.y, hero.ctl.yaw, devple.gow.engine.nav.HeroNavigator.HANG,
						devple.gow.engine.nav.HeroNavigator.FOOT, devple.gow.engine.nav.HeroNavigator.BODY, devple.gow.engine.nav.HeroNavigator.BODY,
						devple.gow.engine.nav.NavWorld.ceilingExclude(heroBodyExclude()));
			}
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
		long ex = heroBodyExclude(); // 몸 질의 0x1E2100 면 거름 (38 §18.6)
		pushWalls(p, ex);
		// 밀어내기가 몸을 아래로 민 만큼은 바닥이 막는다: 원작 이동 단계 순서는 밀어내기 0x1E2DD8 → 벽 0x1E2100 → 바닥 0x1E3298 ("바닥 아래로만" 막음, 32 문서).
		// 여기서는 바닥을 먼저 맞추므로, 위에 있는 적 몸 (띄워진 해골·기어오르는 해골) 이 한 틱에 10–14 아래로 밀면 갑판 밑으로 빠져
		// 다음 틱에는 그 높이에서 바닥을 찾아 아래층 (ATHN01B 선창 −64) 으로 떨어졌다 (ATHN01A 자동 플레이, 43 문서)
		if (c0.grounded) {
			float g2 = ground(p.x, Math.max(p.y, hero.ctl.prevPosition.y) + STEP, p.z, 4000);
			if (!Float.isNaN(g2) && p.y < g2) {
				p.y = g2;
			}
		}
		// 이동 선분 마지막 검사 (0x1EAB78, 38 §19): 틱 시작 위치 → 새 위치가 막이·난간에 막히면 그 앞 + 0.05 에 두고 몸 질의부터 다시 (최대 3 회).
		// 밀어내기 (적 몸·피격) 가 몸 가운데를 얇은 GeneralGuide 막 너머로 넘겨도 반대쪽으로 빠지지 않는다 (ATHN01A 갑판 구석 낙하)
		if (nav != null) {
			long mx = devple.gow.engine.nav.NavWorld.moveExclude(ex);
			for (int i = 0; i < 3 && nav.navWorld.blockMove(hero.ctl.prevPosition, p, devple.gow.engine.nav.HeroNavigator.FOOT, mx); i++) {
				pushWalls(p, ex);
			}
			nav.navWorld.blockMove(hero.ctl.prevPosition, p, devple.gow.engine.nav.HeroNavigator.FOOT, mx);
		}
		// 다른 슬롯 레벨 바닥만 밟고 있으면 그 레벨로 (원작은 두 레벨이 같이 상주, 예 SEWR01 → SEWR02)
		// 스크립트 Idle 중에는 넘기지 않는다 [근사: 엔진 구조]: 엔진의 전환은 씬 (카메라 관리자·스크립트 요청 카메라) 을 새로 만든다. ATHN04A
		// LedgeJumpCams (이웃 슬롯) 가 Idle + SuicideCam 을 켜고 10.5 초 뒤 푸는데, 난간 이음에서 바로 넘어가면 크레인 카메라가 끊기고 (예전엔 세계까지 새로
		// 만들어 푸는 지연도 사라져 영웅이 멈춘 채 남았다). 원작은 LoadCheck 가 현재 레벨 이름만 바꾸고 카메라·두 슬롯 세계가 그대로 돈다 (43 §18).
		if (scriptIdle) {
			otherFloorTime = 0;
		} else if (otherCol != null && !otherName.equals(hostLevel) && c0.grounded && Float.isNaN(col.groundHeight(p.x, p.y + STEP, p.z, 64))
				&& !Float.isNaN(otherCol.groundHeight(p.x, p.y + STEP, p.z, 64))) {
			if ((otherFloorTime += dt) > 0.25f) { // 이음매에서 오가며 바뀌지 않도록 잠깐 머문 뒤
				goToLevel(otherName, new Arrival(LevelLink.Kind.WALK, levelName, new float[]{p.x, p.y, p.z}, hero.ctl.yaw));
			}
		} else if (otherCol != null && otherName.equals(hostLevel) && c0.grounded && !insideOwnVolumes(p)
				&& !Float.isNaN(otherCol.groundHeight(p.x, p.y + STEP, p.z, 64))) {
			// 덧붙는 레벨 (ATHN02D 메두사 방) 의 자기 ENV 볼륨 (방 MedusaRipZone · 마법 단상 · 문) 을 벗어나 숙주 (ATHN02C) 바닥으로 → 숙주가 현재 레벨.
			// 원작은 두 슬롯의 스크립트·GO 가 모두 살아 있어 숙주의 SpawnEnemy (BalistaEnems 해골병)·R2 오브젝트 (회전판 레버·발리스타 크랭크)·Creator
			// (세이브 포인트·상자) 가 그대로 동작하는데, 엔진은 현재 레벨 것만 다룬다. 예전엔 숙주로 넘어가지 않아 미노타우로스 방 남쪽 출구 (MAcdHullOff)
			// 뒤 통로의 해골병이 나오지 않고 발리스타 레버·크랭크·세이브 제단이 동작하지 않았다 (43 문서 §14) [근사: 엔진 구조 — 현재 레벨 기준]
			if ((otherFloorTime += dt) > 0.1f) {
				goToLevel(otherName, new Arrival(LevelLink.Kind.WALK, levelName, new float[]{p.x, p.y, p.z}, hero.ctl.yaw));
			}
		} else {
			otherFloorTime = 0;
		}
	}

	/** 발 p 가 이 레벨의 ENV 볼륨 (스크립트 영역) 안인가 — 덧붙는 레벨의 "자기 영역" (발 + 20 높이에서). 볼륨이 없으면 참. */
	private boolean insideOwnVolumes(Vector3f p) {
		boolean any = false;
		for (devple.gow.tools.level.LevelData.PlacedVolume v : level.data().volumes) {
			if (!v.hull().name.startsWith("ENV_")) {
				continue;
			}
			any = true;
			if (v.contains(p.x, p.y + 20, p.z)) {
				return true;
			}
		}
		return !any;
	}

	/**
	 * 결합 (GrabSync) 중 영웅 변위 (루트 모션·끌기) 의 벽 처리. 원작 생물 갱신 0x1EBD78 은 루트 모션 D 의 이동을 변위 (sp+0x3C0) 로 내비게이션 이동
	 * (지상 0x1EB3D8 / 0x1EAEC8 → 벽 0x1E2100 · 이동 선분 0x1EAB78, 자유 이동 0x1ECD60 → 0x1EA200) 에 넘기므로 결합 중에도 벽을 지나지 않는다
	 * (37 §11.4) [확실: 경로]. 엔진은 결합 중 장면 물리를 건너뛰어 ({@code GrabSync.align} 이 위치를 정함) 벽 가에서 잡은 해골병을 끄는 동안 영웅이
	 * 벽 너머 허공으로 나가 떨어졌다 (ATHN05D 엘리베이터 방 서쪽 벽 x −1132, 43 §21.2-7). 수평만 고친다 (수직 = 결합 하한, 사이클롭스 올라탐 그대로).
	 */
	private void grabWalls() {
		grabWalls(hero.ctl.position, hero.ctl.prevPosition, heroBodyExclude(), col, otherCol, nav, nav.navWorld);
	}

	/** {@link #grabWalls()} 본체: 몸 구 밀어내기 → 이동 선분 (틱 시작 → 지금) 막힘이면 그 앞에서 다시 (최대 3 회, 장면 물리와 같은 순서). */
	static void grabWalls(Vector3f p, Vector3f prev, long ex, LevelCollision col, LevelCollision otherCol, SceneNav nav,
			devple.gow.engine.nav.NavWorld nw) {
		pushWalls(p, ex, col, otherCol, nav);
		long mx = devple.gow.engine.nav.NavWorld.moveExclude(ex);
		for (int i = 0; i < 3 && nw.blockMove(prev, p, devple.gow.engine.nav.HeroNavigator.FOOT, mx); i++) {
			pushWalls(p, ex, col, otherCol, nav);
		}
	}

	/** 영웅 몸 구 벽 밀어내기 (두 슬롯 정적 시트 + 문·창살·회전방 CDV): 발 위치 p 의 x·z 를 고친다. */
	private void pushWalls(Vector3f p, long ex) {
		pushWalls(p, ex, col, otherCol, nav);
	}

	static void pushWalls(Vector3f p, long ex, LevelCollision col, LevelCollision otherCol, SceneNav nav) {
		Vector3f c = new Vector3f(p.x, p.y + 20, p.z);
		boolean pushed = col.collideSphere(c, 12, ex);
		if (otherCol != null) {
			pushed |= otherCol.collideSphere(c, 12, ex);
		}
		if (nav != null) {
			pushed |= nav.collideBodies(c, 12, ex); // 문·창살·회전방 CDV (42 문서 §5)
		}
		if (pushed) {
			p.x = c.x;
			p.z = c.z;
		}
	}

	/** 이번 프레임 렌더 보간 값 (반사 패스 콜백용). */
	private float renderAlpha = 1f;

	private void updateCamera(float alpha) {
		// 원작 카메라 시스템 (CMV 선택 + 레일/궤도 + 전환 보간): CameraRig, 30 문서. 갱신은 update() 틱에서
		cameraRig.apply(cam, alpha);
		// 원작 투영 0x1692D8: CAM 0x70 은 가로 화각으로 고정 (x 배율 = 1/tan(f/2)), 세로는 화면비 0x330A30 (와이드 16/9, 0x1B61B8) 로 줄인다.
		// CameraRig 는 4:3 세로 화각을 주므로 창 비율에 맞춰 가로를 유지 — 세로 고정이던 때는 16:9 에서 가로 68.9° (원작 54.4°) 로 넓어져
		// 크레토스가 작게·카메라가 멀어 보였다 (ATHN01A 시작, 사용자 보고)
		float aspect = pendingEngine != null ? pendingEngine.window().aspect() : 4f / 3f;
		if (aspect > 0) {
			double t = Math.tan(Math.toRadians(cam.fovDeg) / 2) * (4.0 / 3.0) / aspect;
			cam.fovDeg = (float) Math.toDegrees(2 * Math.atan(t));
		}
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
		// 원작 층 2 (0x160CC0, 47 §7.4.2): 불투명 단계 (키 [표[2][0]+2, 표[2][2]) — 레벨·이웃 슬롯·레벨 WAD 적) → 그림자 합성 0x160518 →
		// 반투명 단계 (가산·뺄셈·flags2 0x100 머티리얼 — 영웅 R_HERO·무기 R_WPN 머티리얼은 모두 0x100) → 층 3 (블레이드).
		// 그림자를 끄면 (-Dgow.noshadow) 예전처럼 한 번에
		boolean staged = shadows != null && !NO_STAGES;
		int prevStage = devple.gow.engine.render.DrawStage.set(staged ? devple.gow.engine.render.DrawStage.EARLY : devple.gow.engine.render.DrawStage.ALL);
		try {
			level.skyNeighbour = otherScene; // 다른 슬롯 레벨의 하늘 GO 도 하늘 단계에 (두 슬롯 상주, 각자 가시 칸 — 43 §16)
			if (level.skyEffects == null && magicFx != null) {
				level.skyEffects = c -> magicFx.renderSky(c, e.window().aspect()); // 하늘 GO 파티클은 하늘 단계 (층 1, 43 §17)
			}
			level.render(e, alpha);
			renderOtherSlot(e);
			drawActors(e, alpha);
			renderShadows(e.window().aspect(), alpha);
			if (staged) {
				devple.gow.engine.render.DrawStage.set(devple.gow.engine.render.DrawStage.LATE);
				level.renderLate(e);
				if (otherScene != null) {
					otherScene.renderNeighbour(e, cam);
				}
				drawActors(e, alpha);
			}
		} finally {
			devple.gow.engine.render.DrawStage.set(prevStage);
		}
		if (System.getProperty("gow.lightdebug") != null && hero.lastLights() != null && (lightFrame++ % 30) == 0) {
			System.out.println("영웅 라이트: " + hero.lastLights() + " 블레이드 " + (hero.bladeLight(0) != null ? hero.bladeLight(0).dyn : -1f));
		}
		hero.drawChains(cam, e.window().aspect(), alpha, magicFx.godBladesOn()); // 사슬·궤적 (45 문서 §3)
		if (System.getProperty("gow.nofx") == null) {
			level.renderEffects(e, cam); // 비·파티클·물은 캐릭터 뒤에 (--no-fx 로 끔: 스크린샷용)
			LevelScene o = otherScene;
			if (o != null) {
				o.renderNeighbourEffects(e, cam); // 다른 슬롯 레벨 이미터 (횃불·불) — 두 슬롯 상주
			}
		}
		magicFx.render(cam, e.window().aspect(), e.renderer().white());
		renderCameraFilter();
		float[] fc = screenFade.color();
		e.renderer().overlay(fc[0], fc[1], fc[2], fc[3]); // 스크립트 FadeOut/FadeIn 색 사각형 0x2C8F50 [추정: HUD 아래]
		if (levelHud != null) {
			levelHud.render(e);
		}
		hud.render(e);
	}

	/** 비교용: -Dgow.shadowlate 면 예전 순서 (레벨·캐릭터 전부 뒤에 그림자 합성). */
	private static final boolean NO_STAGES = System.getProperty("gow.shadowlate") != null;

	/** 적·영웅 (블레이드 포함) — 현재 {@link devple.gow.engine.render.DrawStage} 의 파트만. 캐릭터에도 레벨 안개 (같은 basic.frag 유니폼). */
	private void drawActors(Engine e, float alpha) {
		var env = level.fx().env();
		env.applyFog(hero.shader(), cam, e.window().height());
		env.applyFog(enemyShader, cam, e.window().height());
		Matrix4f m = new Matrix4f();
		for (Enemy en : enemies) {
			if (en.fighter().shattered() || en.fighter().statueShown()) {
				continue; // 석상 GO (*StonedBRK) 가 대신 보인다 — 원작 0x1E8960 이 소유자를 숨김 (0x1DFA18 / 0x1E0768)
			}
			en.model().setVariant(en.fighter().modelVariant); // BMI 단계별 갑옷 (37 문서 §10)
			// SCR_NoShadow (0xBFC016FE, Hash127Upper): 스폰 기술 500–504·523 SKSshipDismount 동안 그림자 없음 — 난간 밖·바닥 밑에서 오를 때
			boolean noShadow = shadows != null && shadows.collecting() && en.fighter().combat().activeScript(SCR_NO_SHADOW) != null;
			if (noShadow) {
				shadows.pause();
			}
			en.model().draw(cam, e.window().aspect(), en.fighter().world(m, alpha), en.fighter().animator().pose(),
					e.renderer().white());
			if (noShadow) {
				shadows.resume();
			}
		}
		hero.draw(cam, e.window().aspect(), alpha, e.renderer().white());
		devple.gow.engine.fx.FxEnvironment.disableFog(hero.shader());
		devple.gow.engine.fx.FxEnvironment.disableFog(enemyShader);
	}

	/** 캐릭터 그림자 (47 문서 §7). null = 끔. */
	private devple.gow.engine.light.ShadowCasters shadows;
	private devple.gow.engine.render.ShadowRenderer shadowRenderer;
	private final devple.gow.engine.light.ShadowVolume.Out shadowTris = new devple.gow.engine.light.ShadowVolume.Out();
	private int shadowFrame;

	/**
	 * 모은 그림자 볼륨을 층 2 불투명 단계 (레벨·이웃 슬롯·적) 뒤, 반투명 단계 (영웅·물·가산 등)·층 3 (블레이드)·사슬·이펙트 앞에 합성한다 (0x160CC0 → 0x160518,
	 * 47 §7.4.2). 받는 면 = 현재 + 다른 슬롯 레벨 충돌 (NoShadows 면 제외, 0x1BF658).
	 */
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
		java.nio.file.Path p = Stages.wad(session.rom, wad);
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
			otherScene.setMaterialTimes(otherMaterialTimes);
			otherScene.renderNeighbour(e, cam);
		}
	}

	@Override
	public void exit(Engine e) {
		devple.gow.engine.combat.AiTargets.globalHold = false; // 전역 정지는 레벨마다
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
		neighbourKinds.values().stream().filter(k -> k != null).forEach(k -> session.sound.unload(k.assets().wad().name()));
		soundOnly.forEach(session.sound::unload);
		session.sound.trimFileCache();
		enemies.forEach(en -> en.model().close());
		enemyShader.close();
		magicFx.close();
		// 적·보스 종류의 WAD 자산 (텍스처·모델) — 이전에는 닫지 않아 레벨 전환마다 GL 텍스처가 남았다 (48 문서 §4). 같은 자산을 나눠 쓰는
		// 종류 (보스 템플릿 여럿 = 레벨 WAD 하나) 는 한 번만
		java.util.Set<WadAssets> kindAssets = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		kinds.values().stream().filter(k -> k != null).forEach(k -> kindAssets.add(k.assets()));
		neighbourKinds.values().stream().filter(k -> k != null).forEach(k -> kindAssets.add(k.assets()));
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
