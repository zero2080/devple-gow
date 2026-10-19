package devple.gow.engine.input;

import org.lwjgl.glfw.GLFWGamepadState;

import java.util.EnumMap;
import java.util.Map;

import static org.lwjgl.glfw.GLFW.*;

/**
 * 키보드/게임패드 → PS2 패드 추상화.
 * 원작 조작: □ 약공격, △ 강공격, ○ 잡기, X 점프, L1 방어, R1 상호작용/마법 사용(R1+버튼), R2 마법 발동, 우스틱 회피.
 * <p>
 * 키보드 기본 배치: J=□ I=△ L=○ K=X, Q=L1 E=R1 Z=L2 C=R2, WASD=좌스틱, 방향키=우스틱(회피), Enter=START, Tab=SELECT
 */
public class Input {

	private static final Map<PadButton, Integer> KEYS = new EnumMap<>(Map.ofEntries(
			Map.entry(PadButton.SQUARE, GLFW_KEY_J), Map.entry(PadButton.TRIANGLE, GLFW_KEY_I),
			Map.entry(PadButton.CIRCLE, GLFW_KEY_L), Map.entry(PadButton.CROSS, GLFW_KEY_K),
			Map.entry(PadButton.L1, GLFW_KEY_Q), Map.entry(PadButton.R1, GLFW_KEY_E),
			Map.entry(PadButton.L2, GLFW_KEY_Z), Map.entry(PadButton.R2, GLFW_KEY_C),
			Map.entry(PadButton.START, GLFW_KEY_ENTER), Map.entry(PadButton.SELECT, GLFW_KEY_TAB),
			Map.entry(PadButton.UP, GLFW_KEY_UP), Map.entry(PadButton.DOWN, GLFW_KEY_DOWN),
			Map.entry(PadButton.LEFT, GLFW_KEY_LEFT), Map.entry(PadButton.RIGHT, GLFW_KEY_RIGHT),
			Map.entry(PadButton.L3, GLFW_KEY_LEFT_SHIFT), Map.entry(PadButton.R3, GLFW_KEY_RIGHT_SHIFT)));

	private static final Map<PadButton, Integer> PAD = new EnumMap<>(Map.ofEntries(
			Map.entry(PadButton.CROSS, GLFW_GAMEPAD_BUTTON_A), Map.entry(PadButton.CIRCLE, GLFW_GAMEPAD_BUTTON_B),
			Map.entry(PadButton.SQUARE, GLFW_GAMEPAD_BUTTON_X), Map.entry(PadButton.TRIANGLE, GLFW_GAMEPAD_BUTTON_Y),
			Map.entry(PadButton.L1, GLFW_GAMEPAD_BUTTON_LEFT_BUMPER), Map.entry(PadButton.R1, GLFW_GAMEPAD_BUTTON_RIGHT_BUMPER),
			Map.entry(PadButton.START, GLFW_GAMEPAD_BUTTON_START), Map.entry(PadButton.SELECT, GLFW_GAMEPAD_BUTTON_BACK),
			Map.entry(PadButton.UP, GLFW_GAMEPAD_BUTTON_DPAD_UP), Map.entry(PadButton.DOWN, GLFW_GAMEPAD_BUTTON_DPAD_DOWN),
			Map.entry(PadButton.LEFT, GLFW_GAMEPAD_BUTTON_DPAD_LEFT), Map.entry(PadButton.RIGHT, GLFW_GAMEPAD_BUTTON_DPAD_RIGHT),
			Map.entry(PadButton.L3, GLFW_GAMEPAD_BUTTON_LEFT_THUMB), Map.entry(PadButton.R3, GLFW_GAMEPAD_BUTTON_RIGHT_THUMB)));

	private final long window;
	private final GLFWGamepadState pad = GLFWGamepadState.create();
	private final boolean[] down = new boolean[PadButton.values().length];
	private final boolean[] prev = new boolean[PadButton.values().length];
	private float lx, ly, rx, ry;

	public Input(long window) {
		this.window = window;
	}

	/** 로직 틱마다 1회 호출. */
	public void poll() {
		System.arraycopy(down, 0, prev, 0, down.length);
		boolean hasPad = glfwJoystickIsGamepad(GLFW_JOYSTICK_1) && glfwGetGamepadState(GLFW_JOYSTICK_1, pad);
		for (PadButton b : PadButton.values()) {
			boolean v = glfwGetKey(window, KEYS.get(b)) == GLFW_PRESS;
			if (hasPad) {
				Integer pb = PAD.get(b);
				if (pb != null) {
					v |= pad.buttons(pb) == GLFW_PRESS;
				} else if (b == PadButton.L2) {
					v |= pad.axes(GLFW_GAMEPAD_AXIS_LEFT_TRIGGER) > 0.3f;
				} else if (b == PadButton.R2) {
					v |= pad.axes(GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER) > 0.3f;
				}
			}
			down[b.ordinal()] = v;
		}
		lx = key(GLFW_KEY_D) - key(GLFW_KEY_A);
		ly = key(GLFW_KEY_W) - key(GLFW_KEY_S);
		rx = key(GLFW_KEY_RIGHT) - key(GLFW_KEY_LEFT);
		ry = key(GLFW_KEY_UP) - key(GLFW_KEY_DOWN);
		if (hasPad) {
			lx = pick(lx, pad.axes(GLFW_GAMEPAD_AXIS_LEFT_X));
			ly = pick(ly, -pad.axes(GLFW_GAMEPAD_AXIS_LEFT_Y));
			rx = pick(rx, pad.axes(GLFW_GAMEPAD_AXIS_RIGHT_X));
			ry = pick(ry, -pad.axes(GLFW_GAMEPAD_AXIS_RIGHT_Y));
		}
	}

	private float key(int k) {
		return glfwGetKey(window, k) == GLFW_PRESS ? 1f : 0f;
	}

	private static float pick(float keyValue, float axis) {
		return Math.abs(axis) > 0.2f ? axis : keyValue;
	}

	public boolean held(PadButton b) {
		return down[b.ordinal()];
	}

	public boolean pressed(PadButton b) {
		return down[b.ordinal()] && !prev[b.ordinal()];
	}

	public boolean released(PadButton b) {
		return !down[b.ordinal()] && prev[b.ordinal()];
	}

	public float leftX() {
		return lx;
	}

	public float leftY() {
		return ly;
	}

	public float rightX() {
		return rx;
	}

	public float rightY() {
		return ry;
	}
}
