package devple.gow.engine.combat;

import devple.gow.tools.combat.CombatFile.Attack;
import org.joml.Vector3f;

/** 공격을 받을 수 있는 대상. */
public interface Hittable {

	Vector3f position();

	float hp();

	/** 원작 AI1 번호 (분기 matchA 비교용). */
	int classId();

	/** 무적(이벤트 9) 또는 사망. */
	boolean invulnerable();

	/** 방어 자세 (현재 기술 guard == 1). */
	boolean guarding();

	/**
	 * 피격 처리 (10 문서 §15.4 OnContact 의 피격자 쪽).
	 *
	 * @param unblockable 공격자 기술 flags 0x2
	 * @param forceReact  공격자 기술 flags 0x6
	 * @param knock       월드 공간 충격 벡터
	 * @return 실제로 맞았으면 true (막히면 false)
	 */
	boolean receive(float damage, Attack attack, boolean unblockable, boolean forceReact, Vector3f knock);
}
