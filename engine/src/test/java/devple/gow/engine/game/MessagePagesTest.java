package devple.gow.engine.game;

import devple.gow.tools.ui.MessageTable;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 메시지 쪽 나눔 (원작 0x1B96D0: 줄 첫 두 글자 "--" = 쪽 구분, 쪽마다 1..3 줄) — 43 문서 §23. 예전 엔진은 "----" 를 빈 줄로 바꿔 다섯 줄 틀에 넣어
 * 오라클 342 의 둘째 쪽 "유일한 방법입니다." 가 잘렸다.
 */
class MessagePagesTest {

	@Test
	void oracle342() throws Exception {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(rom.resolve("R_PERMK.WAD")), "gow-rom 없음");
		MessageTable m = MessageTable.fromWad(WadFile.load(rom.resolve("R_PERMK.WAD"))).orElseThrow();
		List<List<String>> p = GameScene.messagePages(m.get(342));
		assertEquals(2, p.size());
		assertEquals(List.of("문을 통해 사막으로 가세요, 크레토스.", "판도라의 상자로 향하는 길은 거기 있습니다.", "그것만이 아레스를 이기고 아테네를 구할"), p.get(0),
				"PS2 영상 9887 첫 쪽 세 줄");
		assertEquals(List.of("유일한 방법입니다."), p.get(1));
		// 게임 안 메시지 (100–699) 는 모든 쪽이 1..3 줄 — 원작 틀 (3 줄) 에 맞는다
		for (int id = 100; id < 700; id++) {
			String t = m.get(id);
			if (t == null) {
				continue;
			}
			for (List<String> page : GameScene.messagePages(t)) {
				assertTrue(page.size() >= 1 && page.size() <= 3, id + ": " + page);
			}
		}
		assertEquals(4, GameScene.messagePages(m.get(130)).size(), "130 (사원 관리인) 네 쪽");
	}
}
