package devple.gow.engine.game;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 게임 상태 머신 (원작 GameState_Tick 0x21DB20 의 전이). 화면/로딩은 리스너가 담당하고 여기서는 전이 규칙만 둔다.
 * 레벨 전환은 스크립트의 GameMgr 메시지(LoadWad / Warp ...)로만 일어난다 (11 문서 §6.2).
 */
public final class GameFlow {

	/** 원작 새 게임 시작 레벨 (0x2B9740 "Athn01A"). */
	public static final String START_LEVEL = "ATHN01A";

	public enum MenuChoice { NEW_GAME, LOAD, CONTINUE, RESTART_CHECKPOINT, QUIT_TO_SHELL }

	private final PlayerState player;
	private final List<Consumer<GameState>> listeners = new ArrayList<>();
	private GameState state = GameState.BOOT;
	private String level;
	private String pendingLevel;
	private int difficulty;
	private boolean loadComplete;

	public GameFlow(PlayerState player) {
		this.player = player;
	}

	public void onStateChange(Consumer<GameState> l) {
		listeners.add(l);
	}

	public GameState state() {
		return state;
	}

	public String level() {
		return level;
	}

	public String pendingLevel() {
		return pendingLevel;
	}

	public int difficulty() {
		return difficulty;
	}

	private void go(GameState s) {
		state = s;
		listeners.forEach(l -> l.accept(s));
	}

	/** 매 틱. 자동 전이(부트 → 셸, 로딩 완료 → 플레이). */
	public void tick() {
		switch (state) {
			case BOOT -> go(GameState.SHELL_LOAD);
			case SHELL_LOAD -> go(GameState.SHELL_MENU);
			case NEW_GAME -> {
				level = START_LEVEL;
				loadComplete = false;
				go(GameState.LEVEL_LOADING);
			}
			case LEVEL_LOADING -> {
				if (loadComplete) {
					go(GameState.IN_GAME);
				}
			}
			default -> {
			}
		}
	}

	/** 레벨 로더가 로딩 완료를 알린다. */
	public void levelLoaded() {
		loadComplete = true;
	}

	public void shellMenu(MenuChoice c, int difficulty) {
		if (state != GameState.SHELL_MENU) {
			return;
		}
		if (c == MenuChoice.NEW_GAME) {
			this.difficulty = difficulty;
			player.costume = 0;
			go(GameState.NEW_GAME);
		} else if (c == MenuChoice.LOAD) {
			go(GameState.LOAD_CARD);
		}
	}

	/** 원작 패드 0x100(START) → 일시정지, 0x800(SELECT) → 파워업 메뉴. */
	public void pausePressed() {
		if (state == GameState.IN_GAME) {
			go(GameState.PAUSE);
		}
	}

	public void powerUpPressed() {
		if (state == GameState.IN_GAME) {
			go(GameState.POWER_UP_MENU);
		}
	}

	public void menuClosed(MenuChoice c) {
		switch (state) {
			case PAUSE -> {
				switch (c) {
					case RESTART_CHECKPOINT -> {
						loadComplete = false;
						go(GameState.LEVEL_LOADING);
					}
					case QUIT_TO_SHELL -> go(GameState.SHELL_LOAD);
					default -> go(GameState.IN_GAME);
				}
			}
			case POWER_UP_MENU, SAVE_DONE -> go(GameState.IN_GAME);
			case DEAD -> {
				if (c == MenuChoice.QUIT_TO_SHELL) {
					go(GameState.SHELL_LOAD);
				} else {
					loadComplete = false;
					go(GameState.LEVEL_LOADING);
				}
			}
			default -> {
			}
		}
	}

	public void playerDied() {
		if (state == GameState.IN_GAME) {
			player.deaths++;
			go(GameState.DEAD);
		}
	}

	public void savePointActivated() {
		if (state == GameState.IN_GAME) {
			go(GameState.SAVE_POINT);
		}
	}

	public void saveFinished() {
		if (state == GameState.SAVE_POINT || state == GameState.SAVE_WRITE) {
			go(GameState.SAVE_DONE);
		}
	}

	// ---- GameMgr 메시지 (ESC Internal:N) ----

	/** 3 LoadWad / 4 LoadForWarp: 다음 구역 WAD 를 미리 로드. */
	public void msgLoadWad(String wad) {
		pendingLevel = wad.toUpperCase();
	}

	/** 5 Warp: 미리 로드한 WAD 로 전환. */
	public void msgWarp() {
		if (pendingLevel != null) {
			level = pendingLevel;
			pendingLevel = null;
			loadComplete = false;
			go(GameState.LEVEL_LOADING);
		}
	}

	/** 6 LoadCheck: 다음 WAD 가 준비됐는지. */
	public boolean msgLoadCheck(String wad) {
		return wad.equalsIgnoreCase(pendingLevel) && loadComplete;
	}

	/** 2 Load(wad, entry): 즉시 레벨 교체. */
	public void msgLoad(String wad) {
		level = wad.toUpperCase();
		pendingLevel = null;
		loadComplete = false;
		go(GameState.LEVEL_LOADING);
	}

	public List<String> requiredWads() {
		return WadPlan.required(state, player, level, pendingLevel);
	}
}
