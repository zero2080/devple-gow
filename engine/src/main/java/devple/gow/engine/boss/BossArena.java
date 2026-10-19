package devple.gow.engine.boss;

import devple.gow.engine.anim.HeroController;
import devple.gow.engine.combat.Combatant;
import devple.gow.engine.combat.Hittable;
import devple.gow.engine.script.ScriptWorld;

/** 보스 컨트롤러가 보는 게임 쪽 (GameScene / 헤드리스 테스트가 구현). */
public interface BossArena {

	HeroController heroCtl();

	Combatant heroCombat();

	Hittable hero();

	/** 영웅 HP 변화 (양수 = 피해, 음수 = 회복). SELF_HP 이벤트 등. */
	void damageHero(float amount);

	/** 레벨 스크립트 (없으면 null). */
	ScriptWorld world();

	CombatScriptBridge bridge();

	default void sound(String name) {
	}
}
