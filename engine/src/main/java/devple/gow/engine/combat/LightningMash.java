package devple.gow.engine.combat;

/**
 * 포세이돈의 분노 매시 미터 (SCR_Lightning 0x1948F0, 전역 0x3368B0) [확실: 식].
 * <ul>
 *   <li>시전 시작에 0 (0x194AB8).</li>
 *   <li>단계의 {@code Allow Mashing} (+0x20) 이 참일 때만 매 프레임: 미터 = clamp(미터 − (1/60)·{@code Mashometer Drain}, 0,
 *       {@code Mashometer Max}), 그 프레임에 <b>○</b>(패드 워드 비트 0x20, Pad_Read 0x17C384 의 비트 5) 를 새로 눌렀으면 +1
 *       (덧셈 뒤에는 자르지 않는다 — 다음 프레임에 잘림).</li>
 *   <li>연타율 = 미터 / Max (0x194BD4). Allow Mashing 이 거짓이면 미터가 0 으로 남아 연타율 0.</li>
 * </ul>
 * 원작은 60Hz 고정 프레임(1/60)이고 엔진은 dt 를 쓴다.
 */
public final class LightningMash {

	private float meter;

	public void start() {
		meter = 0;
	}

	/**
	 * 한 프레임.
	 *
	 * @param allow   Stage n Allow Mashing
	 * @param drain   Stage n Mashometer Drain (초당)
	 * @param max     Stage n Mashometer Max
	 * @param pressed ○ 를 이번 프레임에 새로 눌렀는지
	 * @return 연타율 (미터 / Max)
	 */
	public float update(boolean allow, float drain, float max, boolean pressed, float dt) {
		if (allow) {
			meter = Math.min(Math.max(meter - dt * drain, 0), max);
			if (pressed) {
				meter += 1;
			}
		}
		return rate(max);
	}

	public float meter() {
		return meter;
	}

	public float rate(float max) {
		return max > 0 ? meter / max : 0;
	}
}
