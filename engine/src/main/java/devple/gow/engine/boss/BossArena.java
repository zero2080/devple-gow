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

	/**
	 * 스크립트 밖 카메라 요청 (Internal 0x8 SwitchCamera 와 같은 한 프레임 후보). second = 두 번째 카메라 대상 (없으면 null).
	 * 원작 아레스 클래스 갱신 0x1C3370 이 매 프레임 "AresCamera1" 을 요청한다 (30 문서 §6).
	 */
	default void requestCamera(String name, org.joml.Vector3f second) {
	}
}
