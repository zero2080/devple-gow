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
	public record Arrival(LevelLink.Kind kind, String from, float[] pos, float yaw, devple.gow.engine.level.CameraRig.Handoff camera) {
		public Arrival(LevelLink.Kind kind, String from, float[] pos, float yaw) {
			this(kind, from, pos, yaw, null);
		}
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
	private final Random rnd = new Random();
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
	}

	/** 레벨 전환으로 들어옴. */
	public GameScene(GameSession session, String levelName, Arrival arrival) {
		this(session, levelName, null, 0);
		this.arrival = arrival;
	}

	@Override
	public void enter(Engine e) {
		try {
			level = new LevelScene(session.rom.resolve(levelName + ".WAD"), -2, false);
			level.enter(e);
			level.setExternalCamera(cam);
			level.setDeferEffects(true);
			// SCR_Mirror 반사 패스의 영웅 (원작 0x1DAEC0: 영웅 모델 bit 61, 마스크 0x40 → 거울 뷰 0x80, 값 [4] ≠ 0 인 거울은 뺀다 — 43 문서 §9)
			level.setMirrorActors(rc -> {
				level.fx().env().applyFog(hero.shader(), rc, e.window().height());
				hero.draw(rc, e.window().aspect(), renderAlpha, e.renderer().white());
			});
			col = level.collision();
			hero = new HeroRig(session.rom, session.permk, session.player != null ? session.player.costume : session.costume, session.player);
			hero.difficulty = session.difficulty;
			session.player = hero.player;
			UiVariables vars = new UiVariables();
			hud = new UiLayer(new WadAssets(session.rom.resolve("R_PERMK.WAD")), true, "FLP_HUDK", session.messages, vars);
			levelHud = loadLevelHud(vars);
			session.sound.load(level.data().wad);
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
			enemyShader = new Shader("skinned.vert", "basic.frag");
			orbTweaks = new Tweaks();
			orbTweaks.load(session.permk);
			orbTweaks.load(level.data().wad);
			breakables = new Breakables(level.data(), orbTweaks);
			hero.attachExternalAnims(level.data().wad); // 레벨별 영웅 애니 (ANM_hero_Athn01A 등)
			loadEnemyKinds();
			loadBoss();
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		grab = new devple.gow.engine.combat.GrabSync(hero.ctl, hero.combat);
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
		magicFx = new devple.gow.engine.fx.MagicFx(session.rom); // 원작 이펙트 GO (40 문서)
		hero.magic.setSink(magicFx.sink((name, at) -> session.sound.play(name, at)));
		magicFx.bindHero(hero.combat, hero::jointWorld);
		magicFx.setGodBlades(session.rom.resolve("R_WPN" + hero.player.costume + "_" + Math.max(0, Math.min(5, hero.bladeLevel())) + ".WAD"),
				() -> !hero.player.artemisEquipped); // 블레이드 god 판 (0x1FA9A0, 45 문서 §2)
		hero.hideBlades = magicFx::godBladesOn;
		magicFx.bindBlades(new devple.gow.engine.fx.MagicFx.Blades() { // 사슬이 정한 블레이드 행렬·궤적 (45 문서 §3)
			public boolean world(int side, Matrix4f out) {
				return hero.bladeWorld(side, out);
			}

			public boolean trail(int side) {
				return hero.trail(side);
			}
		});
		wireEffects();
		hudBinding.castable = hero.magic::has;
		boolean seamless = arrival != null && (arrival.kind() == LevelLink.Kind.SEAMLESS || arrival.kind() == LevelLink.Kind.WALK);
		link = new LevelLink(levelName, seamless ? arrival.from() : null, w -> Files.exists(session.rom.resolve(w + ".WAD")));
		if (link.other() != null) {
			loadOther(link.other());
		}
		cameraRig = new devple.gow.engine.level.CameraRig(level.data());
		cameraRig.setOther(otherData); // 다른 슬롯 레벨의 CMV·카메라도 질의 (원작 두 슬롯 상주)
		// 카메라 전환 → 전역 이벤트 1037 (인자0 = 카메라 이름, 원작 0x1EEE88)
		cameraRig.onSwitch(n -> {
			if (world != null) {
				world.postEvent(1037, n, 0, 0, null);
			}
		});
		world = new ScriptWorld(level.data(), session.scriptHost, listener());
		nav = new SceneNav(level.data(), col, hero, world, session.scriptHost, orbTweaks, navHost());
		if (otherName != null) {
			nav.setOther(otherName, otherData, otherCol, nav.neighbourListener());
		}
		String arrivalCam = placeSpawn();
		respawn(spawn);
		checkpoint.set(spawn);
		cam.fovDeg = 60;
		cam.near = 2;
		cam.far = 30000;
		bridge = new devple.gow.engine.boss.CombatScriptBridge(world);
		if (boss != null) {
			boss.attach(arena());
		}
		world.start();
		world.placePlayer(spawn.x, spawn.y, spawn.z, seamless);
		// 시작 카메라는 생성 시 스크립트(CreationSensor → Vis Attr11_1 CMV 끔, 예 ATHN02D MedusaHeadCam) 뒤에 고른다 — 원작은 레벨이
		// 이웃 슬롯에 먼저 올라와 있다가 들어오므로 그 시점엔 생성 스크립트가 이미 돌았다 [추정]. Warp_Jump 순서: 볼륨 선택(0x1EFC98 →
		// 0x1EDF48(0)) 뒤 Start 의 CameraName 을 SwitchCamera (0x1A8E10).
		if (seamless && arrival.camera() != null) {
			cameraRig.resume(arrival.camera());
		}
		cameraRig.update(0, hero.ctl.position, hero.ctl.yaw, false);
		if (!arrivalCam.isEmpty()) {
			cameraRig.request(arrivalCam); // Warp 도착 Start 의 CameraName
			cameraRig.update(0, hero.ctl.position, hero.ctl.yaw, false);
		}
		if (Float.isNaN(col.groundHeight(spawn.x, spawn.y + STEP, spawn.z, 64))) {
			nav.settleArrival(spawn); // 움직이는 발판 위 도착점 (ATHN05D WarpFrom4A)
		}
		System.out.printf("레벨 %s (진입점 %s): 시작 (%.0f, %.0f, %.0f), 적 종류 %s%n", levelName, entry, spawn.x, spawn.y,
				spawn.z, kinds.keySet());
	}

	/** 레벨이 쓰는 적 리소스: RSRCS 태그(0x1F4)의 24바이트 이름들 → R_&lt;이름&gt;.WAD (11 문서 §6.3 Slots 힙). */
	private void loadEnemyKinds() throws IOException {
		for (String name : EnemyFactory.resourceWads(level.data().wad)) {
			var path = session.rom.resolve(name + ".WAD");
			if (!kinds.containsKey(name) && Files.exists(path)) {
				try {
					EnemyFactory.Kind k = EnemyFactory.load(path, session.permk, -1);
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
	private void ensureHost(float x, float y, float z) {
		if (!Float.isNaN(col.groundHeight(x, y + STEP, z, 600)) || !devple.gow.engine.level.HostLevel.addOn(level.data().wad)) {
			return;
		}
		String h = devple.gow.engine.level.HostLevel.find(session.rom, levelName, x, y, z);
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
		otherName = wad;
		otherScenePending = true; // 렌더 스레드에서 지오메트리 적재 (render)
		otherCol = null;
		otherData = null;
		try {
			otherData = new LevelData(devple.gow.tools.wad.WadFile.load(session.rom.resolve(wad + ".WAD")));
			otherCol = new LevelCollision(otherData.sheets);
		} catch (IOException | RuntimeException ex) {
			System.out.println("이웃 레벨 충돌 로드 실패 " + wad + ": " + ex.getMessage());
		}
		if (nav != null) {
			nav.setOther(wad, otherData, otherCol, nav.neighbourListener());
		}
		if (cameraRig != null) {
			cameraRig.setOther(otherData);
		}
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
		if (leaving) {
			return;
		}
		leaving = true;
		System.out.println("레벨 전환 → " + wad + (entryName != null ? " (" + entryName + ")" : ""));
		pendingEngine.scenes().transitionTo(new GameScene(session, wad, entryName, 0));
	}

	private void goToLevel(String wad, Arrival a) {
		if (leaving) {
			return;
		}
		leaving = true;
		System.out.println("레벨 전환 → " + wad + " (" + a.kind() + ", " + a.from() + " 에서)");
		if (a.kind() != LevelLink.Kind.WARP && a.camera() == null && cameraRig != null) {
			a = new Arrival(a.kind(), a.from(), a.pos(), a.yaw(), cameraRig.handoff()); // 이음: 카메라 상태 유지 (30 문서 §7)
		}
		pendingEngine.scenes().transitionTo(new GameScene(session, wad, a));
	}

	private Engine pendingEngine;

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
		for (String part : sc.split(",")) {
			String[] f = part.trim().split(":");
			steps.add(new devple.gow.engine.input.ScriptedInput.Step(Float.parseFloat(f[1]), Float.parseFloat(f[2]),
					PadButton.valueOf(f[0].toUpperCase())));
		}
		return new devple.gow.engine.input.ScriptedInput(0, steps);
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
			f.setCrowd(() -> bodies); // 생물끼리 밀어내기 0x1E2DD8
		}
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
		enemies.add(new Enemy(f, kind.buildModel(enemyShader), r.actor(), kind));
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
		pendingEngine = e;
		if (session.openingPending && levelName.equals(GameFlow.START_LEVEL)) {
			session.openingPending = false;
			playMovie(e, "Semo01"); // 새 게임 오프닝
			return;
		}
		time += dt;
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
		for (var b : nav.platformBodies()) {
			if (b.anim() != null) {
				level.poseInstance(b.owner, b.joints(), b.hidden()); // 움직인 발판·문·회전방은 애니 자세로 (관절 숨김 포함: DMC 결계)
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
		magicFx.weaponWorld(hero.combat, (n, at) -> session.sound.play(n, at)); // 무기-월드 베기 Slice Hit (40 문서 §6)
		if (hero.player.hp <= 0) {
			heroDied();
		}
		footsteps(dt);
		Vector3f fwd = new Vector3f(cam.target).sub(cam.eye).normalize();
		session.sound.listener(cam.eye, fwd, new Vector3f(0, 1, 0));
		session.sound.update(dt);
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
		bodies.add(devple.gow.engine.combat.BodyPush.hero(hero.ctl.position, () -> !hero.ctl.grounded, hero.combat));
		for (Enemy en : enemies) {
			if (en.fighter().nav().world() != null) {
				bodies.add(en.fighter());
			}
		}
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
		devple.gow.engine.combat.BodyPush.push(bodies.get(0), p, bodies, null);
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
		renderAlpha = alpha;
		updateCamera(alpha);
		level.fx().env().setFogOverride(magicFx.screen.fog()); // 포세이돈 Fog Template (0x195C90, 35 문서 §4.6)
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

	private devple.gow.engine.render.CameraFilterRenderer filterRenderer;
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
		var f = filters.computeIfAbsent(n, k -> java.util.Optional.ofNullable(devple.gow.engine.fx.CameraFilter.load(orbTweaks, k)));
		if (f.isEmpty() || !f.get().visible()) {
			return;
		}
		if (filterRenderer == null) {
			filterRenderer = new devple.gow.engine.render.CameraFilterRenderer();
		}
		filterRenderer.draw(f.get(), cam);
	}

	/** 다른 슬롯 레벨 지오메트리 (처음 필요할 때 적재, 바뀌면 교체). */
	private void renderOtherSlot(Engine e) {
		if (otherScenePending) {
			otherScenePending = false;
			if (otherScene != null) {
				otherScene.exit(e);
				otherScene = null;
			}
			if (otherName != null && Files.exists(session.rom.resolve(otherName + ".WAD"))) {
				try {
					LevelScene s = new LevelScene(session.rom.resolve(otherName + ".WAD"), -2, false);
					s.enter(e);
					otherScene = s;
				} catch (RuntimeException ex) {
					System.out.println("이웃 레벨 렌더 실패 " + otherName + ": " + ex.getMessage());
				}
			}
		}
		if (otherScene != null) {
			otherScene.renderNeighbour(e, cam);
		}
	}

	@Override
	public void exit(Engine e) {
		if (otherScene != null) {
			otherScene.exit(e);
			otherScene = null;
		}
		session.sound.stopAll();
		session.sound.unload(level.data().wad.name());
		kinds.values().stream().filter(k -> k != null).forEach(k -> session.sound.unload(k.assets().wad().name()));
		soundOnly.forEach(session.sound::unload);
		enemies.forEach(en -> en.model().close());
		enemyShader.close();
		magicFx.close();
		if (filterRenderer != null) {
			filterRenderer.close();
		}
		hero.close();
		hud.close();
		if (levelHud != null) {
			levelHud.close();
		}
		level.exit(e);
	}
}
