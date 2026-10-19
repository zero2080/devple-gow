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
	private final Vector3f spawn = new Vector3f();
	private final Vector3f checkpoint = new Vector3f();
	private float spawnYaw;
	private float camYaw;
	private boolean paused;
	private float time;
	private float messageTimer;
	private String pendingLevel;
	private boolean leaving;
	private final Map<String, EnemyFactory.Kind> kinds = new HashMap<>();
	private final List<Enemy> enemies = new ArrayList<>();
	private Shader enemyShader;
	private Tweaks orbTweaks;
	private final Random rnd = new Random();

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
			col = level.collision();
			hero = new HeroRig(session.rom, session.permk, 0, session.player);
			hero.difficulty = session.difficulty;
			session.player = hero.player;
			UiVariables vars = new UiVariables();
			hud = new UiLayer(new WadAssets(session.rom.resolve("R_PERMK.WAD")), true, "FLP_HUDK", session.messages, vars);
			vars.addListener((name, value) -> {
				if (paused && (name.endsWith("Done") || name.endsWith("Menu_Choice"))) {
					onMenuClosed(name, value);
				}
			});
			enemyShader = new Shader("skinned.vert", "basic.frag");
			orbTweaks = new Tweaks();
			orbTweaks.load(session.permk);
			orbTweaks.load(level.data().wad);
			loadEnemyKinds();
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		placeSpawn();
		respawn(spawn);
		checkpoint.set(spawn);
		cam.fovDeg = 60;
		cam.near = 2;
		cam.far = 30000;
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
					} catch (RuntimeException ex) {
						System.out.println("적 리소스 로드 실패 " + name + ": " + ex.getMessage());
					}
				}
			}
		}
	}

	private void placeSpawn() {
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
		};
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
		EnemyFactory.Kind kind = kinds.values().stream().filter(k -> k.ai1() == r.templateId()).findFirst()
				.orElse(kinds.values().iterator().next());
		EnemyFighter f = kind.spawn();
		float[] w = r.world();
		f.pos.set(w[12], w[13], w[14]);
		f.prevPos.set(f.pos);
		f.setFoe(hero.hittable());
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

	private void heroDied() {
		hero.player.deaths++;
		hero.player.onCheckpointRestart(hero.player.hp);
		respawn(checkpoint);
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
		hudBinding.update(hero.player, hud.variables());
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
		hero.combat.setTarget(target);
		hero.update(in, camYaw, dt, target != null ? target.pos : null);
		physics(dt);
		updateEnemies(dt);
		Vector3f p = hero.ctl.position;
		world.tick(dt, p.x, p.y, p.z);
		if (hero.player.hp <= 0) {
			heroDied();
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

	/** 지면 추종 + 중력 + 벽 밀어내기. */
	private void physics(float dt) {
		Vector3f p = hero.ctl.position;
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
		Vector3f p = new Vector3f(hero.ctl.prevPosition).lerp(hero.ctl.position, alpha);
		LevelData.PlacedCamera lc = level.data().activeCamera(p.x, p.y + 10, p.z);
		if (lc != null) {
			float[] eye = lc.eye();
			cam.eye.set(eye[0], eye[1], eye[2]);
		} else {
			float d = 140;
			cam.eye.set(p.x + (float) Math.sin(hero.ctl.yaw) * d, p.y + 70, p.z + (float) Math.cos(hero.ctl.yaw) * d);
		}
		cam.target.set(p.x, p.y + 22, p.z);
		camYaw = (float) Math.atan2(-(cam.target.x - cam.eye.x), -(cam.target.z - cam.eye.z));
	}

	@Override
	public void render(Engine e, float alpha) {
		updateCamera(alpha);
		level.render(e, alpha);
		Matrix4f m = new Matrix4f();
		for (Enemy en : enemies) {
			en.model().draw(cam, e.window().aspect(), en.fighter().world(m, alpha), en.fighter().animator().pose(),
					e.renderer().white());
		}
		hero.draw(cam, e.window().aspect(), alpha, e.renderer().white());
		hud.render(e);
	}

	@Override
	public void exit(Engine e) {
		enemies.forEach(en -> en.model().close());
		enemyShader.close();
		hero.close();
		hud.close();
		level.exit(e);
	}
}
