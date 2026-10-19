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

	/** 원작 CDV 피격 볼륨 (몸 구). null 이면 공격자는 정면 구 근사를 쓴다 (34 문서). */
	default HitVolumes hitVolumes() {
		return null;
	}

	/** 이 대상의 전투 컴포넌트 (적 AI 가 대상의 방어 자세·현재 기술을 볼 때 사용, 17 문서 §3.2). 없으면 null. */
	default Combatant combatant() {
		return null;
	}

	/**
	 * 메두사의 시선 누적 (원작 SCR_TurnToRock 0x19FA78 계열, 35 문서 §2). amount = 이번 틱 동결량.
	 * @return 이번 호출로 돌이 되었으면 true. 돌이 될 수 없는 대상은 false.
	 */
	default boolean addFreeze(float amount) {
		return false;
	}

	/** 돌 상태 (석화). 돌인 대상은 맞으면 부서진다 (Stone Kill). */
	default boolean petrified() {
		return false;
	}

	/**
	 * 이동 상태 (결정 트리 조건 4/5, 0x1A14B8/0x1A14D8: owner+0x174 → 0 지상, 1 벽(0x20), 2 공중(0x4), 3 로프(0x400) — 17 문서 §3).
	 */
	default int locomotionState() {
		return 0;
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
