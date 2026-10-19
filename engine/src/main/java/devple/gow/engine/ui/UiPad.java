package devple.gow.engine.ui;

import devple.gow.engine.input.Input;
import devple.gow.engine.input.PadButton;

/**
 * 엔진 패드 → FLP 입력 핸들러 버튼 마스크.
 * <p>
 * 확인된 비트(07 문서 §4.2): 0x10 △(뒤로), 0x20 ○(선택), 0x1000/0x2000/0x4000/0x8000 방향키 상/우/하/좌,
 * 0x10000.. 왼쪽 스틱 같은 순서, 0x100000.. 오른쪽 스틱.
 * 나머지는 PS2 패드 버튼 워드의 두 바이트를 바꾼 배치(하위 바이트 L2 R2 L1 R1 △ ○ X □, 상위 바이트 SELECT L3 R3 START ↑ → ↓ ←)로 추정:
 * 이 배치가 확인된 비트와 모두 맞고, 일시정지 화면의 0x100/0x800 뗌 핸들러(SELECT/START 로 닫기)와도 일치한다.
 */
public final class UiPad {

	public static final int L2 = 0x1, R2 = 0x2, L1 = 0x4, R1 = 0x8;
	public static final int TRIANGLE = 0x10, CIRCLE = 0x20, CROSS = 0x40, SQUARE = 0x80;
	public static final int SELECT = 0x100, L3 = 0x200, R3 = 0x400, START = 0x800;
	public static final int UP = 0x1000, RIGHT = 0x2000, DOWN = 0x4000, LEFT = 0x8000;
	/** 왼쪽 스틱 방향 = 방향키 비트 << 4, 오른쪽 스틱 = << 8. */
	public static final int LSTICK_SHIFT = 4, RSTICK_SHIFT = 8;

	private UiPad() {
	}

	public static int bit(PadButton b) {
		return switch (b) {
			case L2 -> L2;
			case R2 -> R2;
			case L1 -> L1;
			case R1 -> R1;
			case TRIANGLE -> TRIANGLE;
			case CIRCLE -> CIRCLE;
			case CROSS -> CROSS;
			case SQUARE -> SQUARE;
			case SELECT -> SELECT;
			case L3 -> L3;
			case R3 -> R3;
			case START -> START;
			case UP -> UP;
			case RIGHT -> RIGHT;
			case DOWN -> DOWN;
			case LEFT -> LEFT;
		};
	}

	/** 현재 눌린 상태 마스크. 스틱은 0.5 이상 기울이면 방향 비트. */
	public static int mask(Input in) {
		int m = 0;
		for (PadButton b : PadButton.values()) {
			if (in.held(b)) {
				m |= bit(b);
			}
		}
		m |= stick(in.leftX(), in.leftY()) << LSTICK_SHIFT;
		m |= stick(in.rightX(), in.rightY()) << RSTICK_SHIFT;
		return m;
	}

	private static int stick(float x, float y) {
		int m = 0;
		if (y > 0.5f) {
			m |= UP;
		} else if (y < -0.5f) {
			m |= DOWN;
		}
		if (x > 0.5f) {
			m |= RIGHT;
		} else if (x < -0.5f) {
			m |= LEFT;
		}
		return m;
	}
}
