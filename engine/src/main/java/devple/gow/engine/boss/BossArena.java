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
	 * 스크립트 밖 카메라 요청 (Internal 0x8 SwitchCamera 와 같은 한 프레임 후보). targets = 추가 카메라 대상
	 * (AresCamera1: 아레스 몸, 영웅 관절 vertebrae4, 아레스 관절 BAR01JOCVEC1 — CameraRig.request).
	 * 원작 아레스 클래스 갱신 0x1C3370 이 매 프레임 "AresCamera1" 을 요청한다 (30 문서 §6).
	 */
	default void requestCamera(String name, org.joml.Vector3f... targets) {
	}

	/** 원작 이펙트 GO 재생기 (레벨 WAD 등록됨, 없으면 null — 헤드리스 테스트는 EffectSystem.headless()). */
	default devple.gow.engine.fx.EffectSystem effects() {
		return null;
	}

	/** 레벨 충돌 (투사체·통나무, 없으면 null). */
	default devple.gow.engine.level.LevelCollision collision() {
		return null;
	}

	/** 추가 카메라 대상 슬롯 (CameraRig.setTargetSlot, p == null 이면 지움). BMI 머리 관절 0.35. */
	default void cameraTarget(org.joml.Vector3f p, float weight) {
	}
}
