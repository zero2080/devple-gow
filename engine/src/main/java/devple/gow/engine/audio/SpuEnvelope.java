package devple.gow.engine.audio;

/**
 * SPU2 ADSR 엔벨로프 (톤 ADSR1/ADSR2, 14 문서 §2.4 → 29 문서 §7). PS1 SPU 와 같은 규칙을 48kHz 샘플 단위로 흉내 낸다.
 * <pre>
 * ADSR1: 15 공격 지수 | 14-10 공격 shift | 9-8 공격 step(+7..+4) | 7-4 감쇠 shift (지수 감소, step −8) | 3-0 서스테인 레벨 SL (감쇠는 (level&gt;&gt;11) ≤ SL 까지)
 * ADSR2: 15 서스테인 지수 | 14 서스테인 감소 | 12-8 서스테인 shift | 7-6 서스테인 step (+7..+4 / −8..−5) | 5 릴리스 지수 | 4-0 릴리스 shift
 * 한 단계: cycles = 1 &lt;&lt; max(0, shift−11), step = stepVal &lt;&lt; max(0, 11−shift)
 *         지수 증가 &amp;&amp; level &gt; 0x6000 → cycles ×4,  지수 감소 → step × level / 0x8000
 * </pre>
 * 레벨 0..0x7FFF 를 선형 볼륨으로 쓴다.
 */
public final class SpuEnvelope {

	public enum Phase { ATTACK, DECAY, SUSTAIN, RELEASE, OFF }

	public static final int RATE = 48000;

	private final int adsr1;
	private final int adsr2;
	private Phase phase = Phase.ATTACK;
	private int level;
	private int wait;

	public SpuEnvelope(int adsr1, int adsr2) {
		this.adsr1 = adsr1 & 0xFFFF;
		this.adsr2 = adsr2 & 0xFFFF;
	}

	public Phase phase() {
		return phase;
	}

	/** 0..1 선형 볼륨. */
	public float level() {
		return level / 32767f;
	}

	public boolean off() {
		return phase == Phase.OFF;
	}

	/** 키 오프 → 릴리스 단계. */
	public void release() {
		if (phase != Phase.OFF) {
			phase = Phase.RELEASE;
			wait = 0;
		}
	}

	/** 초 단위 진행 (게임 틱 → 샘플 수). */
	public void advance(float sec) {
		advanceSamples(Math.round(sec * RATE));
	}

	public void advanceSamples(int n) {
		for (int i = 0; i < n && phase != Phase.OFF; i++) {
			if (--wait > 0) {
				continue;
			}
			step();
		}
	}

	private void step() {
		if (phase == Phase.DECAY && (level >> 11) <= (adsr1 & 0xF)) {
			// 하드웨어 비교: (레벨 >> 11) ≤ 서스테인 레벨 값 (0x80FF 처럼 SL=F 면 감쇠 없이 최대 유지)
			phase = Phase.SUSTAIN;
		}
		boolean exp;
		boolean dec;
		int shift;
		int stepVal;
		switch (phase) {
			case ATTACK -> {
				exp = (adsr1 & 0x8000) != 0;
				dec = false;
				shift = (adsr1 >> 10) & 0x1F;
				stepVal = 7 - ((adsr1 >> 8) & 3);
			}
			case DECAY -> {
				exp = true;
				dec = true;
				shift = (adsr1 >> 4) & 0xF;
				stepVal = -8;
			}
			case SUSTAIN -> {
				exp = (adsr2 & 0x8000) != 0;
				dec = (adsr2 & 0x4000) != 0;
				shift = (adsr2 >> 8) & 0x1F;
				int s = (adsr2 >> 6) & 3;
				stepVal = dec ? -8 + s : 7 - s;
			}
			case RELEASE -> {
				exp = (adsr2 & 0x20) != 0;
				dec = true;
				shift = adsr2 & 0x1F;
				stepVal = -8;
			}
			default -> {
				return;
			}
		}
		int cycles = 1 << Math.max(0, shift - 11);
		int st = stepVal << Math.max(0, 11 - shift);
		if (exp && !dec && level > 0x6000) {
			cycles *= 4;
		}
		if (exp && dec) {
			// 레벨이 낮아지면 정수 곱이 0 이 되어 −18dB 근처에서 멈추므로 최소 −1 로 끝까지 내린다 (원작 꼬리 근사)
			st = Math.min(-1, st * level / 0x8000);
		}
		wait = cycles;
		level = Math.max(0, Math.min(0x7FFF, level + st));
		switch (phase) {
			case ATTACK -> {
				if (level >= 0x7FFF) {
					phase = Phase.DECAY;
				}
			}
			case RELEASE -> {
				if (level <= 0) {
					phase = Phase.OFF;
				}
			}
			default -> {
			}
		}
	}
}
