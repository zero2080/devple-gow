package devple.gow.engine.game;

/** 원작 g_gameState(0x29F428) 의 상태. 번호는 원작 값 (11 문서 §6.1). */
public enum GameState {
	BOOT(0), SHELL_LOAD(1), SHELL_CARD_CHECK(2), SHELL_MENU(3), NEW_GAME(4), LOAD_CARD(5), LOAD_FADE(6),
	LOAD_CANCEL(7), LOAD_APPLY(8), LEVEL_LOADING(9), LEVEL_WAIT(10), IN_GAME(11), PAUSE(12), POWER_UP_MENU(13),
	DEAD(14), SAVE_POINT(15), SAVE_WRITE(17), SAVE_DONE(18);

	public final int id;

	GameState(int id) {
		this.id = id;
	}

	/** 레벨이 메모리에 있어야 하는 상태. */
	public boolean inLevel() {
		return id >= IN_GAME.id;
	}
}
