package devple.gow.engine.game;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 질문 메시지 → 메뉴 템플릿 (42 문서 §2): PAND01C 1019 "손잡이를 가져가시겠습니까? ---- *예 / 아니오". */
class QuestionMenuTest {

	@Test
	void splitsQuestionAndChoices() {
		QuestionMenu q = QuestionMenu.parse("손잡이를 가져가시겠습니까?\n----\n*예\n아니오", null);
		assertEquals(List.of("손잡이를 가져가시겠습니까?"), q.lines());
		assertEquals(List.of("예", "아니오"), q.choices());
		assertEquals(1, q.defaultChoice());
		assertEquals(12, q.frame(), "1 줄 2 지 = 원작 GameMgr +0x208 = 12");
	}

	@Test
	void defaultMarkAndFallbackChoices() {
		assertEquals(2, QuestionMenu.parse("a\nb\n----\n하나\n*둘\n셋", null).defaultChoice());
		assertEquals(23, QuestionMenu.parse("a\nb\n----\n하나\n*둘\n셋", null).frame());
		QuestionMenu q = QuestionMenu.parse("선택지 없는 질문", null);
		assertEquals(List.of("예", "아니오"), q.choices());
	}
}
