package devple.gow.engine.combat;

import devple.gow.engine.input.Input;
import devple.gow.engine.input.PadButton;

/**
 * 분기 입력 코드(10 문서 §2.5) ↔ 엔진 패드. 코드 1–10 = 버튼, 11 이상 = 자동/이벤트.
 * 코드 6(마법) = 패드 비트 0 = L2, 코드 9 = 비트 1 = R2 (사용 버튼), 코드 8 = R1 (대시) [확실: Pad_Read 비트 순서, {@link PadButton#USE}].
 */
public final class CombatInput {

	public static final int AUTO = 11, ANY_INPUT = 12, EV_HIT = 13, EV_DEATH = 14, EV_BLOCKED = 16, EV_HIT_CONFIRM = 17,
			STICK = 19, EV_CIRCLE = 21, EV_WAS_BLOCKED = 22;

	private CombatInput() {
	}

	public static PadButton button(int code) {
		return switch (code) {
			case 1 -> PadButton.CROSS;
			case 2 -> PadButton.SQUARE;
			case 3 -> PadButton.TRIANGLE;
			case 4 -> PadButton.CIRCLE;
			case 5 -> PadButton.L1;
			case 6 -> PadButton.L2; // 마법: 패드 비트 0 = L2 (PadButton.USE 주석)
			case 7 -> PadButton.L3;
			case 8 -> PadButton.R1;
			case 9 -> PadButton.R2; // 패드 비트 1 = R2 (영웅 기술에는 안 쓰임, 사용 버튼)
			case 10 -> PadButton.R3;
			default -> null;
		};
	}

	/** 버튼 비트마스크 (PadButton ordinal). */
	public static int mask(Input in) {
		int m = 0;
		for (PadButton b : PadButton.values()) {
			if (in.held(b)) {
				m |= 1 << b.ordinal();
			}
		}
		return m;
	}

	/**
	 * ANY_INPUT 의 버튼 (폴링 표 0x2B9290 [12] = 0x207FE0): 새로 눌린 패드 비트 (+0x28 &amp; (+0x28 ^ +0x2C)) &amp; 0xFF — 비트 0–7 = L2 R2 L1 R1 △ ○ × □
	 * [확실: 코드]. 예전 엔진은 분기 코드 1–8 (× □ △ ○ L1 L2 L3 R1) 을 돌아 R2 (사용) 가 빠지고 L3 가 들어 있었다 — 싸운 뒤 IDLECOMBAT (M80, ANY_INPUT
	 * → 기술 끝) 에 선 채 R2 를 누르면 기술이 끝나지 않아 내비게이션 (레버·상자·크랭크 잡기 0x1C3F18) 이 그 R2 를 받지 못했다 (PAND01A 레버, 43 §34).
	 */
	private static final PadButton[] PAD_BITS_0_7 = {PadButton.L2, PadButton.R2, PadButton.L1, PadButton.R1, PadButton.TRIANGLE, PadButton.CIRCLE,
			PadButton.CROSS, PadButton.SQUARE};

	public static boolean anyNewButton(Input in) {
		for (PadButton b : PAD_BITS_0_7) {
			if (in.pressed(b)) {
				return true;
			}
		}
		return false;
	}
}
