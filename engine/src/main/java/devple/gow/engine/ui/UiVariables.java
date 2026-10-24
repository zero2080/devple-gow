package devple.gow.engine.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 엔진 ↔ FLP 공유 변수 ({@code PS2_*}). 원작 g_hud(0x29D9F4) 변수 블록에 대응 (06 문서 0x1B6490 등록 ~200개).
 * <p>
 * 이름은 플래시 4 처럼 대소문자 무시. 경로가 붙은 {@code _level0/:PS2_X} 도 같은 변수로 취급한다.
 * 게임은 매 프레임 값을 세팅하고 {@code PS2_*_Event} 에 이벤트 번호를 넣으면, FLP 의 SimKeyEvent 스크립트가
 * 소비한 뒤 -1 로 되돌린다. FLP 가 쓰는 {@code PS2_Sound1/2}, {@code PS2_Done}, {@code PS2_Menu_Choice} 등은
 * {@link Listener} 로 게임에 알린다.
 */
public final class UiVariables {

	/** FLP 스크립트가 변수를 바꿨을 때 (엔진이 직접 set 한 것은 알리지 않음). */
	public interface Listener {
		void changed(String name, String value);
	}

	private final Map<String, String> values = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
	private final List<Listener> listeners = new ArrayList<>();

	/** 전역(엔진 공유) 변수인지: 이름이 PS2_ 로 시작. */
	public static boolean isGlobal(String name) {
		return name.regionMatches(true, 0, "PS2_", 0, 4);
	}

	public String get(String name) {
		return values.get(name);
	}

	public boolean has(String name) {
		return values.containsKey(name);
	}

	public double number(String name) {
		return FlpVm.toNumber(values.get(name));
	}

	/** 없거나 숫자가 아니면 def. */
	public double number(String name, double def) {
		String s = values.get(name);
		if (s == null || s.isEmpty()) {
			return def;
		}
		double d = FlpVm.toNumber(s);
		return Double.isNaN(d) ? def : d;
	}

	/** 엔진 쪽 세팅. */
	public UiVariables set(String name, String value) {
		values.put(name, value);
		return this;
	}

	public UiVariables set(String name, double value) {
		return set(name, FlpVm.format(value));
	}

	/** 이벤트 발행: FLP 가 다음 SimKeyEvent 에서 처리하고 -1 로 되돌린다. */
	public UiVariables event(String name, int value) {
		return set(name, value);
	}

	/** FLP 스크립트 쪽 세팅 (리스너 통지). */
	void setFromScript(String name, String value) {
		String old = values.put(name, value);
		if (!value.equals(old) || name.regionMatches(true, 0, "PS2_Sound", 0, 9)) {
			for (Listener l : listeners) {
				l.changed(name, value);
			}
		}
	}

	public void addListener(Listener l) {
		listeners.add(l);
	}

	// ---------------------------------------------------------------- 타자 효과

	/** 원작 타자 속도 (0x29CCD8 = 50.0 글자/초, 그리기 0x1486F8). */
	public static final float TYPE_RATE = 50f;
	private String typePrefix;
	private int typeFrom, typeTo;
	private float typeTime;
	private boolean typeDone;

	/**
	 * 메시지 타자 효과 시작 (원작 0x1B9B00 → 0x1482E0: 변수 이름 "PS2_MessageTemplate_Line%d" 의 from..to 범위, 경과 0x29CCD4 · 끝 0x29CCCC 를 0) [확실]:
	 * 그리기 (0x148600 – 0x148724) 가 범위 안 변수의 글자를 경과 × 50 자까지만 (앞 줄부터 차례로) 보이고, 끝 표시면 9999 자. prefix = null 이면 끔.
	 */
	public void typeLines(String prefix, int from, int to) {
		typePrefix = prefix;
		typeFrom = from;
		typeTo = to;
		typeTime = 0;
		typeDone = false;
	}

	/** 그리는 프레임마다 경과 += dt (0x1486C4 – 0x1486DC). */
	public void typeTick(float dt) {
		if (typePrefix != null && !typeDone) {
			typeTime += dt;
		}
	}

	/**
	 * 확정 버튼 (0x148360): 이미 끝 표시였으면 true, 아니면 끝 표시로 바꾸고 false — 원작 메시지 상자 (0x1B8E40) 는 첫 ○ 를 글자를 다 보이는 데만 쓰고
	 * 두 번째 ○ 에 넘긴다 (다 쳐진 뒤라도 끝 표시는 버튼으로만 선다) [확실].
	 */
	public boolean typeComplete() {
		if (typePrefix == null || typeDone) {
			return true;
		}
		typeDone = true;
		return false;
	}

	/** 타자 중인지 (끝 표시 전). */
	public boolean typing() {
		return typePrefix != null && !typeDone;
	}

	/** 그릴 글자: 타자 범위 안 변수면 앞 줄 글자 수를 뺀 만큼만 ([…] 버튼 태그는 한 글자), 아니면 그대로. */
	public String typed(String var, String text) {
		if (typePrefix == null || typeDone || text == null || var == null) {
			return text;
		}
		int line = lineOf(var);
		if (line < typeFrom || line > typeTo) {
			return text;
		}
		int budget = (int) (TYPE_RATE * typeTime);
		for (int k = typeFrom; k < line; k++) {
			budget -= units(get(typePrefix + k));
		}
		if (budget <= 0) {
			return "";
		}
		int i = 0, n = 0;
		while (i < text.length() && n < budget) {
			int e = text.charAt(i) == '[' ? text.indexOf(']', i) : -1;
			i = e > i ? e + 1 : i + 1;
			n++;
		}
		return text.substring(0, i);
	}

	private int lineOf(String var) {
		String v = var;
		int colon = v.lastIndexOf(':');
		if (colon >= 0) {
			v = v.substring(colon + 1);
		}
		if (!v.regionMatches(true, 0, typePrefix, 0, typePrefix.length())) {
			return -1;
		}
		try {
			return Integer.parseInt(v.substring(typePrefix.length()));
		} catch (NumberFormatException ex) {
			return -1;
		}
	}

	/** 글자 수 ([…] 태그 하나 = 1). */
	static int units(String s) {
		if (s == null) {
			return 0;
		}
		int i = 0, n = 0;
		while (i < s.length()) {
			int e = s.charAt(i) == '[' ? s.indexOf(']', i) : -1;
			i = e > i ? e + 1 : i + 1;
			n++;
		}
		return n;
	}

	public Map<String, String> all() {
		return values;
	}
}
