package devple.gow.engine.script;

import java.util.function.IntConsumer;

/**
 * GameMgr 스크립트 타이머 표 (원작 0x3368F8, 칸 4 개) [확실: 디스어셈블]: 남은 초 float[4] (+0x00), 길이 float[4] (+0x10), 플래그 u8[4] (+0x20) —
 * 1 = 사용 중, 2 = 멈춤, 4 = HUD 모래시계에 표시. 번호는 칸 + 1 (0 = 없음 — 스크립트는 "if (타이머 > 0) DestroyTimer" 로 쓴다).
 * <ul>
 *   <li>Internal 0x14 CreateTimer(초) 0x1B0B68: 플래그 0 인 첫 칸, 플래그 1 · 길이 = 남은 = 초, 번호 반환</li>
 *   <li>0x15 0x1B0BE8: 사용 중이면 플래그 |= 2 (멈춤) · 0x16 0x1B0C28: 플래그 &amp;= ~2 (다시) · 0x17 0x1B0C68: 플래그 0 (지움)</li>
 *   <li>0x18 0x1B0CA8: 남은 초 · 0x19 0x1B0CE0: 모든 칸 표시 비트를 지우고 이 칸만 4 (0 = 아무것도 안 보임)</li>
 *   <li>매 프레임 (스크립트 세계 갱신 0x1A7A78 의 0x1A81F8 – 0x1A8354, 게임 상태 0xB 일 때): 멈춤이 아니면 남은 −= 1/60, 0 이하면 칸을 비우고
 *       전역 이벤트 1050 (0x41A, 인자 0 = 번호, 보낸 쪽 = 영웅 GO 0x29D904) — 표시 칸은 HUD Timer_Value (경과 %) · Timer_Seconds (남은 초)</li>
 *   <li>0x1A72C8: 모두 비움 (스크립트 세계 초기화 0x1A6F18, 플레이어 진행도 초기화 0x1D6818)</li>
 * </ul>
 * 데이터: ATHN06C 오라클 구출 70 초 (TIMERoracl, 0x19 로 모래시계), Challenge of the Gods 아레나 6 곳 (0x19), 그 밖 궁수 스폰·히드라 카메라·VO 안내 타이머 67 곳.
 */
public final class ScriptTimers {

	/** 칸 수 (0x1B0B98 slti 4). */
	public static final int SLOTS = 4;
	/** 이벤트 번호 0x41A. */
	public static final int EVENT = 1050;
	public static final int USED = 1, PAUSED = 2, SHOWN = 4;

	private final float[] remaining = new float[SLOTS], duration = new float[SLOTS];
	private final int[] flags = new int[SLOTS];

	/** 0x14: 빈 칸에 새 타이머 (번호 1..4). 칸이 다 차면 원작은 칸 4 (표 밖) 에 써 버린다 — 엔진은 0 [근사]. */
	public int create(float seconds) {
		for (int i = 0; i < SLOTS; i++) {
			if (flags[i] == 0) {
				flags[i] = USED;
				duration[i] = seconds;
				remaining[i] = seconds;
				return i + 1;
			}
		}
		return 0;
	}

	private static boolean valid(int id) {
		return id >= 1 && id <= SLOTS;
	}

	/** 0x15: 멈춤. */
	public void pause(int id) {
		if (valid(id) && flags[id - 1] != 0) {
			flags[id - 1] |= PAUSED;
		}
	}

	/** 0x16: 다시 흐름. */
	public void resume(int id) {
		if (valid(id) && flags[id - 1] != 0) {
			flags[id - 1] &= ~PAUSED;
		}
	}

	/** 0x17: 지움 (이벤트 없음). */
	public void destroy(int id) {
		if (valid(id)) {
			flags[id - 1] = 0;
		}
	}

	/** 0x18: 남은 초 (칸을 보지 않고 읽는다). */
	public float remaining(int id) {
		return valid(id) ? remaining[id - 1] : 0f;
	}

	/** 0x19: HUD 모래시계에 이 타이머 (0 = 숨김) — 다른 칸의 표시 비트는 모두 지운다. */
	public void show(int id) {
		for (int i = 0; i < SLOTS; i++) {
			flags[i] &= ~SHOWN;
		}
		if (valid(id) && flags[id - 1] != 0) {
			flags[id - 1] |= SHOWN;
		}
	}

	/**
	 * 표 그대로 {남은[4], 길이[4], 플래그[4]} — 원작 GameMgr 블록 0x3368F8 (0x1C4 바이트) 을 세이브·체크포인트 스트림 7 로 통째로 쓴다 (0x1B15B8) [확실].
	 */
	public float[] save() {
		float[] out = new float[SLOTS * 3];
		for (int i = 0; i < SLOTS; i++) {
			out[i] = remaining[i];
			out[SLOTS + i] = duration[i];
			out[2 * SLOTS + i] = flags[i];
		}
		return out;
	}

	/** {@link #save()} 를 되돌린다 (0x1B15E0: 체크포인트 재시작은 +0x00..+0x2F 타이머·동영상 칸만, 본 동영상 목록 +0x30.. 은 지금 것 유지). null = 비움. */
	public void restore(float[] v) {
		reset();
		if (v == null || v.length < SLOTS * 3) {
			return;
		}
		for (int i = 0; i < SLOTS; i++) {
			remaining[i] = v[i];
			duration[i] = v[SLOTS + i];
			flags[i] = (int) v[2 * SLOTS + i];
		}
	}

	/** 0x1A72C8. */
	public void reset() {
		for (int i = 0; i < SLOTS; i++) {
			flags[i] = 0;
			remaining[i] = 0;
			duration[i] = 0;
		}
	}

	public boolean active(int id) {
		return valid(id) && flags[id - 1] != 0;
	}

	public int flags(int id) {
		return valid(id) ? flags[id - 1] : 0;
	}

	/**
	 * 한 프레임 (0x1A81F8 – 0x1A8350): 멈추지 않은 칸은 dt 만큼 줄고, 0 이하가 되면 비우고 expired(번호) — 원작은 그 자리에서 PostEvent (0x1A8C78) 로
	 * 큐에 넣는다 (다음 프레임 전달). 칸 순서 0..3.
	 */
	public void tick(float dt, IntConsumer expired) {
		for (int i = 0; i < SLOTS; i++) {
			if (flags[i] == 0) {
				continue;
			}
			if ((flags[i] & PAUSED) == 0) {
				remaining[i] -= dt;
			}
			if (remaining[i] <= 0) {
				duration[i] = 0;
				remaining[i] = 0;
				flags[i] = 0;
				expired.accept(i + 1);
			}
		}
	}

	/**
	 * HUD 모래시계 값 (0x1A827C – 0x1A82D8): 표시 칸 (첫 번째) 의 {경과 % = 100 − (int)(남은 × 100 / 길이 + 0.5), 남은 초 = (int)(남은 + 0.5)},
	 * 표시 칸이 없으면 null. FLP_HUDK HourGlassMeter 가 GotoFrame2(2 + 경과 %) (프레임 92 부터 붉은 깜박임 clip#3), HourGlassTime 이 초.
	 */
	public int[] hud() {
		for (int i = 0; i < SLOTS; i++) {
			if (flags[i] != 0 && (flags[i] & SHOWN) != 0 && duration[i] > 0) {
				int percent = 100 - (int) (remaining[i] * 100f / duration[i] + 0.5f);
				int secs = (int) (remaining[i] + 0.5f);
				return new int[]{percent, secs};
			}
		}
		return null;
	}
}
