package devple.gow.engine.game;

import devple.gow.engine.core.Engine;
import devple.gow.engine.scene.Scene;
import devple.gow.engine.ui.UiLayer;
import devple.gow.engine.ui.UiVariables;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * 셸(메인 메뉴) — 원작 상태 1–3 (ShellLoad → ShellMenu, 11 문서 §6.1). R_SHELLK / FLP_ShellK 를 그대로 실행한다.
 * 메뉴가 {@code PS2_Menu_Choice}(1 새 게임, 2 불러오기) + {@code PS2_Done = 1} 을 쓰면 다음 상태로.
 * 새 게임 = 난이도 {@code PS2_Difficulty_Level} 로 {@link GameFlow#START_LEVEL} 진입.
 */
public final class ShellScene implements Scene {

	private final Path rom;
	private UiLayer ui;
	private boolean started;

	public ShellScene(Path rom) {
		this.rom = rom;
	}

	@Override
	public void enter(Engine e) {
		try {
			ui = UiLayer.load(rom.resolve("R_SHELLK.WAD"), "FLP_ShellK", null);
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		UiVariables v = ui.variables();
		v.addListener((name, value) -> {
			if (name.equals("PS2_Done") && "1".equals(num(value)) && !started) {
				String choice = num(v.get("PS2_Menu_Choice"));
				if ("2".equals(choice) && java.nio.file.Files.exists(SaveGame.slot(0))) {
					loadGame(e);
				} else if ("1".equals(choice) || "2".equals(choice)) {
					startGame(e, parseInt(num(v.get("PS2_Difficulty_Level")), 1));
				}
			}
		});
	}

	private void startGame(Engine e, int difficulty) {
		started = true;
		try {
			GameSession s = new GameSession(rom);
			s.difficulty = Math.max(0, Math.min(3, difficulty));
			System.out.println("새 게임: 난이도 " + s.difficulty + " (" + devple.gow.engine.combat.DamageRules.DIFFICULTY[s.difficulty] + ")");
			e.scenes().transitionTo(new GameScene(s, GameFlow.START_LEVEL, null, 0));
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** 불러오기 (원작 상태 5 LoadCard → 8 LoadApply): 슬롯 0 의 세이브로 세션 복원 후 저장 위치에서 시작. */
	private void loadGame(Engine e) {
		started = true;
		try {
			GameSession s = new GameSession(rom);
			SaveGame g = SaveGame.read(SaveGame.slot(0));
			g.applyTo(s);
			System.out.println("불러오기: " + g.level + " 붉은 오브 " + s.player.redOrbs);
			e.scenes().transitionTo(new GameScene(s, g));
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static String num(Object o) {
		return o == null ? null : String.valueOf(o).replaceAll("\\.0$", "");
	}

	private static int parseInt(String s, int def) {
		try {
			return Integer.parseInt(s);
		} catch (RuntimeException ex) {
			return def;
		}
	}

	@Override
	public void update(Engine e, float dt) {
		ui.update(e);
	}

	@Override
	public void render(Engine e, float alpha) {
		ui.render(e);
	}

	@Override
	public void exit(Engine e) {
		ui.close();
	}
}
