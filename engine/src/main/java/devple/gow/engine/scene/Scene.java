package devple.gow.engine.scene;

import devple.gow.engine.core.Engine;

/** 씬 = 게임의 한 상태 (타이틀, 메뉴, 레벨, 컷신 ...). */
public interface Scene {

	default void enter(Engine engine) {
	}

	/** 고정 틱(60Hz) 로직. */
	void update(Engine engine, float dt);

	/** 렌더. alpha = 다음 틱까지의 보간 비율. */
	void render(Engine engine, float alpha);

	default void exit(Engine engine) {
	}

	/** 위 씬이 덮어도(예: 일시정지 메뉴) 아래 씬을 계속 그릴지. */
	default boolean transparent() {
		return false;
	}
}
