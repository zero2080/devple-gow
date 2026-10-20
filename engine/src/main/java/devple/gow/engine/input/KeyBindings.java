package devple.gow.engine.input;

import devple.gow.tools.content.Content;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import static org.lwjgl.glfw.GLFW.*;

/**
 * 키 배치 (49 문서 §6). 계층의 {@code keys.properties} 를 덮어 합쳐 읽고, 없는 항목은 기본 배치 (원래 하드코딩과 같음).
 * <pre>
 *   CROSS=K,SPACE          PadButton 이름 = GLFW 키 이름 (쉼표로 여럿, 빈 값 = 키보드 없음)
 *   STICK_L_UP=W           스틱 방향 ({@link Stick})
 *   pad.CROSS=A            게임패드 버튼 = GLFW 게임패드 버튼 이름 (+ LEFT_TRIGGER / RIGHT_TRIGGER)
 * </pre>
 * 모르는 이름은 경고하고 건너뛴다. 값의 이름이 전부 모르는 것이면 기본을 그대로 둔다.
 * 상수는 컴파일 때 박히므로 GLFW 네이티브 없이 해석된다 (테스트 가능).
 */
public final class KeyBindings {

	public static final String FILE = "keys.properties";

	/** 키보드 스틱 방향. 게임패드 스틱은 축 그대로. */
	public enum Stick {
		STICK_L_UP, STICK_L_DOWN, STICK_L_LEFT, STICK_L_RIGHT,
		STICK_R_UP, STICK_R_DOWN, STICK_R_LEFT, STICK_R_RIGHT
	}

	/** 게임패드 트리거를 버튼처럼 쓸 때의 코드 (0x100 | GLFW 축 번호). 축 −1(뗌)..1 → 0 초과면 누름. */
	public static final int PAD_AXIS = 0x100;
	public static final int PAD_LEFT_TRIGGER = PAD_AXIS | GLFW_GAMEPAD_AXIS_LEFT_TRIGGER;
	public static final int PAD_RIGHT_TRIGGER = PAD_AXIS | GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER;

	private static final Map<String, Integer> KEY_NAMES = new HashMap<>();
	private static final Map<String, Integer> PAD_NAMES = new HashMap<>();

