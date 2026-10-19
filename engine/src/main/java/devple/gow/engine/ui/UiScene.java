package devple.gow.engine.ui;

import devple.gow.engine.core.Engine;
import devple.gow.engine.game.GameRules;
import devple.gow.engine.game.PlayerState;
import devple.gow.engine.game.Weapon;
import devple.gow.engine.scene.Scene;
import devple.gow.tools.wad.WadFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * FLP UI 단독 실행 씬 ({@code --ui <wad> <FLP> [mode]}). 검증/디버그용.
 * <ul>
 *   <li>menu (기본): 무비를 그대로 재생 — R_SHELLK/FLP_ShellK 메인 메뉴. 방향키 ↑↓, L = ○ 선택, I = △ 뒤로</li>
 *   <li>hud: 데모 PlayerState 를 {@link HudBinding} 으로 연결 (체력/마법 바, 오브 미터)</li>
 *   <li>pause / powerup: hud + PS2_PauseMenu_Event / PS2_PSMMenu_Event = 1</li>
 *   <li>icon:&lt;이름&gt;: hud + 미니게임 아이콘 심볼 ({@link MinigameIcon#CLIPS}, 예 icon:CircleButtonGreek)</li>
 *   <li>meter: 레벨 FLP (예 ATHN01E FLP_Athn01E) 보스 체력 미터 1 을 켜고 60 으로</li>
 *   <li>memcard: 메인 메뉴 + 불러오기 메모리카드 화면 ({@link devple.gow.engine.game.MemoryCardFlow}) — 견본 세이브 2개가 든
 *       임시 카드로 파일 선택 화면(23)까지 진행</li>
 *   <li>bonus: 메인 메뉴 → 귀중품 화면 ({@link devple.gow.engine.game.BonusMenu}, 전부 해금) 에서 커서를 3칸 내린 상태 (--ui-warm 400 이상)
 *       (영상 선택은 로그만)</li>
 * </ul>
 * 스크린샷이 결정적이도록 enter 에서 warm 틱만큼 미리 진행한다.
 */
public final class UiScene implements Scene {

	private final Path wad;
	private final String flpName;
	private final String mode;
	private final int warm;
	private UiLayer ui;
	private PlayerState ps;
	private final HudBinding hud = new HudBinding();
	private devple.gow.engine.game.MemoryCardFlow card;
	private devple.gow.engine.game.BonusMenu bonus;

	public UiScene(Path wad, String flpName, String mode, int warm) {
		this.wad = wad;
		this.flpName = flpName;
		this.mode = mode == null ? "menu" : mode;
		this.warm = warm;
	}

	@Override
	public void enter(Engine e) {
		try {
			ui = UiLayer.load(wad, flpName, null);
			if (mode.equals("memcard")) {
				devple.gow.engine.game.ShellBinding.init(ui.variables(), 0);
				card = demoCard();
			} else if (mode.equals("bonus")) {
				int bits = devple.gow.engine.game.BonusMenu.ALL_UNLOCKED;
				devple.gow.engine.game.ShellBinding.init(ui.variables(), bits);
				bonus = new devple.gow.engine.game.BonusMenu(ui.variables(), ui.player().messages(), bits,
						new devple.gow.engine.game.BonusMenu.Host() {
							@Override
							public void playMovie(String name, java.util.function.Consumer<Boolean> done) {
								System.out.println("[UI] 보너스 영상 " + name);
								done.accept(false);
							}

							@Override
							public void startLevel(String wad) {
								System.out.println("[UI] 보너스 레벨 " + wad);
							}
						});
				ui.player().addCommandListener(bonus::command);
			} else if (!mode.equals("menu")) {
				Path perm = Files.exists(wad.resolveSibling("R_PERMK.WAD")) ? wad.resolveSibling("R_PERMK.WAD") : wad;
				ps = new PlayerState(GameRules.load(WadFile.load(perm)));
				demoState(ps);
			}
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		ui.player().addCommandListener((c, a) -> System.out.println("[UI] FSCommand " + c + " " + a));
		ui.variables().addListener((n, v) -> System.out.println("[UI] " + n + " = " + v));
		for (int i = 0; i < warm; i++) {
			if (ps != null) {
				hud.update(ps, ui.variables());
			}
			if (card != null && i == 20) {
				card.start();
			} else if (card != null && i == 50) {
				card.command("PS2_MemoryCard_Select", "1");
			}
			if (mode.equals("meter")) {
				if (i == 20) {
					ui.variables().event("PS2_EnemyMeter_Event1", 1);
				} else if (i == 60) {
					ui.variables().set("PS2_EnemyMeter_Value1", Integer.getInteger("gow.meter", 60));
					ui.variables().event("PS2_EnemyMeter_Event1", 2);
				}
			}
			if (i == 20 && mode.startsWith("icon:")) {
				ui.player().attach(mode.substring(5));
			}
			if (i == 20 && mode.equals("pause")) {
				ui.variables().event("PS2_PauseMenu_Event", 1);
			} else if (i == 20 && mode.equals("powerup")) {
				HudBinding.write(ps, ui.variables());
				ui.variables().event("PS2_PSMMenu_Event", 1);
			}
			// bonus: ↓×3 → ○ (귀중품 열기) → ↓×3
			int[] bonusKeys = {UiPad.DOWN, UiPad.DOWN, UiPad.DOWN, UiPad.CIRCLE, 0, UiPad.DOWN, UiPad.DOWN, UiPad.DOWN};
			int k = (i - 20) / 45;
			boolean key = bonus != null && i >= 20 && (i - 20) % 45 == 0 && k < bonusKeys.length;
			ui.player().pad(key ? bonusKeys[k] : 0);
			ui.player().tick();
			if (bonus != null) {
				bonus.update(1 / 60f);
			}
		}
		if (System.getProperty("gow.ui.dump") != null || System.getenv("GOW_UI_DUMP") != null) {
			ui.player().dump(System.out::println);
		}
	}

	private devple.gow.engine.game.MemoryCardFlow demoCard() throws IOException {
		Path dir = Files.createTempDirectory("gow-card");
		float[][] demo = {{1, 3723}, {}, {3, 45296}};
		for (int i = 0; i < demo.length; i++) {
			if (demo[i].length == 0) {
				continue;
			}
			devple.gow.engine.game.SaveGame g = new devple.gow.engine.game.SaveGame();
			g.level = "ATHN01A";
			g.selectedMagic = "";
			g.difficulty = (int) demo[i][0];
			g.playTime = demo[i][1];
			g.write(devple.gow.engine.game.MemoryCardFlow.file(dir, i));
		}
		var f = devple.gow.engine.game.MemoryCardFlow.load(ui.variables(), ui.player().messages(), dir);
		ui.player().addCommandListener(f::command);
		return f;
	}

	/** 데모 값: 체력 레벨 1 (최대 125) 중 100, 마법 레벨 2 (최대 125) 중 90, 붉은 오브 1234. */
	private static void demoState(PlayerState ps) {
		ps.hpLevel = 1;
		ps.hp = 100;
		ps.mpLevel = 2;
		ps.mp = 90;
		ps.redOrbs = 1234;
		ps.itemFlags = 0b1111;
		ps.level.put(Weapon.BLADES, 1);
		ps.invested.put(Weapon.BLADES, 300);
	}

	@Override
	public void update(Engine e, float dt) {
		if (ps != null) {
			hud.update(ps, ui.variables());
		}
		ui.update(e);
		if (card != null) {
			card.update(dt);
		}
		if (bonus != null) {
			bonus.update(dt);
		}
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
