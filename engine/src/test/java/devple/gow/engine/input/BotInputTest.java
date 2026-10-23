package devple.gow.engine.input;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 자동 플레이 봇의 메두사의 시선 규칙 (원작 조작 = 메시지 595 "L2 를 누르고, □ 를 눌러서 발사"). */
class BotInputTest {

	static final float DT = 1 / 60f;

	@Test
	void gazeSelectsWithLeftThenHoldsL2AndSquare() {
		BotInput b = new BotInput(0);
		Vector3f hero = new Vector3f(), enemy = new Vector3f(0, 0, -100);
		b.gaze = true;
		b.selectGaze = true;
		boolean left = false;
		for (int i = 0; i < 40; i++) {
			b.steer(hero, enemy, 0, DT);
			left |= b.pressed(PadButton.LEFT);
			assertFalse(b.held(PadButton.L2), "고르기 전에는 시전하지 않음");
		}
		assertTrue(left, "← 로 메두사 선택 (0x1D7E10)");
		b.selectGaze = false;
		for (float t = 0; t < 0.5f; t += DT) {
			b.steer(hero, enemy, 0, DT);
		}
		assertTrue(b.held(PadButton.L2), "L2 홀드 = 머리 들기 (81 → 82)");
		assertTrue(b.held(PadButton.SQUARE), "□ 홀드 = 광선 (0x1972E8)");
		// 석화되면 (gaze 꺼짐) 놓고 쉰 뒤 보통 묶음 (□ = 석상 파괴)
		b.gaze = false;
		b.steer(hero, enemy, 0, DT);
		assertFalse(b.held(PadButton.L2));
		boolean square = false;
		for (float t = 0; t < 1.5f; t += DT) {
			b.steer(hero, enemy, 0, DT);
			square |= b.pressed(PadButton.SQUARE);
			assertFalse(b.held(PadButton.L2));
		}
		assertTrue(square, "석상에 □");
	}

	@Test
	void gazeWalksCloserFirst() {
		BotInput b = new BotInput(0);
		b.gaze = true;
		b.steer(new Vector3f(), new Vector3f(0, 0, -600), 0, DT);
		assertFalse(b.held(PadButton.L2));
		assertTrue(b.leftY() > 0.9f, "대상 쪽으로 걷기: " + b.leftY());
	}
}
