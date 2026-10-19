package devple.gow.engine.ui;

import devple.gow.tools.ui.FlpFile;
import devple.gow.tools.ui.MessageTable;
import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 원작 메인 메뉴로 새 게임 선택 → 난이도 선택 → PS2_Done/Menu_Choice (ShellScene 이 받는 신호). */
class ShellNewGameTest {

	@Test
	void newGameSelectsDifficultyAndSignalsDone() throws Exception {
		Path dir = FlpPlayerTest.rom();
		Assumptions.assumeTrue(Files.exists(dir.resolve("R_SHELLK.WAD")), "gow-rom 없음");
		WadFile w = WadFile.load(dir.resolve("R_SHELLK.WAD"));
		WadTag t = w.tags().stream().filter(x -> x.name().equals("FLP_ShellK") && x.hasData()).findFirst().orElseThrow();
		UiVariables v = new UiVariables();
		List<String> log = new ArrayList<>();
		v.addListener((n, val) -> log.add(n + "=" + val));
		FlpPlayer p = new FlpPlayer(new FlpFile(t.data()), v,
				MessageTable.fromWad(WadFile.load(dir.resolve("R_PERMK.WAD"))).orElseThrow());
		boolean done = false;
		// "새 게임" 에서 ○ 를 반복해서 누르며 하위 메뉴(난이도 등)를 기본값으로 통과
		for (int round = 0; round < 12 && !done; round++) {
			for (int i = 0; i < 40; i++) {
				p.pad(0);
				p.tick();
			}
			p.pad(UiPad.CIRCLE);
			p.tick();
			done = log.contains("PS2_Done=1");
		}
		assertTrue(done, "PS2_Done 미발생: " + log);
		assertTrue(log.stream().anyMatch(s -> s.startsWith("PS2_Menu_Choice=1")), log.toString());
		System.out.println("셸 → 새 게임 로그: " + log.stream().filter(s -> !s.startsWith("PS2_Sound")).toList()
				+ " 난이도=" + v.get("PS2_Difficulty_Level"));
	}
}
