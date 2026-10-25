package devple.gow.engine.level;

import devple.gow.tools.anim.Animation;

import java.util.List;
import java.util.Random;

/**
 * SCR_AresSky (ATHN03D/03E {@code goaresanimgroup1} — 도시 너머 골짜기의 거대한 아레스, 영상 986–987): 하늘 GO 의 act 를 무작위로 바꿔 튼다
 * [확실: 0x218968 · 0x2189A8 · 0x25C6B0 디스어셈블].
 * <pre>
 * 생성 0x218968: SCR_Sky 와 같은 0x218848 (소유 GO 모델·파티클 부품을 층 1 하늘로, GO 위치 = 원점 → 관절 0 이 눈) + 객체 {f32 −1, s32 −1}
 * 매 프레임 0x25C6B0 → 0x2189A8:
 *   t = 재생 중 항목 시간의 최댓값 (0x100C50), d = 재생 중 act 길이의 최댓값 (0x100BF0)
 *   d ≤ t (1 회 act 끝) 또는 t &lt; 직전 t (반복 act 가 감김) 이면
 *     act 수 (ANM +0x14, 0x239EC8) ≥ 2 일 때 난수 0x13E6B0 으로 [0, act 수) 에서 직전에 고른 것과 다른 act → 0x1007C8 (처음부터)
 *   직전 t = t (바꾸기 전에 잰 값 — 1 회 act 가 끝나 바꾼 다음 프레임은 새 act 시간이 작아 한 번 더 바뀐다)
 * </pre>
 * ATHN03D/03E 세트: 0 main (0.033 초, 관절 트랙 없음) · 1 aoBAR1a03Hl01 (8.8 초 반복) · 2 Hl02 (7.7) · 3 Hl03 (5.6) · 4 Hl04 (6.1), 시작 act 0.
 * 엔진은 1/60 초 단계로 따라가고 (렌더·이펙트가 같은 시계로 {@link #advanceTo}), 난수는 고정 씨앗 (원작 전역 난수와 같은 열은 아니다).
 * 관절 트랙이 없는 act (main) 동안은 메시를 숨긴다 [추정: 원작은 관절 채널을 비워 (0x1067A8 → vt+0x54) 바인드 자세 = 눈 자리로 1–2 프레임].
 */
public final class AresSkyActs {

	/** 원작 프레임 (1/60 초). */
	static final float STEP = 1 / 60f;
	/** 한 번에 따라잡는 최대 시간 (그 이상 밀렸으면 건너뛴다). */
	static final float MAX_CATCH_UP = 10f;

	private final List<Animation> acts;
	private final Random rng;
	private int cur;
	private float time;
	private float lastTime = -1;
	private int lastAct = -1;
	private float clock = Float.NaN;
	private int switches;

	/**
	 * @param acts  ANM 세트의 act 전부 (순서 = act 번호)
	 * @param start 시작 act (인스턴스 0x54, act 수 이상이면 0 — 0x1309C0)
	 * @param t0    시작 시간 (인스턴스 0x50)
	 */
	public AresSkyActs(List<Animation> acts, int start, float t0, long seed) {
		this.acts = List.copyOf(acts);
		this.cur = start >= 0 && start < acts.size() ? start : 0;
		this.time = t0;
		this.rng = new Random(seed);
	}

	/** 시계를 now (레벨 효과 시간) 까지 1/60 초씩 진행. 시계가 거꾸로 가면 (이음에서 시계 교체) 그 자리에서 다시 잡는다. */
	public synchronized void advanceTo(float now) {
		if (Float.isNaN(clock) || now < clock) {
			clock = now;
			return;
		}
		if (now - clock > MAX_CATCH_UP) {
			clock = now - MAX_CATCH_UP;
		}
		while (clock + STEP <= now + 1e-6f) {
			step();
			clock += STEP;
		}
	}

	/** 원작 한 프레임: 스크립트 갱신 0x2189A8 뒤 애니 시간 진행 (반복 act 는 길이만큼 되감김 0x116EF0, 1 회 act 는 끝에 멈춤). */
	void step() {
		Animation a = acts.get(cur);
		float t = time, d = a.duration;
		if ((d <= t || t < lastTime) && acts.size() >= 2) {
			int n;
			do {
				n = rng.nextInt(acts.size());
			} while (n == lastAct);
			lastAct = n;
			cur = n;
			time = 0;
			switches++;
		}
		lastTime = t;
		a = acts.get(cur);
		time += STEP;
		if (a.duration > 0 && time > a.duration) {
			time = a.looping() ? time - a.duration : a.duration;
		}
	}

	public synchronized Animation act() {
		return acts.get(cur);
	}

	public synchronized int actIndex() {
		return cur;
	}

	/** 지금 act 의 경과 시간 (초). */
	public synchronized float time() {
		return time;
	}

	/** 지금까지 act 를 바꾼 횟수 (시험용). */
	public synchronized int switches() {
		return switches;
	}

	/** 메시를 그리는가 — 관절 트랙이 없는 act (main) 동안은 숨김 [추정]. */
	public synchronized boolean visible() {
		return acts.get(cur).hasSkeletalData();
	}
}