	static {
		for (char c = 'A'; c <= 'Z'; c++) {
			KEY_NAMES.put(String.valueOf(c), GLFW_KEY_A + (c - 'A'));
		}
		for (int d = 0; d <= 9; d++) {
			KEY_NAMES.put(String.valueOf(d), GLFW_KEY_0 + d);
			KEY_NAMES.put("KP_" + d, GLFW_KEY_KP_0 + d);
		}
		for (int f = 1; f <= 25; f++) {
			KEY_NAMES.put("F" + f, GLFW_KEY_F1 + f - 1);
		}
		Object[] named = {
				"SPACE", GLFW_KEY_SPACE, "APOSTROPHE", GLFW_KEY_APOSTROPHE, "COMMA", GLFW_KEY_COMMA,
				"MINUS", GLFW_KEY_MINUS, "PERIOD", GLFW_KEY_PERIOD, "SLASH", GLFW_KEY_SLASH,
				"SEMICOLON", GLFW_KEY_SEMICOLON, "EQUAL", GLFW_KEY_EQUAL, "LEFT_BRACKET", GLFW_KEY_LEFT_BRACKET,
				"BACKSLASH", GLFW_KEY_BACKSLASH, "RIGHT_BRACKET", GLFW_KEY_RIGHT_BRACKET, "GRAVE_ACCENT", GLFW_KEY_GRAVE_ACCENT,
				"ESCAPE", GLFW_KEY_ESCAPE, "ENTER", GLFW_KEY_ENTER, "TAB", GLFW_KEY_TAB, "BACKSPACE", GLFW_KEY_BACKSPACE,
				"INSERT", GLFW_KEY_INSERT, "DELETE", GLFW_KEY_DELETE, "RIGHT", GLFW_KEY_RIGHT, "LEFT", GLFW_KEY_LEFT,
				"DOWN", GLFW_KEY_DOWN, "UP", GLFW_KEY_UP, "PAGE_UP", GLFW_KEY_PAGE_UP, "PAGE_DOWN", GLFW_KEY_PAGE_DOWN,
				"HOME", GLFW_KEY_HOME, "END", GLFW_KEY_END, "CAPS_LOCK", GLFW_KEY_CAPS_LOCK,
				"SCROLL_LOCK", GLFW_KEY_SCROLL_LOCK, "NUM_LOCK", GLFW_KEY_NUM_LOCK, "PRINT_SCREEN", GLFW_KEY_PRINT_SCREEN,
				"PAUSE", GLFW_KEY_PAUSE, "KP_DECIMAL", GLFW_KEY_KP_DECIMAL, "KP_DIVIDE", GLFW_KEY_KP_DIVIDE,
				"KP_MULTIPLY", GLFW_KEY_KP_MULTIPLY, "KP_SUBTRACT", GLFW_KEY_KP_SUBTRACT, "KP_ADD", GLFW_KEY_KP_ADD,
				"KP_ENTER", GLFW_KEY_KP_ENTER, "KP_EQUAL", GLFW_KEY_KP_EQUAL,
				"LEFT_SHIFT", GLFW_KEY_LEFT_SHIFT, "LEFT_CONTROL", GLFW_KEY_LEFT_CONTROL, "LEFT_ALT", GLFW_KEY_LEFT_ALT,
				"LEFT_SUPER", GLFW_KEY_LEFT_SUPER, "RIGHT_SHIFT", GLFW_KEY_RIGHT_SHIFT,
				"RIGHT_CONTROL", GLFW_KEY_RIGHT_CONTROL, "RIGHT_ALT", GLFW_KEY_RIGHT_ALT,
				"RIGHT_SUPER", GLFW_KEY_RIGHT_SUPER, "MENU", GLFW_KEY_MENU};
		for (int i = 0; i < named.length; i += 2) {
			KEY_NAMES.put((String) named[i], (Integer) named[i + 1]);
		}
		Object[] pad = {
				"A", GLFW_GAMEPAD_BUTTON_A, "B", GLFW_GAMEPAD_BUTTON_B, "X", GLFW_GAMEPAD_BUTTON_X, "Y", GLFW_GAMEPAD_BUTTON_Y,
				"CROSS", GLFW_GAMEPAD_BUTTON_CROSS, "CIRCLE", GLFW_GAMEPAD_BUTTON_CIRCLE,
				"SQUARE", GLFW_GAMEPAD_BUTTON_SQUARE, "TRIANGLE", GLFW_GAMEPAD_BUTTON_TRIANGLE,
				"LEFT_BUMPER", GLFW_GAMEPAD_BUTTON_LEFT_BUMPER, "RIGHT_BUMPER", GLFW_GAMEPAD_BUTTON_RIGHT_BUMPER,
				"BACK", GLFW_GAMEPAD_BUTTON_BACK, "START", GLFW_GAMEPAD_BUTTON_START, "GUIDE", GLFW_GAMEPAD_BUTTON_GUIDE,
				"LEFT_THUMB", GLFW_GAMEPAD_BUTTON_LEFT_THUMB, "RIGHT_THUMB", GLFW_GAMEPAD_BUTTON_RIGHT_THUMB,
				"DPAD_UP", GLFW_GAMEPAD_BUTTON_DPAD_UP, "DPAD_RIGHT", GLFW_GAMEPAD_BUTTON_DPAD_RIGHT,
				"DPAD_DOWN", GLFW_GAMEPAD_BUTTON_DPAD_DOWN, "DPAD_LEFT", GLFW_GAMEPAD_BUTTON_DPAD_LEFT,
				"LEFT_TRIGGER", PAD_LEFT_TRIGGER, "RIGHT_TRIGGER", PAD_RIGHT_TRIGGER};
		for (int i = 0; i < pad.length; i += 2) {
			PAD_NAMES.put((String) pad[i], (Integer) pad[i + 1]);
		}
	}

