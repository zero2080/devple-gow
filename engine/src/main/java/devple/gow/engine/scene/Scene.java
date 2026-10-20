package devple.gow.engine.scene;

import devple.gow.engine.core.Engine;

/** 씬 = 게임의 한 상태 (타이틀, 메뉴, 레벨, 컷신 ...). */
public interface Scene {

	default void enter(Engine engine) {
	}

	/**
	 * 페이드 아웃 동안 (교체 전, 틱마다) 다음 씬이 미리 할 일 — GL 올리기·생성 등 (48 문서 §9). 이 동안 이전 씬은 그려지기만 하고 갱신되지 않는다.
	 * enter 결과가 미리 하지 않았을 때와 같아야 한다.
	 */
	default void preload(Engine engine) {
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
