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

	public Map<String, String> all() {
		return values;
	}
}
