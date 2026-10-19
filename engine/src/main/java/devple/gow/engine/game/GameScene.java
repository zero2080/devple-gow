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
import devple.gow.tools.level.GoInstance;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.wad.WadTag;
import devple.gow.tools.wad.WadTagType;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 통합 게임 씬: 원본 레벨(배치·충돌·카메라) + 크레토스(이동·전투·성장) + 레벨 스크립트(ScriptWorld) + 적 + 원작 HUD.
 * <ul>
 *   <li>지면: 충돌 시트의 바닥 높이 추종 + 중력 (9.82 m/s², 1m ≈ 16 유닛 — 추정)</li>
 *   <li>벽: 허리 높이 구(반지름 12) 밀어내기</li>
 *   <li>카메라: CMV 볼륨이 고른 레벨 카메라 위치에서 크레토스를 바라봄, 없으면 추적 카메라</li>
 *   <li>스크립트: 메시지 → HUD 메시지 창, 체크포인트 → 재시작 위치, 데스존 → 사망, LoadWad/Warp/Load → 레벨 전환, SpawnEnemy → 적 생성</li>
 *   <li>START = 원작 일시정지 메뉴</li>
 * </ul>
 */
public final class GameScene implements Scene {

	public static final float UNITS_PER_METER = 16f;
	private static final float GRAVITY = 9.82f * UNITS_PER_METER;
	private static final float STEP = 14f;

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
	private final Vector3f spawn = new Vector3f();
	private final Vector3f checkpoint = new Vector3f();
	private float spawnYaw;
	private float camYaw;
	private boolean paused;
	/** 파워업(업그레이드) 메뉴 열림: 메뉴 스크립트가 투자를 처리하므로 HUD 값 쓰기를 멈춘다. */
	private boolean powerUp;
	private float time;
	private float messageTimer;
	private float stepTimer;
	private boolean wasAir;
	private String pendingLevel;
	private boolean leaving;
	private final Map<String, EnemyFactory.Kind> kinds = new HashMap<>();
	private final List<Enemy> enemies = new ArrayList<>();
	private Shader enemyShader;
	private Tweaks orbTweaks;
	private devple.gow.engine.combat.GrabSync grab;
	private final Random rnd = new Random();

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

