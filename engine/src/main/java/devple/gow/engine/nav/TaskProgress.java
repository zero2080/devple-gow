package devple.gow.engine.nav;

/**
 * R1 오브젝트의 과제 진행률 = <b>오브젝트 애니 재생 위치</b> (38 문서 §3.1·§10.1).
 * <pre>
 * 원작 진행률 송신 함수 3 개가 같은 코드다 [확실: 디스어셈블]
 *   0x215928 IO_CSM   (마지막 % +0x1C4, 마지막 속도 +0x1C8, 과제 id +0x1CC)
 *   0x2118E8 PushPull (+0x27C / +0x280 / +0x284)   ← 레버·크랭크도 1023 을 보낸다 (PAND00C goelevatorlever id 111)
 *   0x213F90 IO_TandF (+0x1C8 / +0x1CC / +0x1D0)
 *   f12 = 애니 시간 t, f13 = 애니 길이 len, f14 = 재생 속도
 *   pct = clamp(round(100·t/len), 0, 100), pct == 100 인데 t &lt; len 이면 99
 *   pct 또는 (int)(속도·100) 이 바뀌면 PostEvent(1023, pct, id, 속도·100, 오브젝트)
 * 재생 속도 (CSM 갱신 0x215DF0 / PushPull 0x2127B0):
 *   영웅이 붙어 있으면(+0x190 ≥ 0) 1.0, 떨어졌고 t &gt; 0 이면 −4.0 (되감기), t 가 0 에 닿으면 0
 *   t ≥ len 이고 속도 &gt; 0 → 완료: 0x215928(1.01, 1, 1) = 100 % 를 한 번 더 보냄
 * </pre>
 * 길이 0 인 애니(DEST03 뿔피리 "main")는 붙자마자 완료된다.
 */
public final class TaskProgress {

	/** 붙어 있을 때 / 떨어졌을 때 재생 속도 (0x216088 1.0, 0x2160C0 −4.0). */
	public static final float ENGAGED_SPEED = 1f, REWIND_SPEED = -4f;

	/** 1023 인자: 0 = %, 1 = 과제 id, 2 = 속도 × 100. */
	public record Event(int percent, int taskId, int speed100) {
	}

	public final int taskId;
	/** 오브젝트 애니 길이 (초). */
	public final float length;
	public float time, speed;
	public boolean done;
	private int lastPercent = Integer.MIN_VALUE, lastSpeed = Integer.MIN_VALUE;

	/** 붙어 있을 때 / 떨어졌을 때 애니 재생 속도 (CSM·레버 1 / −4, IO_TandF = TAndF Acc / −Dec). */
	private final float engagedSpeed, rewindSpeed;

	public TaskProgress(int taskId, float length) {
		this(taskId, length, ENGAGED_SPEED, REWIND_SPEED);
	}

	public TaskProgress(int taskId, float length, float engagedSpeed, float rewindSpeed) {
		this.taskId = taskId;
		this.length = Math.max(0, length);
		this.engagedSpeed = engagedSpeed;
		this.rewindSpeed = rewindSpeed;
	}

	/** 0..1. */
	public float fraction() {
		return done ? 1 : length <= 0 ? 0 : Math.min(1, time / length);
	}

	/**
	 * 완료 때 보내는 값. false (CSM 0x215DF0) = 0x215928(1.01, 1, 1) → 100 %, 속도 100.
	 * true (PushPull 레버 0x2127B0 / 크랭크 0x212998) = 끝에서 속도 0 으로 멈추고 0x2118E8(t, len, 0) → 100 %, 속도 0.
	 */
	public boolean stopAtEnd;

	/**
	 * 한 프레임. engaged = 영웅이 붙어 있음 (오브젝트 +0x190 ≥ 0).
	 *
	 * @return 보낼 1023 이벤트 (바뀐 게 없으면 null)
	 */
	public Event update(boolean engaged, float dt) {
		if (done) {
			return null;
		}
		return advance(engaged ? engagedSpeed : time > 0 ? rewindSpeed : 0, dt);
	}

