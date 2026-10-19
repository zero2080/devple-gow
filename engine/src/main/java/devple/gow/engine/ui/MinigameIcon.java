package devple.gow.engine.ui;

/**
 * HUD 미니게임 버튼 아이콘 (원작 sub_001BA338, 35 문서 §6): 아이콘 번호가 바뀌면 이전 심볼을 떼고 표 0x29DA40 의 이름 심볼을
 * FLP_HUDK 루트에 붙인다 ({@link FlpPlayer#attach}). 한 번에 하나, 위치는 FLP 에 배치된 그대로 (화면 위쪽 가운데).
 */
public final class MinigameIcon {

	/** 원작 표 0x29DA40 (15개). 0..9 = TimedPress 종류 ({@code TimedPressQte.ICON_CLIPS}), 10.. = 연타·스틱. */
	public static final String[] CLIPS = {"XButtonGreek", "TriangleButtonGreek", "CircleButtonGreek", "SquareButtonGreek",
			"Up180CW", "Up180CCW", "Down180CW", "Down180CCW", "360CW", "360CCW", "XButtonPress", "R2ButtonPress", "WiggleStick",
			"WiggleBackForth", "R2ButtonStatic"};
	public static final int NONE = -1, BUTTON_PRESS = 10, R2_PRESS = 11, WIGGLE_STICK = 12, WIGGLE_BACK_FORTH = 13,
			/** 버튼 안내 센서 (EventSensor PromptOnEnter, 원작 슬롯 1 ← 14). */
			PROMPT = 14;

	private int current = NONE;

	public int current() {
		return current;
	}

	/**
	 * idx = 표 번호 (NONE = 숨김). 원작 슬롯 설정 sub_0021DA50 은 10..13(연타·스틱) 으로 바뀔 때 SND_ICON 을 낸다.
	 *
	 * @return 바뀌어서 SND_ICON 을 내야 하면 true
	 */
	public boolean show(FlpPlayer p, int idx) {
		if (idx == current) {
			return false;
		}
		if (current >= 0) {
			p.detach(CLIPS[current]);
		}
		current = NONE;
		if (idx >= 0 && idx < CLIPS.length && p.attach(CLIPS[idx])) {
			current = idx;
		}
		return idx >= BUTTON_PRESS && idx <= WIGGLE_BACK_FORTH;
	}
}
