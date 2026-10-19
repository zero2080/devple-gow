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

	public static boolean anyNewButton(Input in) {
		for (int c = 1; c <= 8; c++) {
			if (in.pressed(button(c))) {
				return true;
			}
		}
		return false;
	}
}
