package devple.gow.engine.combat;

import org.joml.Vector3f;

/** 전투 컴포넌트를 가진 엔티티가 제공하는 상태 (원작 owner+0x174 상태 비트, +0x178 HP 등). */
public interface CombatOwner {

	float hp();

	boolean onGround();

	int bladeLevel();

	boolean godMode();

	boolean artemisEquipped();

	/** 선택 마법 아이템 ID (3–6, 없으면 0). */
	int selectedMagic();

	/** 원작 AI1 템플릿 번호 (영웅 -1). */
	int classId();

	Vector3f position();

	/** 이동 방향 yaw (정면 = 모델 -Z 를 yaw 만큼 회전). */
	float yaw();
}
