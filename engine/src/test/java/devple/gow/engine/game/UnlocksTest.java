package devple.gow.engine.game;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 11 문서 §2.3: CompleteGame(kind) → 해금 비트, 저장·누적. */
class UnlocksTest {

	@TempDir
	Path card;

	@Test
	void completeGameBits() throws Exception {
		assertEquals(0, Unlocks.load(card));
		assertEquals(0x1, Unlocks.complete(card, 0, 1), "보통 클리어 → bit0");
		assertEquals(0x3, Unlocks.complete(card, 0, 2), "어려움 → bit1 누적");
		assertEquals(0xB, Unlocks.complete(card, 1, 2), "신들의 도전 → bit3");
		assertEquals(0x1F, Unlocks.complete(card, 2, 0) | Unlocks.complete(card, 0, 3));
		assertEquals(0x1F, Unlocks.load(card), "파일에서 다시 읽음");
		assertEquals(0x1, Unlocks.bitFor(0, 0), "쉬움도 bit0");
	}
}
