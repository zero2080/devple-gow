package devple.gow.engine.mod;

import devple.gow.engine.combat.EnemyFighter;

/**
 * 적 AI 대체 (49 문서 §13). 등록되면 {@link EnemyFighter#update} 가 살아 있고 석화·AI 정지가 아닐 때 원래 결정 트리 AI
 * ({@link devple.gow.engine.combat.AiBrain}) 대신 이것을 부른다. 기술·피격·사망·루트 모션은 원래 그대로다.
 * <ul>
 *   <li>이동: {@link EnemyFighter#steer} (원작 내비게이션 — 바닥·벽·줄, 이동 애니)</li>
 *   <li>기술: {@code self.combat().request(기술 ID)}</li>
 *   <li>원래 AI 위임: {@code self.brain().update(dt)} 후 {@code self.steer(self.brain().move, self.brain().face, dt)}</li>
 * </ul>
 */
@FunctionalInterface
public interface EnemyController {

	void update(EnemyFighter self, float dt);
}
