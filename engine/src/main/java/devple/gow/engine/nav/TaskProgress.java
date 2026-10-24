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

	/**
	 * 대포 크랭크 감기 (Push Type 2 = 0x212998 모드 3, ATHN02C AthnGun) [확실: 디스어셈블]: 붙어 있는 동안 속도대로 감다가 끝 (길이) 에 닿으면
	 * 시간은 끝에 둔 채 속도 0 — 진행 보고는 길이 − 0.01 (0x212AD0, 99 %) 이고 완료하지 않는다. 영웅이 발차기 (슬롯 0x53 → 0x54) 로 비트 2 를 세우고
	 * 놓아야 {@link #fire()}.
	 *
	 * @return 보낼 1023 (끝이면 99 %)
	 */
	public Event cannon(float spd, float dt) {
		if (done) {
			return null;
		}
		speed = spd;
		time = Math.max(0, Math.min(length, time + speed * dt));
		if (time >= length - 1e-4f) {
			time = length;
			speed = 0;
			return emit(length - 0.01f, length, 0, false);
		}
		return emit(time, length, speed, false);
	}

	/** 대포가 끝까지 감겨 있는가 (영웅 발차기 대기, 0x212AD0 → 영웅 +0x2D8 |= 0x20000). */
	public boolean wound() {
		return !done && length > 0 && time >= length - 1e-4f;
	}

	/** 대포 발사 마무리 (+0x29A 비트 2 를 본 놓인 뒤 갱신, 0x212B80): 끝 시간 그대로 100 %, 속도 0, 완료. */
	public Event fire() {
		time = length;
		speed = 0;
		done = true;
		return emit(length, length, 0, true);
	}

	/**
	 * 스크립트 1015 리셋 되감기 한 프레임 (0x2100E0 → 크랭크 0x212C34 / 레버 0x2127B0): spd = 이번 속도 (null = 템플릿 +0x108 비트 2 —
	 * 시간 0 으로 바로, 0x100578). 0 에 닿으면 속도 0 (다시 잡힘은 호출자가). 완료 상태를 푼다.
	 */
	public Event rewind(Float spd, float dt) {
		done = false;
		if (spd == null || time <= 0) {
			time = 0;
			speed = 0;
			return emit(0, length, 0, false);
		}
		speed = spd;
		time = Math.max(0, time + speed * dt);
		if (time <= 0) {
			speed = 0;
		}
		return emit(time, length, speed, false);
	}

	/** 링 크랭크 TWK (Push 템플릿 +0x2C Acc, +0x30 Dec, +0x34 접근 가속 #b6ba95a3, +0x38 최대, +0x3C 최대(놓음), +0x40 허용, +0x108 비트 4 양방향). */
	public record RingParams(float acc, float dec, float approach, float max, float maxBack, float tol, boolean twoWay) {
	}

	/**
	 * 링 크랭크 (Push Type 4, 원작 0x212998 모드 2 = 0x212F58) [확실: 디스어셈블]. 애니가 한 바퀴 — 시간은 길이로 감긴다 (완료 없음).
	 * <ul>
	 *   <li>붙어 있음: 입력 &gt; 0 → 속도 += Acc·dt (최대), 입력 &lt; 0 이고 양방향 → −Acc·dt (−최대), 입력 없음 → Dec·dt 로 0 쪽.</li>
	 *   <li>놓음: 비율 f 가 멈춤 자리 0·¼·½·¾ (끝 1 = 0) ± 허용 안이면 접근 가속으로 그 자리로 (최대 +0x3C), 닿으면 멈춤.
	 *       밖이면 그 자리에서 멈춘다.</li>
	 * </ul>
	 * 원작은 |목표 − f| &lt; 1e-4 일 때 붙인다 — 엔진은 프레임 사이에 목표를 지나면 붙인다 [근사: 같은 결과, 진동 없음].
	 *
	 * @param input 크랭크 축 방향 입력 부호 (+1 / −1 / 0)
	 */
	public Event ring(boolean attached, int input, RingParams p, float dt) {
		if (length <= 0) {
			return null;
		}
		float f = time / length;
		if (attached) {
			if (input > 0) {
				speed = Math.min(speed + p.acc() * dt, p.max());
			} else if (input < 0 && p.twoWay()) {
				speed = Math.max(speed - p.acc() * dt, -p.max());
			} else {
				speed = speed > 0 ? Math.max(speed - p.dec() * dt, 0) : Math.min(speed + p.dec() * dt, 0);
			}
		} else {
			float target = detent(f, p.tol());
			if (target < 0) {
				speed = 0;
				return emit(time, length, 0, false);
			}
			float goal = target == 0 && f > 0.5f ? 1 : target;
			if (Math.abs(goal - f) < 1e-4f) {
				time = (goal >= 1 ? 0 : goal) * length;
				speed = 0;
				return emit(time, length, 0, false);
			}
			speed = f < goal ? Math.min(speed + p.approach() * dt, p.maxBack()) : Math.max(speed - p.approach() * dt, -p.maxBack());
			float next = f + speed * dt / length;
			if ((goal - f) * (goal - next) <= 0) {
				time = (goal >= 1 ? 0 : goal) * length;
				speed = 0;
				return emit(time, length, 0, false);
			}
		}
		time = ((time + speed * dt) % length + length) % length;
		return emit(time, length, speed, false);
	}

	/**
	 * 기중기형 크랭크 (Push Type 3 → 0x212998 모드 1, PAND04C 엘리베이터 "Pand04CElevs"·PAND04G "Pand4GCrane") [확실: 디스어셈블]:
	 * 붙어 있음 — 입력 &gt; 0 → +Acc·dt (최대 +0x38), &lt; 0 → −Acc·dt (−최대), 없음 → Dec·dt 로 0 쪽 (0x212D18 / 0x212DAC).
	 * 놓음 — Dec·dt 로 0 쪽 (되감지 않음). 시간은 [0, 길이] 에서 멈춘다 (0x212EA0) — 완료가 없고 앞뒤로 움직인다.
	 */
	public Event crane(boolean attached, int input, RingParams p, float dt) {
		if (length <= 0) {
			return null;
		}
		if (attached && input > 0) {
			speed = Math.min(speed + p.acc() * dt, p.max());
		} else if (attached && input < 0) {
			speed = Math.max(speed - p.acc() * dt, -p.max());
		} else {
			speed = speed > 0 ? Math.max(speed - p.dec() * dt, 0) : Math.min(speed + p.dec() * dt, 0);
		}
		time += speed * dt;
		if (time >= length) {
			time = length;
			speed = Math.min(speed, 0);
		} else if (time <= 0) {
			time = 0;
			speed = Math.max(speed, 0);
		}
		return emit(time, length, speed, false);
	}

	/** 멈춤 자리 (0x21302C): f &lt; tol 또는 f &gt; 1 − tol → 0, |f − ¼|·|f − ½|·|f − ¾| &lt; tol 중 하나 → 그 값, 아니면 −1. */
	public static float detent(float f, float tol) {
		if (f < tol || f > 1 - tol) {
			return 0;
		}
		for (float d : new float[]{0.25f, 0.5f, 0.75f}) {
			if (f > d - tol && f < d + tol) {
				return d;
			}
		}
		return -1;
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
