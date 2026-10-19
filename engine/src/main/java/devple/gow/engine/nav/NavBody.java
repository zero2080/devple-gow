package devple.gow.engine.nav;

import org.joml.Vector3f;

/**
 * 내비게이터가 움직이는 몸 (영웅 컨트롤러). 헤드리스 테스트는 간단한 구현을 쓴다.
 * 좌표 규칙은 {@code HeroController} 와 같다: yaw 의 정면 = (-sin yaw, 0, -cos yaw).
 */
public interface NavBody {

	Vector3f position();

	float yaw();

	void setYaw(float yaw);

	float vy();

	void setVy(float vy);

	boolean grounded();

	void setGrounded(boolean g);

	/** 공중 수평 발사 속도 (줄에서 뛰어내림·짚라인 끝). 착지하면 몸이 0 으로 만든다. */
	Vector3f launch();

	/** 클립 재생 (이름 = Navigation 표의 act 이름). */
	void play(String anim, boolean restart);

	boolean animFinished();

	/** 클립 길이 (초). 없으면 음수 — 호출자가 대신 값을 쓴다 (헤드리스 테스트). */
	default float clipLength(String anim) {
		return -1f;
	}

	/** 지상 이동 모양 바꾸기 (균형 걷기 등): walk/idle null = 기본, speedScale 1 = 기본 속도. */
	void locomotion(String walk, String idle, float speedScale);
}
