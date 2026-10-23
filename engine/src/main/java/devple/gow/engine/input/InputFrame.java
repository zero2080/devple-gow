package devple.gow.engine.input;

/**
 * 영웅 입력 기준 (카메라 요) — 원작 영웅 입력 객체 (생성 0x1DCD20, 매 프레임 0x1DD1E8) 의 기준 행렬 +0x60·유지 프레임 +0x100·스틱 각 +0x104 (30 문서 §9.1).
 * <ul>
 *   <li>보통 (+0x100 = 0) 은 매 프레임 기준 = 지금 카메라, +0x104 = 지금 스틱 각.</li>
 *   <li>카메라 <b>컷</b> (선택 0x1EF310 의 전환 시간 ≤ 0.1초) 이면 0x1DCE80 이 +0x100 = 1.0 / (1/60) = 60 — 기준을 컷 전 카메라에 묶는다.
 *       스틱이 컷 순간 방향 (+0x104) 에서 7° 안이면 계속 그대로 (프레임으로 줄지 않는다 — 스틱을 같은 쪽으로 밀고 있는 동안은 무기한),
 *       벗어난 각 7° 마다 한 단계씩 기준을 지금 카메라 쪽으로 slerp(1/남은 수) 하고 남은 수를 1 줄인다 (0x1DD328–0x1DD398).
 *       스틱을 놓으면 (크기 0) 바로 지금 카메라로 (0x1DD488).</li>
 * </ul>
 * 컷 뒤에도 같은 쪽으로 계속 달리게 해 준다 — 없으면 화면 기준이 뒤집혀 크레토스가 돌아서 이전 볼륨으로 되돌아가 카메라가 왔다 갔다 한다.
 * 각은 라디안 (엔진 카메라 요: 정면 = (−sin, −cos)).
 */
public final class InputFrame {

	/** 0x29E150 1.0 초 / 0x29D318 (1/60) = 60 프레임. */
	public static final int HOLD_FRAMES = 60;
	/** 0x29E154 / 0x29E158: 스틱 각 7° 마다 한 단계. */
	private static final double STEP = Math.toRadians(7);
	/** 엔진 스틱 데드존 (HeroController 와 같은 0.15) — 원작 크기 +0xC &gt; 0 대응. */
	private static final float DEAD = 0.15f;

	private float ref;
	private int hold;
	private float stickAngle;
	private boolean init;
	/** 0x29F178: 이만큼의 선택 동안 컷이 유지를 걸지 않는다 (전역 정지가 풀릴 때 2, 0x1EF2F0). */
	private int suppress;

	/** 지금 기준 요 (라디안). 유지 중이 아니면 지금 카메라 요 그대로. */
	public float yaw(float camYaw) {
		return init && hold > 0 ? ref : camYaw;
	}

	/** 전역 정지 해제 (0x1BF080 → 0x1EF2F0(n)): 유지 지움 (+0x100 = 0), 다음 n 번의 선택은 컷이어도 유지 없음. */
	public void release(int n) {
		hold = 0;
		suppress = n;
	}

	/**
	 * 카메라 선택 한 번 (0x1EF310 끝): 0x29F178 이 0 이 아니면 1 줄인다. cut = 이번 선택이 컷 (전환 ≤ 0.1초) 이고 전역 정지가 아님 (0x1BF108).
	 */
	public void selected(boolean cut) {
		if (cut && suppress == 0) {
			cut();
		}
		if (suppress > 0) {
			suppress--;
		}
	}

	/** 남은 유지 단계 (0 = 지금 카메라 그대로). */
	public int hold() {
		return hold;
	}

	/** 카메라 컷 (0x1DCE80: 전환 ≤ 0.1초) — 기준을 지금 값에 묶는다. */
	public void cut() {
		if (init) {
			hold = HOLD_FRAMES;
		}
	}

	/**
	 * 틱마다 (영웅 입력 전). camYaw = 지금 카메라 요, sx/sy = 왼쪽 스틱. 반환 = 이번 틱 입력 기준 요.
	 */
	public float update(float camYaw, float sx, float sy) {
		float mag = (float) Math.hypot(sx, sy);
		float a = (float) Math.atan2(-sx, sy);
		if (!init || hold == 0 || mag < DEAD) {
			ref = camYaw;
			stickAngle = a;
			hold = 0;
			init = true;
			return ref;
		}
		double diff = Math.abs(wrap(a - stickAngle));
		while (diff > STEP && hold > 0) {
			ref += wrap(camYaw - ref) / hold; // slerp(기준, 지금, 1/남은 수) — 요 하나라 각 보간
			diff -= STEP;
			hold--;
		}
		ref = wrap(ref);
		return ref;
	}

	private static float wrap(double a) {
		return (float) Math.atan2(Math.sin(a), Math.cos(a));
	}
}
