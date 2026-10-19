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
 * 불러오기 = 메뉴의 메모리카드 화면을 {@link MemoryCardFlow} 로 진행 (카드 → 파일 → 확인 → 불러오는 중), △ 취소면 메뉴로 복귀.
 */
public final class ShellScene implements Scene {

	private final Path rom;
	private UiLayer ui;
	private boolean started;
	private MemoryCardFlow card;

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
		var snd = GameSession.sharedSound(rom);
		ShellBinding.init(v, 0);
		ShellBinding.bindAudio(v, snd);
		try {
			if (!snd.loaded().containsKey("R_PERMK.WAD")) {
				snd.load(devple.gow.tools.wad.WadFile.load(rom.resolve("R_PERMK.WAD")));
			}
			snd.load(devple.gow.tools.wad.WadFile.load(rom.resolve("R_SHELLK.WAD")));
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		v.addListener((name, value) -> {
			if (name.regionMatches(true, 0, "PS2_Sound", 0, 9) && value != null && value.startsWith("SND_")) {
				snd.play(value);
			}
		});
		ui.player().addCommandListener((cmd, arg) -> {
			if (card != null) {
				card.command(cmd, arg);
			}
		});
		v.addListener((name, value) -> {
			if (name.equals("PS2_Done") && "1".equals(num(value)) && !started) {
				String choice = num(v.get("PS2_Menu_Choice"));
				if ("2".equals(choice)) {
					if (card == null) {
						card = MemoryCardFlow.load(v, ui.player().messages(), SaveGame.dir());
						card.start();
					}
				} else if ("1".equals(choice)) {
					startGame(e, parseInt(num(v.get("PS2_Difficulty_Level")), 1));
				}
			}
		});
	}

	private void startGameFromCard(Engine e) {
		if (card == null) {
			return;
		}
		switch (card.result()) {
			case LOADED -> {
				SaveGame g = card.loaded();
				card = null;
				loadGame(e, g);
			}
			case CANCELLED -> card = null; // 메뉴는 SaveLoad_Off → 메인 메뉴로 스스로 돌아간다
			default -> {
			}
		}
	}

	private void startGame(Engine e, int difficulty) {
		started = true;
		try {
			GameSession s = new GameSession(rom);
			s.difficulty = Math.max(0, Math.min(3, difficulty));
			s.openingPending = true;
			System.out.println("새 게임: 난이도 " + s.difficulty + " (" + devple.gow.engine.combat.DamageRules.DIFFICULTY[s.difficulty] + ")");
			e.scenes().transitionTo(new GameScene(s, GameFlow.START_LEVEL, null, 0));
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** 불러오기 (원작 상태 5 LoadCard → 8 LoadApply): 고른 세이브로 세션 복원 후 저장 위치에서 시작. */
	private void loadGame(Engine e, SaveGame g) {
		started = true;
		try {
			GameSession s = new GameSession(rom);
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
		if (card != null) {
			card.update(dt);
			startGameFromCard(e);
		}
		GameSession.sharedSound(rom).update(dt);
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
