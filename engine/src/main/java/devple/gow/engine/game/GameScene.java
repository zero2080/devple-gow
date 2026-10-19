package devple.gow.engine.game;

import devple.gow.engine.core.Engine;
import devple.gow.engine.input.PadButton;
import devple.gow.engine.level.LevelCollision;
import devple.gow.engine.level.LevelScene;
import devple.gow.engine.render.Camera;
import devple.gow.engine.scene.Scene;
import devple.gow.engine.ui.HudBinding;
import devple.gow.engine.ui.UiLayer;
import devple.gow.engine.ui.UiVariables;
import devple.gow.tools.level.GoInstance;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.ui.MessageTable;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * 통합 게임 씬: 원본 레벨(배치·충돌·카메라) + 크레토스(이동·전투·성장) + 원작 HUD/일시정지 메뉴.
 * <ul>
 *   <li>지면: 충돌 시트의 바닥 높이 추종 + 중력 (9.82 m/s², 1m ≈ 16 유닛 — Orb 템플릿 Gravity 9.82 와 같은 단위계로 추정)</li>
 *   <li>벽: 허리 높이 구(반지름 12) 밀어내기</li>
 *   <li>카메라: 원작처럼 CMV 볼륨이 고른 레벨 카메라 위치에서 크레토스를 바라봄. 해당 볼륨이 없으면 추적 카메라</li>
 *   <li>START = 원작 일시정지 메뉴 (FLP_HUDK), 메뉴가 끝나면 복귀</li>
 * </ul>
 */
public final class GameScene implements Scene {

	public static final float UNITS_PER_METER = 16f;
	private static final float GRAVITY = 9.82f * UNITS_PER_METER;
	private static final float STEP = 14f;

	private final Path rom;
	private final String levelName;
	private final float autoRun;
	private LevelScene level;
	private LevelCollision col;
	private HeroRig hero;
	private UiLayer hud;
	private final HudBinding hudBinding = new HudBinding();
	private final Camera cam = new Camera();
	private final Vector3f spawn = new Vector3f();
	private float spawnYaw;
	private float vy;
	private float camYaw;
	private boolean paused;
	private float time;

	/** @param autoRun 자동 검증용: 시작 후 이 시간 동안 앞으로 달린다 (0 = 없음) */
	public GameScene(Path rom, String levelName, float autoRun) {
		this.rom = rom;
		this.levelName = levelName.toUpperCase();
		this.autoRun = autoRun;
	}

	@Override
	public void enter(Engine e) {
		try {
			WadFile perm = WadFile.load(rom.resolve("R_PERMK.WAD"));
			level = new LevelScene(rom.resolve(levelName + ".WAD"), -2, false);
			level.enter(e);
			level.setExternalCamera(cam);
			col = level.collision();
			hero = new HeroRig(rom, perm, 0);
			UiVariables vars = new UiVariables();
			hud = new UiLayer(new devple.gow.engine.asset.WadAssets(rom.resolve("R_PERMK.WAD")), true, "FLP_HUDK",
					MessageTable.fromWad(perm).orElse(null), vars);
			vars.addListener((name, value) -> {
				if (paused && (name.endsWith("Done") || name.endsWith("Menu_Choice"))) {
					onMenuClosed(name, value);
				}
			});
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		LevelData data = level.data();
		GoInstance start = data.layout.instances.stream().filter(g -> g.object().toLowerCase().startsWith("start"))
				.findFirst().orElse(null);
		if (start != null) {
			spawn.set(start.pos()[0], start.pos()[1], start.pos()[2]);
			spawnYaw = start.rot()[1];
		}
		respawn();
		cam.fovDeg = 60;
		cam.near = 2;
		cam.far = 30000;
		System.out.printf("게임 시작: %s, 시작 위치 (%.0f, %.0f, %.0f)%n", levelName, spawn.x, spawn.y, spawn.z);
	}

	private void respawn() {
		hero.ctl.position.set(spawn);
		hero.ctl.prevPosition.set(spawn);
		hero.ctl.yaw = spawnYaw;
		vy = 0;
		hero.player.hp = Math.max(hero.player.hp, hero.player.maxHp());
	}

	private void onMenuClosed(String name, Object value) {
		paused = false;
		hud.acceptInput = true;
		System.out.println("메뉴 닫힘: " + name + " = " + value);
		// 일시정지 메뉴 선택 1 = 마지막 체크포인트에서 다시 시작 (11 문서 §6.1 상태 12)
		if (name.endsWith("Menu_Choice") && "1".equals(String.valueOf(value).replace(".0", ""))) {
			respawn();
		}
	}

	@Override
	public void update(Engine e, float dt) {
		time += dt;
		hudBinding.update(hero.player, hud.variables());
		hud.update(e);
		if (paused) {
			return;
		}
		if (e.input().pressed(PadButton.START)) {
			paused = true;
			hud.variables().event("PS2_PauseMenu_Event", 1);
			return;
		}
		devple.gow.engine.input.Input in = e.input();
		if (time < autoRun) {
			in = new devple.gow.engine.input.Input(e.window().handle()) {
				@Override
				public float leftY() {
					return 1f;
				}
			};
		}
		hero.update(in, camYaw, dt, null);
		physics(dt);
		if (hero.player.hp <= 0) {
			hero.player.deaths++;
			respawn();
		}
	}

	/** 지면 추종 + 중력 + 벽 밀어내기. */
	private void physics(float dt) {
		Vector3f p = hero.ctl.position;
		float g = col.groundHeight(p.x, p.y + STEP, p.z, 4000);
		if (!Float.isNaN(g) && p.y - g <= 2f && vy <= 0) {
			p.y = g;
			vy = 0;
		} else {
			vy -= GRAVITY * dt;
			p.y += vy * dt;
			if (!Float.isNaN(g) && p.y < g) {
				p.y = g;
				vy = 0;
			}
		}
		if (Float.isNaN(g) && p.y < spawn.y - 3000) {
			respawn(); // 바닥 없는 곳으로 떨어짐
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
		float fx = cam.target.x - cam.eye.x, fz = cam.target.z - cam.eye.z;
		camYaw = (float) Math.atan2(-fx, -fz);
	}

	@Override
	public void render(Engine e, float alpha) {
		updateCamera(alpha);
		level.render(e, alpha);
		hero.draw(cam, e.window().aspect(), alpha, e.renderer().white());
		hud.render(e);
	}

	@Override
	public void exit(Engine e) {
		hero.close();
		hud.close();
		level.exit(e);
	}
}