	private static final PadButton[] B = PadButton.values();
	private static final Stick[] S = Stick.values();

	private final int[][] keys = new int[B.length][];
	private final int[][] sticks = new int[S.length][];
	private final int[][] pads = new int[B.length][];

	private static volatile KeyBindings cached;
	private static volatile Content cachedFor;

	private KeyBindings() {
	}

	/** 기본 배치: J=□ I=△ L=○ K=× Q=L1 E=R1 Z=L2 C=R2 Enter=START Tab=SELECT 방향키=방향키·우스틱 WASD=좌스틱 Shift=L3/R3. */
	public static KeyBindings defaults() {
		KeyBindings k = new KeyBindings();
		k.key(PadButton.SQUARE, GLFW_KEY_J).key(PadButton.TRIANGLE, GLFW_KEY_I)
				.key(PadButton.CIRCLE, GLFW_KEY_L).key(PadButton.CROSS, GLFW_KEY_K)
				.key(PadButton.L1, GLFW_KEY_Q).key(PadButton.R1, GLFW_KEY_E)
				.key(PadButton.L2, GLFW_KEY_Z).key(PadButton.R2, GLFW_KEY_C)
				.key(PadButton.START, GLFW_KEY_ENTER).key(PadButton.SELECT, GLFW_KEY_TAB)
				.key(PadButton.UP, GLFW_KEY_UP).key(PadButton.DOWN, GLFW_KEY_DOWN)
				.key(PadButton.LEFT, GLFW_KEY_LEFT).key(PadButton.RIGHT, GLFW_KEY_RIGHT)
				.key(PadButton.L3, GLFW_KEY_LEFT_SHIFT).key(PadButton.R3, GLFW_KEY_RIGHT_SHIFT);
		int[] stick = {GLFW_KEY_W, GLFW_KEY_S, GLFW_KEY_A, GLFW_KEY_D, GLFW_KEY_UP, GLFW_KEY_DOWN, GLFW_KEY_LEFT, GLFW_KEY_RIGHT};
		for (Stick s : S) {
			k.sticks[s.ordinal()] = new int[]{stick[s.ordinal()]};
		}
		k.pad(PadButton.CROSS, GLFW_GAMEPAD_BUTTON_A).pad(PadButton.CIRCLE, GLFW_GAMEPAD_BUTTON_B)
				.pad(PadButton.SQUARE, GLFW_GAMEPAD_BUTTON_X).pad(PadButton.TRIANGLE, GLFW_GAMEPAD_BUTTON_Y)
				.pad(PadButton.L1, GLFW_GAMEPAD_BUTTON_LEFT_BUMPER).pad(PadButton.R1, GLFW_GAMEPAD_BUTTON_RIGHT_BUMPER)
				.pad(PadButton.L2, PAD_LEFT_TRIGGER).pad(PadButton.R2, PAD_RIGHT_TRIGGER)
				.pad(PadButton.START, GLFW_GAMEPAD_BUTTON_START).pad(PadButton.SELECT, GLFW_GAMEPAD_BUTTON_BACK)
				.pad(PadButton.UP, GLFW_GAMEPAD_BUTTON_DPAD_UP).pad(PadButton.DOWN, GLFW_GAMEPAD_BUTTON_DPAD_DOWN)
				.pad(PadButton.LEFT, GLFW_GAMEPAD_BUTTON_DPAD_LEFT).pad(PadButton.RIGHT, GLFW_GAMEPAD_BUTTON_DPAD_RIGHT)
				.pad(PadButton.L3, GLFW_GAMEPAD_BUTTON_LEFT_THUMB).pad(PadButton.R3, GLFW_GAMEPAD_BUTTON_RIGHT_THUMB);
		return k;
	}

	private KeyBindings key(PadButton b, int k) {
		keys[b.ordinal()] = new int[]{k};
		return this;
	}

	private KeyBindings pad(PadButton b, int p) {
		pads[b.ordinal()] = new int[]{p};
		return this;
	}

