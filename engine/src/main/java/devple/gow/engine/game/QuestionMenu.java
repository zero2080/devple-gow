package devple.gow.engine.game;

import devple.gow.tools.ui.MessageTable;

import java.util.ArrayList;
import java.util.List;

/**
 * 질문 메시지 → HUD 메뉴 템플릿 내용 (Internal 0xB, 42 문서 §2). 본문 형식 "질문 줄 ---- *예 / 아니오" (1005·1013·1019):
 * 위 = 줄 (최대 3, PS2_MenuTemplate_Line1..3), 아래 = 선택지 (최대 5, Menu1..5), '*' = 기본 선택 (없으면 1).
 * 선택지가 없으면 원작 기본 "Yes"/"No" (0x1B9EE8) = 메시지 4057 예 / 4056 아니오.
 *
 * @param defaultChoice 1 부터
 */
public record QuestionMenu(List<String> lines, List<String> choices, int defaultChoice) {

	public static QuestionMenu of(MessageTable messages, int id) {
		return parse(messages != null ? messages.get(id) : null, messages);
	}

	public static QuestionMenu parse(String text, MessageTable messages) {
		List<String> lines = new ArrayList<>(), choices = new ArrayList<>();
		int def = 1;
		if (text != null) {
			String[] parts = text.split("----", 2);
			for (String l : parts[0].split("\n")) {
				if (!l.isBlank() && lines.size() < 3) {
					lines.add(l.trim());
				}
			}
			if (parts.length > 1) {
				for (String c : parts[1].split("\n")) {
					if (!c.isBlank() && choices.size() < 5) {
						String t = c.trim();
						if (t.startsWith("*")) {
							def = choices.size() + 1;
							t = t.substring(1);
						}
						choices.add(t);
					}
				}
			}
		}
		if (choices.isEmpty()) {
			choices.add(messages != null && messages.get(4057) != null ? messages.get(4057) : "예");
			choices.add(messages != null && messages.get(4056) != null ? messages.get(4056) : "아니오");
		}
		if (lines.isEmpty()) {
			lines.add("");
		}
		return new QuestionMenu(lines, choices, def);
	}

	/** FLP MenuTemplates 프레임 (GotoFrame2 는 1 부터): 10·줄 수 + 선택지 수 — 1 줄 2 지 = 12 = 원작 GameMgr +0x208. */
	public int frame() {
		return 10 * lines.size() + choices.size();
	}
}
