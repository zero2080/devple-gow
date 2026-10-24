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

	/**
	 * 클립을 시간 t 에 세워 둔다 (원작 이동 매개변수 ≥ 0 → 애니 시간 = 매개변수 × 길이, 재생 속도 0 — 0x1F77B4 → 0x100628 / 0x100510) [확실].
	 * 예: TAndF 들기 (이동 0x60 ANITandF2) 의 매개변수 = min(문 위치, 1) (0x1ECA44). 기본 구현은 그냥 재생 (헤드리스 테스트).
	 */
	default void scrub(String anim, float t) {
		play(anim, false);
	}

	/** 지금 자세에서 관절의 모델 공간 y (몸 뿌리 기준 — 원작 모델 인스턴스 +0x50 관절 행렬). 모르면 NaN (헤드리스 테스트). */
	default float jointModelY(String joint) {
		return Float.NaN;
	}
}