	@Override
	public void enter(Engine e) {
		try {
			level = new LevelScene(session.rom.resolve(levelName + ".WAD"), -2, false);
			level.enter(e);
			level.setExternalCamera(cam);
			level.setDeferEffects(true);
			col = level.collision();
			hero = new HeroRig(session.rom, session.permk, 0, session.player);
			hero.difficulty = session.difficulty;
			session.player = hero.player;
			UiVariables vars = new UiVariables();
			hud = new UiLayer(new WadAssets(session.rom.resolve("R_PERMK.WAD")), true, "FLP_HUDK", session.messages, vars);
			session.sound.load(level.data().wad);
			hero.combat.onSound((h, p) -> session.sound.play(h, p));
			vars.addListener((name, value) -> {
				if (name.regionMatches(true, 0, "PS2_Sound", 0, 9) && value != null && value.startsWith("SND_")) {
					session.sound.play(value);
				}
				if (powerUp && name.equals("PS2_PSMDone") && "1".equals(String.valueOf(value).replace(".0", ""))) {
					onPowerUpClosed();
				} else if (paused && !powerUp && (name.equals("PS2_PauseDone") || name.endsWith("Menu_Choice"))) {
					onMenuClosed(name, value);
				}
			});
			enemyShader = new Shader("skinned.vert", "basic.frag");
			orbTweaks = new Tweaks();
			orbTweaks.load(session.permk);
			orbTweaks.load(level.data().wad);
			hero.attachExternalAnims(level.data().wad); // 레벨별 영웅 애니 (ANM_hero_Athn01A 등)
			loadEnemyKinds();
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		grab = new devple.gow.engine.combat.GrabSync(hero.ctl, hero.combat);
		placeSpawn();
		respawn(spawn);
		checkpoint.set(spawn);
		cam.fovDeg = 60;
		cam.near = 2;
		cam.far = 30000;
		cameraRig = new devple.gow.engine.level.CameraRig(level.data());
		cameraRig.update(0, hero.ctl.position, hero.ctl.yaw, false);
		world = new ScriptWorld(level.data(), session.scriptHost, listener());
		world.start();
		System.out.printf("레벨 %s (진입점 %s): 시작 (%.0f, %.0f, %.0f), 적 종류 %s%n", levelName, entry, spawn.x, spawn.y,
				spawn.z, kinds.keySet());
	}

	/** 레벨이 쓰는 적 리소스: RSRCS 태그(0x1F4)의 24바이트 이름들 → R_&lt;이름&gt;.WAD (11 문서 §6.3 Slots 힙). */
	private void loadEnemyKinds() throws IOException {
		for (WadTag t : level.data().wad.tags()) {
			if (t.tag() != WadTagType.RESOURCES || !t.hasData()) {
				continue;
			}
			var d = t.data();
			for (int off = 0; off + 24 <= d.limit(); off += 24) {
				int n = 0;
				while (n < 24 && d.get(off + n) != 0) {
					n++;
				}
				if (n == 0) {
					continue;
				}
				byte[] b = new byte[n];
				d.get(off, b);
				String name = "R_" + new String(b, StandardCharsets.ISO_8859_1).toUpperCase();
				var path = session.rom.resolve(name + ".WAD");
				if (!kinds.containsKey(name) && Files.exists(path)) {
					try {
						EnemyFactory.Kind k = EnemyFactory.load(path, session.permk, -1);
						kinds.put(name, k);
						orbTweaks.load(k.assets().wad());
						session.sound.load(k.assets().wad());
						hero.attachExternalAnims(k.assets().wad());
					} catch (RuntimeException ex) {
						System.out.println("적 리소스 로드 실패 " + name + ": " + ex.getMessage());
					}
				}
			}
		}
	}

	private void placeSpawn() {
		if (loadPos != null) {
			spawn.set(loadPos[0], loadPos[1], loadPos[2]);
			spawnYaw = loadYaw;
			return;
		}
		LevelData data = level.data();
		GoInstance start = null;
		if (entry != null) {
			start = data.layout.instances.stream().filter(g -> g.name().equalsIgnoreCase(entry) || g.object().equalsIgnoreCase(entry))
					.findFirst().orElse(null);
		}
		if (start == null) {
			start = data.layout.instances.stream().filter(g -> g.object().toLowerCase().startsWith("start")).findFirst().orElse(null);
		}
		if (start != null) {
			spawn.set(start.pos()[0], start.pos()[1], start.pos()[2]);
			spawnYaw = start.rot()[1];
		}
	}

	private void respawn(Vector3f at) {
		hero.ctl.position.set(at);
		hero.ctl.prevPosition.set(at);
		hero.ctl.yaw = spawnYaw;
		hero.ctl.vy = 0;
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
			public void vis(ScriptEntity source, List<String> objects, int[] values) {
				if (values.length > 4 && values[4] != 0) {
					objects.forEach(o -> cameraRig.setOwnerVolumes(o, values[4] == 1)); // Attr11_1: CMV 볼륨 on/off
				}
			}

			@Override
			public void internal(int func, List<Object> args) {
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
				switch (r.op()) {
					case LOAD_WAD, LOAD_FOR_WARP -> {
						pendingLevel = r.wad();
						System.out.println("다음 레벨 미리 로드 요청: " + r.wad());
					}
					case WARP -> {
						if (pendingLevel != null) {
							goToLevel(pendingLevel, null);
						}
					}
					case LOAD -> goToLevel(r.wad(), r.entry());
					default -> {
					}
				}
			}

			@Override
			public boolean loadCheck(String wad) {
				// 원작은 백그라운드 로드 완료 여부. 여기서는 WAD 가 있으면 즉시 준비된 것으로 본다
				return Files.exists(session.rom.resolve(wad.toUpperCase() + ".WAD"));
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
			public void spawnEnemy(SpawnRequest r) {
				spawn(r);
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

	private Engine pendingEngine;

	private void spawn(ScriptListener.SpawnRequest r) {
		if (kinds.isEmpty()) {
			return;
		}
		// TemplateId → 적 종류 매핑은 미해결 (13 문서 공백) → AI1 번호가 같은 종류, 없으면 레벨의 첫 종류
		EnemyFactory.Kind kind = kinds.values().stream().filter(k -> k.hasTemplate(r.templateId())).findFirst().orElse(null);
		EnemyFighter f;
		if (kind != null) {
			f = kind.spawn(r.templateId());
		} else {
			// 레벨 WAD 자체에 정의된 AI1 템플릿 (예 ATHN01D 의 301 "AOXF1") → 레벨 튜닝값을 첫 적 종류에 합쳐 그 템플릿으로
			kind = kinds.values().iterator().next();
			kind.tweaks().load(level.data().wad);
			f = kind.hasTemplate(r.templateId()) ? kind.spawn(r.templateId()) : kind.spawn();
		}
		float[] w = r.world();
		f.pos.set(w[12], w[13], w[14]);
		f.prevPos.set(f.pos);
		f.setFoe(hero.hittable());
		f.combat().onSound((h, p) -> session.sound.play(h, p));
		f.setIncomingScale(hero::incomingScale);
		enemies.add(new Enemy(f, kind.buildModel(enemyShader), r.actor(), kind));
		System.out.printf("적 스폰: %s (템플릿 %d, 순찰 %s) at (%.0f, %.0f, %.0f)%n", kind.assets().wad().name(), r.templateId(),
				r.patrol(), f.pos.x, f.pos.y, f.pos.z);
	}

	private void showMessage(int id, float seconds) {
		String text = session.messages != null ? session.messages.get(id) : null;
		if (text == null) {
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
	 * 세이브 포인트 저장 (원작 상태 15 SavePoint → 17 SaveWrite). 원작 메모리카드 슬롯 UI(SaveGameMenu/SaveLoadMenu)는
	 * 생략하고 슬롯 0 에 바로 저장한다.
	 */
	private void save() {
		try {
			Vector3f p = hero.ctl.position;
			SaveGame.capture(session, levelName, new float[]{p.x, p.y, p.z}, hero.ctl.yaw).write(SaveGame.slot(0));
			checkpoint.set(p);
			System.out.println("저장 완료: " + SaveGame.slot(0));
		} catch (IOException ex) {
			System.out.println("저장 실패: " + ex.getMessage());
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
		time += dt;
		if (System.getProperty("gow.debug") != null && ((int) (time * 60)) % 60 == 0) {
			Vector3f hp = hero.ctl.position;
			System.out.printf("[디버그] t=%.0f 영웅 (%.0f, %.0f, %.0f) 지면=%s 바닥높이=%.1f 소리=%d 음악=%s 백엔드=%s%n", time, hp.x, hp.y,
					hp.z, hero.ctl.grounded, col.groundHeight(hp.x, hp.y + STEP, hp.z, 4000), session.sound.active(),
					session.sound.currentMusic(), session.sound.backend().getClass().getSimpleName());
		}
		if (!powerUp) {
			hudBinding.update(hero.player, hud.variables());
		}
		hud.update(e);
		if (messageTimer > 0 && (messageTimer -= dt) <= 0) {
			hud.variables().event("PS2_MessageTemplate_Event", 0);
		}
		if (paused || leaving) {
			return;
		}
		if (e.input().pressed(PadButton.START)) {
			paused = true;
			hud.variables().event("PS2_PauseMenu_Event", 1);
			return;
		}
		if (e.input().pressed(PadButton.R1) && !hero.combat.active() && nearSavePoint()) {
			save();
		}
		if (e.input().pressed(PadButton.SELECT)) {
			paused = powerUp = true;
			HudBinding.write(hero.player, hud.variables()); // Progress_ToHud
			hud.variables().event("PS2_PSMMenu_Event", 1);
			return;
		}
		Input in = e.input();
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
			physics(dt);
		}
		updateEnemies(dt);
		if (target != null) {
			Enemy te = enemies.stream().filter(x -> x.fighter() == target).findFirst().orElse(null);
			boolean was = grab.active();
			grab.update(in, target, te != null ? te.kind().tweaks() : null, dt);
			if (was && !grab.active() && grab.lastResult() == devple.gow.engine.combat.RepeatPressQte.State.PASSED) {
				target.hp = 0; // 처형 성공 → 사망 처리 (원작은 처형 기술의 피해/스크립트) [근사]
			}
		}
		Vector3f p = hero.ctl.position;
		cameraRig.update(dt, p, hero.ctl.yaw, !hero.ctl.grounded);
		world.tick(dt, p.x, p.y, p.z);
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
		for (Enemy en : enemies) {
			EnemyFighter f = en.fighter();
			boolean wasDead = f.dead();
			f.update(dt);
			float g = col.groundHeight(f.pos.x, f.pos.y + STEP, f.pos.z, 400);
			if (!Float.isNaN(g)) {
				f.pos.y = g;
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
	 * 표면 = 충돌 Sheet 속성(미해석) → 현재 PLANK(나무 갑판 등) 고정. 주기 = 이동 클립 길이의 절반 (한 걸음).
	 */
	private void footsteps(float dt) {
		var c = hero.ctl;
		String surface = "SND_FOOTSTEP_PLANK";
		if (!c.grounded) {
			wasAir = true;
			return;
		}
		if (wasAir) {
			wasAir = false;
			session.sound.play(surface, c.position, 3); // LAND
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
		}
	}

	/** 지면 추종 + 중력 + 벽 밀어내기. */
	private void physics(float dt) {
		Vector3f p = hero.ctl.position;
		// 허공 방지: 지상에서 이동한 결과 아래에 바닥이 전혀 없으면(배 가장자리 밖 등) 수평 이동 취소.
		// 원작의 보이지 않는 벽 / 바다 데스존을 대신하는 근사 — 아래층 바닥이 있는 낙하는 허용
		if (hero.ctl.grounded && Float.isNaN(col.groundHeight(p.x, p.y + STEP, p.z, 4000))
				&& !Float.isNaN(col.groundHeight(hero.ctl.prevPosition.x, hero.ctl.prevPosition.y + STEP, hero.ctl.prevPosition.z, 4000))) {
			p.x = hero.ctl.prevPosition.x;
			p.z = hero.ctl.prevPosition.z;
		}
		float g = col.groundHeight(p.x, p.y + STEP, p.z, 4000);
		var c0 = hero.ctl;
		if (!Float.isNaN(g) && p.y - g <= 2f && c0.vy <= 0) {
			p.y = g;
			c0.vy = 0;
			c0.grounded = true;
		} else {
			c0.vy -= GRAVITY * dt;
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
		Vector3f c = new Vector3f(p.x, p.y + 20, p.z);
		if (col.collideSphere(c, 12)) {
			p.x = c.x;
			p.z = c.z;
		}
	}

	private void updateCamera(float alpha) {
		// 원작 카메라 시스템 (CMV 선택 + 레일/궤도 + 전환 보간): CameraRig, 30 문서. 갱신은 update() 틱에서
		cameraRig.apply(cam, alpha);
		camYaw = (float) Math.atan2(-(cam.target.x - cam.eye.x), -(cam.target.z - cam.eye.z));
	}

	@Override
	public void render(Engine e, float alpha) {
		updateCamera(alpha);
		level.render(e, alpha);
		// 캐릭터에도 레벨 안개 (같은 basic.frag 유니폼)
		var env = level.fx().env();
		env.applyFog(hero.shader(), cam, e.window().height());
		env.applyFog(enemyShader, cam, e.window().height());
		Matrix4f m = new Matrix4f();
		for (Enemy en : enemies) {
			en.model().draw(cam, e.window().aspect(), en.fighter().world(m, alpha), en.fighter().animator().pose(),
					e.renderer().white());
		}
		hero.draw(cam, e.window().aspect(), alpha, e.renderer().white());
		devple.gow.engine.fx.FxEnvironment.disableFog(hero.shader());
		devple.gow.engine.fx.FxEnvironment.disableFog(enemyShader);
		level.renderEffects(e, cam); // 비·파티클·물은 캐릭터 뒤에
		hud.render(e);
	}

	@Override
	public void exit(Engine e) {
		session.sound.stopAll();
		session.sound.unload(level.data().wad.name());
		kinds.values().forEach(k -> session.sound.unload(k.assets().wad().name()));
		enemies.forEach(en -> en.model().close());
		enemyShader.close();
		hero.close();
		hud.close();
		level.exit(e);
	}
}