	/**
	 * 정해진 속도로 한 프레임 (크랭크처럼 속도가 가속/감속되는 오브젝트). 속도 &gt; 0 으로 끝에 닿으면 완료,
	 * 속도 &lt; 0 으로 0 에 닿으면 멈춘다.
	 */
	public Event advance(float spd, float dt) {
		if (done) {
			return null;
		}
		speed = spd;
		time = Math.max(0, Math.min(length, time + speed * dt));
		if (speed > 0 && time >= length - 1e-4f) { // 프레임 시간 누적 오차 (영웅 애니 끝과 같은 틱)
			time = length;
			done = true;
			if (stopAtEnd) {
				speed = 0;
				return emit(length, length, 0, true);
			}
			return emit(1.01f, 1f, 1f, true);
		}
		if (speed < 0 && time <= 0) {
			speed = 0;
		}
		return emit(time, length, speed, false);
	}

	/** IO_TandF 들어 올리는 문 TWK (TAndF 템플릿 +0x18 Acc, +0x1C Dec, +0x20 #fe467fe6, +0x24 #b503fbe4, +0x28 TF Time). */
	public record LiftParams(float acc, float dec, float maxUp, float maxDown, float tfTime) {
	}

	/**
	 * IO_TandF 한 프레임 (원작 0x214400, 위치 +0x1E4 = {@link #time} 0..1, 속도 +0x1C4 = {@link #speed}) [확실: 디스어셈블].
	 * <pre>
	 * 붙어 있음:  위치 += 속도 · dt / 4;  위치 ≥ 1 → 완료
	 *             이번 프레임 누름 (마지막 누름 뒤 시간 0x29DBF0 ≤ 0) → 속도 = min(속도 + Acc, 최대)   — 누를 때마다 한 번 (가속 · dt 아님)
	 *             마지막 누름 뒤 TF Time 이 지남 → 속도 = max(속도 − Dec · dt, −최대 하강)
	 *             속도 &lt; 0 이고 위치 ≤ 0 → 0 에서 멈춤
	 * 떨어짐:     속도 = min(속도, 0); 위치 += 속도 · dt / 4; 속도 = max(속도 − Dec · dt, −최대 하강); 0 에서 멈춤
	 * 1023 = 0x213F90(위치, 1, 속도)
	 * </pre>
	 *
	 * @param press      이번 프레임 R1 을 새로 눌렀음 (붙어 있을 때만 의미)
	 * @param sincePress 마지막 누름 뒤 시간 (초)
	 */
	public Event lift(boolean attached, boolean press, float sincePress, LiftParams p, float dt) {
		if (done) {
			return null;
		}
		if (attached) {
			time += speed * dt * 0.25f;
			if (time >= 1) {
				time = 1;
				done = true;
				return emit(1, 1, speed, true);
			}
			if (press) {
				speed = Math.min(speed + p.acc(), p.maxUp());
			} else if (sincePress > p.tfTime()) {
				speed = Math.max(speed - p.dec() * dt, -p.maxDown());
			}
			if (speed < 0 && time <= 0) {
				time = 0;
				speed = 0;
			}
		} else if (time > 0) {
			speed = Math.min(speed, 0);
			time += speed * dt * 0.25f;
			speed = Math.max(speed - p.dec() * dt, -p.maxDown());
			if (time <= 0) {
				time = 0;
				speed = 0;
			}
		} else {
			speed = 0;
		}
		return emit(time, 1, speed, false);
	}

	/** 원작 0x215928. */
	private Event emit(float t, float len, float spd, boolean force) {
		int pct = len <= 0 ? 100 : Math.round(100 * t / len);
		pct = Math.max(0, Math.min(100, pct));
		if (pct == 100 && t < len) {
			pct = 99;
		}
		int s = (int) (spd * 100);
		if (!force && pct == lastPercent && s == lastSpeed) {
			return null;
		}
		lastPercent = pct;
		lastSpeed = s;
		return new Event(pct, taskId, s);
	}

	/** 처음 상태로 (스크립트 1015 리셋 등). */
	public void reset() {
		time = 0;
		speed = 0;
		done = false;
		lastPercent = Integer.MIN_VALUE;
		lastSpeed = Integer.MIN_VALUE;
	}
}
