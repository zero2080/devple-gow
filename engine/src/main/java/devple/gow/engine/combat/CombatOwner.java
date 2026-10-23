package devple.gow.engine.combat;

import org.joml.Vector3f;

/** 전투 컴포넌트를 가진 엔티티가 제공하는 상태 (원작 owner+0x174 상태 비트, +0x178 HP 등). */
public interface CombatOwner {

	float hp();

	boolean onGround();

	/**
	 * 분기 허용 상태 마스크 (Combat_StateMask 0x207DC0, 10 문서 §2.4): 지상 0x2 / 공중 0x4 / 벽 0x20 / 줄 0x80 / 수영 0x10000.
	 * 기본은 지상/공중만. 벽·줄에 붙는 적은 {@link EnemyNav#combatMask()}.
	 */
	default int stateMask() {
		return onGround() ? 0x2 : 0x4;
	}

	int bladeLevel();

	boolean godMode();

	boolean artemisEquipped();

	/** 아르테미스의 검 보유 (분기 magic 7 조건, 원작 0x3044C2&amp;0x40). 장착 여부와 다르다. */
	default boolean artemisOwned() {
		return artemisEquipped();
	}

	/** 선택 마법 아이템 ID (3–6, 없으면 0). */
	int selectedMagic();

	/** 원작 AI1 템플릿 번호 (영웅 -1). */
	int classId();

	Vector3f position();

	/** 이동 방향 yaw (정면 = 모델 -Z 를 yaw 만큼 회전). */
	float yaw();

	/** HasMagic (0x1D93C0): 선택 마법(3–6)을 쓸 MP 가 있는지. */
	default boolean hasMagic(int magicId) {
		return true;
	}

	/** UseMagic: 마법 분기로 기술을 시작할 때 MP 소모. */
	default void useMagic(int magicId) {
	}

	/**
	 * 피해 적용. 반환값 = 잡기 원 임계 HP(AI1 "Circle HP")를 이번 피해로 통과했는지 (이벤트 21).
	 */
	default boolean applyDamage(float damage) {
		return false;
	}

	/** 이 피해로 죽는지 (피격 이벤트 EV_DEATH 판정, 피해 적용 전). 피해를 클래스가 직접 다루는 적(BMI)은 false. */
	default boolean killedBy(float damage) {
		return hp() > 0 && hp() <= damage;
	}

	/**
	 * 개체 배율 (owner +0x168: 적 AI1 ScaleCenter/Range, 영웅 1). 원작 관절 월드 행렬에 들어 있어 CDV 구 중심·반지름도 함께 줄어든다
	 * (0x13A998: 관절 행렬 × 로컬 중심, 반지름 × 관절 배율) — 선창 히드라 0.3 (37 문서 §4.3).
	 */
	default float modelScale() {
		return 1f;
	}
}
