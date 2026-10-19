package devple.gow.engine.game;

import devple.gow.engine.audio.SoundManager;
import devple.gow.engine.ui.UiVariables;

/**
 * 셸(메인 메뉴) FLP 가 읽는 엔진 변수 초기화 + 옵션 반영 (원작 상태 1–3, 11 문서 §6.1).
 * 값이 비어 있으면 메뉴 핸들러 조건이 통과하지 않아 버튼이 무반응이 되거나(EnableButtons), 난이도·코스튬 선택이 건너뛰어진다.
 * <pre>
 *   PS2_EnableButtons      1      메뉴 입력 허용
 *   PS2_Difficulty_Level   0..3   난이도 커서 초기값 (메뉴가 선택 결과를 같은 변수에 다시 씀)
 *   PS2_HardMode_Unlocked  0/1    "신(매우 어려움)" 난이도 해금 (원작: 게임 완료 해금 비트 0–2, 11 문서 §2.3)
 *   PS2_Costumes_Unlocked  0/1    보너스 코스튬 (해금 비트 3)
 *   PS2_Sound/MusicVolume  0..10  옵션 메뉴가 ±1 (범위는 스크립트 비교값 0 / 10) — 기본 10 [추정]
 *   PS2_Vibration / WideScreen / ProgressiveScan / DeFlicker / SoundMode   화면·패드 옵션 (현재 표시만)
 * </pre>
 */
public final class ShellBinding {

	public static final int MAX_VOLUME = 10;

	private ShellBinding() {
	}

	public static void init(UiVariables v, int unlockBits) {
		v.set("PS2_EnableButtons", 1);
		// 난이도 커서 초기값 (메뉴가 이 값으로 _level0/:ShellDifficulty 를 맞춘 뒤 선택 결과를 다시 쓴다). 기본 1 = 전사(보통)
		if (!v.has("PS2_Difficulty_Level") || v.get("PS2_Difficulty_Level").isEmpty()) {
			v.set("PS2_Difficulty_Level", 1);
		}
		v.set("PS2_HardMode_Unlocked", (unlockBits & 0x7) != 0 ? 1 : 0);
		v.set("PS2_Costumes_Unlocked", (unlockBits & 0x8) != 0 ? 1 : 0);
		v.set("PS2_Costume_Choice", 0);
		v.set("PS2_SoundVolume", MAX_VOLUME);
		v.set("PS2_MusicVolume", MAX_VOLUME);
		v.set("PS2_Vibration", 1);
		v.set("PS2_WideScreen", 0);
		v.set("PS2_ProgressiveScan", 0);
		v.set("PS2_DeFlicker", 0);
		v.set("PS2_SoundMode", 0);
	}

	/** 옵션 메뉴가 음량을 바꾸면 사운드 그룹 음량에 반영 (그룹 0 = SFX, 1 = Music). */
	public static void bindAudio(UiVariables v, SoundManager snd) {
		v.addListener((name, value) -> {
			if (value == null || value.isEmpty()) {
				return;
			}
			try {
				float f = Math.max(0, Math.min(MAX_VOLUME, Float.parseFloat(value))) / MAX_VOLUME;
				if (name.equals("PS2_SoundVolume")) {
					snd.setGroupVolume(0, f);
				} else if (name.equals("PS2_MusicVolume")) {
					snd.setGroupVolume(1, f);
				}
			} catch (NumberFormatException ignored) {
				// 숫자가 아닌 값
			}
		});
	}
}