	/** 계층의 keys.properties (Content 가 바뀌면 다시 읽음). */
	public static KeyBindings current() {
		Content c = Content.get();
		KeyBindings k = cached;
		if (k == null || cachedFor != c) {
			Properties p = Content.properties(FILE);
			k = parse(p);
			if (!p.isEmpty()) {
				System.out.println("[keys] " + FILE + " 적용: " + p.size() + "개 항목");
			}
			cached = k;
			cachedFor = c;
		}
		return k;
	}

	/** 기본 배치 위에 표를 덮는다. */
	public static KeyBindings parse(Properties p) {
		KeyBindings k = defaults();
		for (String name : p.stringPropertyNames()) {
			String key = name.trim();
			boolean isPad = key.regionMatches(true, 0, "pad.", 0, 4);
			String action = (isPad ? key.substring(4) : key).toUpperCase(Locale.ROOT);
			int[][] table;
			int idx;
			if (isPad) {
				table = k.pads;
				idx = buttonIndex(action);
			} else {
				idx = buttonIndex(action);
				table = k.keys;
				if (idx < 0) {
					idx = stickIndex(action);
					table = k.sticks;
				}
			}
			if (idx < 0) {
				warn("모르는 동작 '" + key + "' — 무시");
				continue;
			}
			int[] codes = codes(p.getProperty(name), isPad ? PAD_NAMES : KEY_NAMES, key);
			if (codes != null) {
				table[idx] = codes;
			}
		}
		return k;
	}

	/** 쉼표 목록 → 코드. 빈 값 = 없음 (빈 배열), 이름이 전부 모르는 것이면 null (기본 유지). */
	private static int[] codes(String value, Map<String, Integer> names, String action) {
		String v = value.trim();
		if (v.isEmpty()) {
			return new int[0];
		}
		List<Integer> out = new ArrayList<>();
		for (String part : v.split(",")) {
			String n = part.trim().toUpperCase(Locale.ROOT);
			if (n.isEmpty()) {
				continue;
			}
			Integer c = names.get(n.startsWith("KEY_") && !names.containsKey(n) ? n.substring(4) : n);
			if (c == null) {
				warn(action + ": 모르는 이름 '" + part.trim() + "'");
			} else if (!out.contains(c)) {
				out.add(c);
			}
		}
		if (out.isEmpty()) {
			warn(action + ": 쓸 이름이 없어 기본 유지");
			return null;
		}
		return out.stream().mapToInt(Integer::intValue).toArray();
	}

	private static int buttonIndex(String n) {
		for (PadButton b : B) {
			if (b.name().equals(n)) {
				return b.ordinal();
			}
		}
		return -1;
	}

	private static int stickIndex(String n) {
		for (Stick s : S) {
			if (s.name().equals(n)) {
				return s.ordinal();
			}
		}
		return -1;
	}

	private static void warn(String msg) {
		System.err.println("[keys] " + FILE + ": " + msg);
	}

	/** GLFW 키 이름 → 키 코드 (모르면 −1). */
	public static int keyCode(String name) {
		return KEY_NAMES.getOrDefault(name.trim().toUpperCase(Locale.ROOT), -1);
	}

	/** GLFW 게임패드 버튼 이름 → 코드 (트리거는 {@link #PAD_AXIS} | 축, 모르면 −1). */
	public static int padCode(String name) {
		return PAD_NAMES.getOrDefault(name.trim().toUpperCase(Locale.ROOT), -1);
	}

	public int[] keys(PadButton b) {
		return keys[b.ordinal()];
	}

	public int[] keys(Stick s) {
		return sticks[s.ordinal()];
	}

	/** 게임패드 버튼 코드 (트리거 = {@link #PAD_AXIS} | 축). */
	public int[] pad(PadButton b) {
		int[] p = pads[b.ordinal()];
		return p != null ? p : new int[0];
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		for (PadButton b : B) {
			sb.append(b).append('=').append(Arrays.toString(keys[b.ordinal()])).append(' ');
		}
		return sb.toString().trim();
	}
}
