package devple.gow.engine.audio;

import devple.gow.tools.audio.SoundBank;

import java.util.Random;

/**
 * 989snd LFO 한 슬롯 (IRX: 설정 0x4F14, 초기화 0x22DC, 깊이 0x29D4, 계산 0x24D8, 모양 표 0x1E444 — 29 문서 §9).
 * 핸들러(사운드 인스턴스)마다 슬롯 4개. 240Hz 틱마다 위상 += step·2 (주기 = 2048 &lt;&lt; 16), 값 = 모양(위상 &gt;&gt; 16) ∈ ±0x7FFF.
 */
final class SoundLfo {

	static final int SLOTS = 4;
	static final int VOLUME = 1, PAN = 2, PMOD = 3, PBEND = 4, RATE = 5, DEPTH = 6;

	final int target;
	final int targetExtra;
	final int shape;
	final int duty;
	final int flags;
	final int baseDepth;
	final int baseStep;
	int depth;
	int step;
	/** 대상별 배율 적용 깊이 (0x29D4 → +0x20). */
	int scaled;
	int phase;
	private int randValue;
	private boolean randHigh;

	SoundLfo(SoundBank.Lfo p, Random rng, int soundVol) {
		target = p.target();
		targetExtra = p.targetExtra();
		shape = p.shape();
		duty = p.duty();
		flags = p.flags();
		baseDepth = depth = p.depth();
		baseStep = step = p.step();
		phase = ((flags & 2) != 0 ? rng.nextInt(0x800) : p.startPhase()) << 16;
		if (shape == 5) {
			randValue = -(rng.nextInt(0x8000)) * rng.nextInt(2);
			randHigh = true;
		}
		rescale(soundVol);
	}

	/** 0x29D4: 대상별 깊이 배율. 볼륨은 소리 볼륨 × depth / 1024, 팬 ×45/256 (1024 = 180°), PMOD ×381/64, PBEND ×32767/1024. */
	void rescale(int soundVol) {
		scaled = switch (target) {
			case VOLUME -> soundVol * depth >> 10;
			case PAN -> depth * 45 >> 8;
			case PMOD -> depth * 381 >> 6;
			case PBEND -> depth * 32767 >> 10;
			default -> 0;
		};
	}

	/** 한 틱: 위상 진행 후 (진행 전 위상의) 모양 값, flags bit0 이면 반전. */
	int next(Random rng) {
		int p = phase >> 16;
		phase += step * 2;
		if (phase > 0x7FFFFFF || phase < 0) {
			phase -= 0x8000000;
		}
		int v = shape(p & 0x7FF, rng);
		return (flags & 1) != 0 ? -v : v;
	}

	private int shape(int p, Random rng) {
		return switch (shape) {
			case 1 -> (int) Math.round(Math.sin(p * Math.PI / 1024) * 0x7FFF); // 1/4 주기 표 0x1E038 (513개)
			case 2 -> p < duty ? 0x7FFF : -0x7FFF;
			case 3 -> p < 0x200 ? p * 0x7FFF / 0x200
					: p < 0x600 ? 0x7FFF - (p - 0x200) * 0x7FFF * 2 / 0x400 : (p - 0x600) * 0x7FFF / 0x200 - 0x7FFF;
			case 4 -> p < 0x400 ? p * 0x7FFF / 0x400 : (p - 0x400) * 0x7FFF / 0x400 - 0x7FFF;
			case 5 -> random(p, rng);
			default -> 0;
		};
	}

	/** 0x2244: 반 주기마다 새 값 (앞 절반 진입 = [-0x7FFF..0] 또는 0, 뒤 절반 진입 = ±0x3FFF·2). */
	private int random(int p, Random rng) {
		if (p >= 0x400) {
			if (randHigh) {
				randHigh = false;
				randValue = ((rng.nextInt(0x8000)) - 0x3FFF) * 2;
			}
		} else if (!randHigh) {
			randHigh = true;
			randValue = -(rng.nextInt(0x8000)) * rng.nextInt(2);
		}
		return randValue;
	}
}
