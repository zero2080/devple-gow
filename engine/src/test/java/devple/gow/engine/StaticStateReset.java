package devple.gow.engine;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * 모든 테스트 앞에서 엔진의 JVM 전역 상태를 처음 값으로 (48 문서 §5): 적 AI 난수 씨앗 순번 ({@code EnemyFighter.spawnCount}) 과 대상 슬롯
 * ({@code AiTargets}). 이것이 없으면 같은 JVM 에서 앞서 돈 테스트가 만든 적 수에 따라 결과가 바뀐다 (전체 실행과 단독 실행·섞은 순서가 달랐다).
 * {@code junit-platform.properties} 의 확장 자동 감지로 모든 테스트 클래스에 붙는다.
 */
public final class StaticStateReset implements BeforeEachCallback {

	@Override
	public void beforeEach(ExtensionContext context) {
		devple.gow.engine.combat.EnemyFighter.resetSpawnCount();
		devple.gow.engine.combat.AiTargets.clear();
		devple.gow.engine.mod.Plugins.reset(); // 플러그인 등록표 (49 문서 §13 — 테스트 자원의 예제 플러그인이 다른 테스트에 새지 않게)
	}
}
