package devple.gow.engine.mod;

/**
 * 코드 확장 (49 문서 §13). {@link java.util.ServiceLoader} 로 찾는다 — 클래스패스의
 * {@code META-INF/services/devple.gow.engine.mod.GowPlugin} 과 각 계층의 {@code plugins/*.jar}.
 * 훅은 작게 둔다: 시작 때 한 번 ({@link #onStart}) 과 매 프레임 ({@link #onFrame}).
 */
public interface GowPlugin {

	default String name() {
		return getClass().getSimpleName();
	}

	/** 엔진 시작 때 한 번. 적 제어 교체 등록은 여기서 ({@link PluginContext#replaceEnemy}). */
	void onStart(PluginContext ctx);

	/** 게임 장면 매 프레임 (dt 초). */
	default void onFrame(float dt) {
	}
}
