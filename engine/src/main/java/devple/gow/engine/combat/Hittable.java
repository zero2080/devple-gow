package devple.gow.engine.combat;

import devple.gow.tools.combat.CombatFile.Attack;
import org.joml.Vector3f;

/** 공격을 받을 수 있는 대상. */
public interface Hittable {

	enum Result { IGNORED, BLOCKED, HIT }

	Vector3f position();

	float hp();

	/** 원작 AI1 번호 (분기 matchA 비교용). */
	int classId();

	/** 현재 공격 자세 태그 (원작 combatant+0x1B0, 분기 matchB 비교용). */
	default int stanceTag() {
		return -1;
	}

	/**
	 * 잡기 가능 상태 (원작 combatant+0x1B8: HP 가 AI1 Circle Health Threshold 아래 → 잡기 원 표시).
	 * 분기 flags 0x10/0x40 이 이 값을 본다 (GRAB → MAIMIN01 vs GRABREJECT).
	 */
	default boolean grabbable() {
		return false;
	}

	/** 이 대상의 전투 컴포넌트 (적 AI 가 대상의 방어 자세·현재 기술을 볼 때 사용, 17 문서 §3.2). 없으면 null. */
	default Combatant combatant() {
		return null;
	}

	/** 무적(이벤트 9) 또는 사망. */
	boolean invulnerable();

	/**
	 * 피격 처리 (10 문서 §15.4 OnContact 의 피격자 쪽).
	 *
	 * @param damage 공격자 배율까지 적용된 피해
	 * @param knock  월드 공간 충격 벡터
	 */
	Result receive(Attack attack, Combatant attacker, float damage, Vector3f knock);
}
