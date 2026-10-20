package devple.gow.engine.input;

import org.lwjgl.glfw.GLFWGamepadState;

import static org.lwjgl.glfw.GLFW.*;

/**
 * 키보드/게임패드 → PS2 패드 추상화.
 * 원작 조작 (1편, Pad_Read 비트 순서 — {@link PadButton#USE}): □ 약공격, △ 강공격, ○ 잡기, X 점프, L1 방어, R1 대시,
 * L2 마법 (포세이돈 연타 = ○), R2 사용 (상자·문·레버·세이브), 우스틱 회피, L3+R3 신들의 분노.
 * <p>
 * 키보드 기본 배치: J=□ I=△ L=○ K=X, Q=L1 E=R1 Z=L2 C=R2, WASD=좌스틱, 방향키=우스틱(회피), Enter=START, Tab=SELECT,
 * 왼쪽/오른쪽 Shift = L3/R3. 계층의 {@code keys.properties} 로 바꾼다 ({@link KeyBindings}, 49 문서 §6).
 * <p>
 * 게임패드: GLFW 게임패드 매핑 (SDL 형식, Xbox·DualShock 4·DualSense 등 내장). 슬롯 1–16 중 첫 게임패드를 쓰고 꽂고 뺄 때 다시 찾는다.
 * 매핑이 없는 패드는 작업 폴더의 {@code gamecontrollerdb.txt} (또는 환경 변수 {@code GOW_PAD_DB} 경로) 를 시작할 때 읽어 추가한다.
 * 위치 대응은 표준 배치: 아래 = ×, 오른쪽 = ○, 왼쪽 = □, 위 = △ (Xbox A/B/X/Y 자리).
 */
public class Input {

	private final long window;
	private final KeyBindings bind = KeyBindings.current();
	private final GLFWGamepadState pad = GLFWGamepadState.create();
	private final boolean[] down = new boolean[PadButton.values().length];
	private final boolean[] prev = new boolean[PadButton.values().length];
	private float lx, ly, rx, ry;
	/** 쓰는 게임패드 슬롯 (GLFW_JOYSTICK_1.., −1 = 없음), 다시 찾기까지 남은 틱. */
	private int padSlot = -1, rescan;
	private static boolean mappingsLoaded;

	public Input(long window) {
		this.window = window;
		if (window != 0) {
			loadMappings();
		}
	}

	/** gamecontrollerdb.txt (SDL 게임패드 매핑) 가 있으면 GLFW 매핑에 추가 — 내장 매핑이 없는 패드용. */
	private static void loadMappings() {
		if (mappingsLoaded) {
			return;
		}
		mappingsLoaded = true;
		String env = System.getenv("GOW_PAD_DB");
		java.nio.file.Path db = java.nio.file.Path.of(env != null ? env : "gamecontrollerdb.txt");
		if (!java.nio.file.Files.isRegularFile(db)) {
			return;
		}
		try {
			String text = java.nio.file.Files.readString(db);
			try (org.lwjgl.system.MemoryStack st = org.lwjgl.system.MemoryStack.stackPush()) {
				boolean ok = glfwUpdateGamepadMappings(st.ASCII(text));
				System.out.println("게임패드 매핑 파일 " + db + (ok ? " 적용" : " 적용 실패"));
			}
		} catch (java.io.IOException | RuntimeException ex) {
			System.out.println("게임패드 매핑 파일 읽기 실패: " + ex.getMessage());
		}
	}

	/** 연결된 조이스틱 목록 (슬롯, 이름, 게임패드 매핑 여부·이름, GUID) — 진단용. */
	public static java.util.List<String> describeJoysticks() {
		java.util.List<String> out = new java.util.ArrayList<>();
		for (int j = GLFW_JOYSTICK_1; j <= GLFW_JOYSTICK_LAST; j++) {
			if (!glfwJoystickPresent(j)) {
				continue;
			}
			boolean gp = glfwJoystickIsGamepad(j);
			out.add(String.format("슬롯 %d: %s — %s (GUID %s)", j + 1, glfwGetJoystickName(j),
					gp ? "게임패드 매핑 있음: " + glfwGetGamepadName(j) : "매핑 없음 (gamecontrollerdb.txt 필요)", glfwGetJoystickGUID(j)));
		}
		return out;
	}

	/** 지금 쓰는 게임패드 이름 (없으면 null). */
	public String padName() {
		return padSlot >= 0 ? glfwGetGamepadName(padSlot) : null;
	}

	/** 첫 게임패드 슬롯 찾기 (바뀌면 로그). */
	private void findPad() {
		int found = -1;
		for (int j = GLFW_JOYSTICK_1; j <= GLFW_JOYSTICK_LAST && found < 0; j++) {
			if (glfwJoystickIsGamepad(j)) {
				found = j;
			}
		}
		if (found != padSlot) {
			if (found >= 0) {
				System.out.println("게임패드 연결: " + glfwGetGamepadName(found) + " (슬롯 " + (found + 1) + ")");
			} else if (padSlot >= 0) {
				System.out.println("게임패드 연결 해제");
			}
			if (found < 0) {
				describeJoysticks().forEach(d -> System.out.println("  조이스틱 " + d));
			}
			padSlot = found;
		}
	}

	/** 로직 틱마다 1회 호출. */
	public void poll() {
		System.arraycopy(down, 0, prev, 0, down.length);
		if (--rescan <= 0 || padSlot >= 0 && !glfwJoystickIsGamepad(padSlot)) {
			findPad(); // 꽂고 빼기: 1 초마다 다시 찾는다
			rescan = 60;
		}
		boolean hasPad = padSlot >= 0 && glfwGetGamepadState(padSlot, pad);
		for (PadButton b : PadButton.values()) {
			boolean v = any(bind.keys(b));
			if (hasPad) {
				for (int pb : bind.pad(b)) {
					v |= (pb & KeyBindings.PAD_AXIS) != 0
							? pad.axes(pb & 0xFF) > 0f // 트리거 −1(뗌)..1 → 절반 이상
							: pad.buttons(pb) == GLFW_PRESS;
				}
			}
			down[b.ordinal()] = v;
		}
		lx = key(KeyBindings.Stick.STICK_L_RIGHT) - key(KeyBindings.Stick.STICK_L_LEFT);
		ly = key(KeyBindings.Stick.STICK_L_UP) - key(KeyBindings.Stick.STICK_L_DOWN);
		rx = key(KeyBindings.Stick.STICK_R_RIGHT) - key(KeyBindings.Stick.STICK_R_LEFT);
		ry = key(KeyBindings.Stick.STICK_R_UP) - key(KeyBindings.Stick.STICK_R_DOWN);
		if (hasPad) {
			lx = pick(lx, pad.axes(GLFW_GAMEPAD_AXIS_LEFT_X));
			ly = pick(ly, -pad.axes(GLFW_GAMEPAD_AXIS_LEFT_Y));
			rx = pick(rx, pad.axes(GLFW_GAMEPAD_AXIS_RIGHT_X));
			ry = pick(ry, -pad.axes(GLFW_GAMEPAD_AXIS_RIGHT_Y));
		}
	}

	private boolean any(int[] ks) {
		for (int k : ks) {
			if (glfwGetKey(window, k) == GLFW_PRESS) {
				return true;
			}
		}
		return false;
	}

	private float key(KeyBindings.Stick s) {
		return any(bind.keys(s)) ? 1f : 0f;
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
