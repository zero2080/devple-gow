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
	 * 한 프레임. engaged = 영웅이 붙어 있음 (CSM 연타 중 / 레버를 잡고 있음).
	 *
	 * @return 보낼 1023 이벤트 (바뀐 게 없으면 null)
	 */
	public Event update(boolean engaged, float dt) {
		if (done) {
			return null;
		}
		speed = engaged ? engagedSpeed : time > 0 ? rewindSpeed : 0;
		time = Math.max(0, Math.min(length, time + speed * dt));
		if (engaged && time >= length) {
			done = true;
			return emit(1.01f, 1f, 1f, true);
		}
		if (!engaged && time <= 0) {
			speed = 0;
		}
		return emit(time, length, speed, false);
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
