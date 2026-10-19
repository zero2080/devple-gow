package devple.gow.engine.ui;

import devple.gow.engine.game.MemoryCardFlow;
import devple.gow.engine.game.SaveGame;
import devple.gow.engine.game.ShellBinding;
import devple.gow.tools.ui.FlpFile;
import devple.gow.tools.ui.MessageTable;
import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 22 문서 §7: 원작 FLP 의 메모리카드 화면(SaveLoadMenu) 을 MemoryCardFlow 로 끝까지 진행 — 셸 불러오기 / HUD 저장. */
class MemoryCardFlowTest {

	@TempDir
	Path card;

	private static FlpPlayer flp(String wad, String name, UiVariables v) throws Exception {
		Path dir = FlpPlayerTest.rom();
		WadFile w = WadFile.load(dir.resolve(wad));
		WadTag t = w.tags().stream().filter(x -> x.name().equals(name) && x.hasData()).findFirst().orElseThrow();
		return new FlpPlayer(new FlpFile(t.data()), v, MessageTable.fromWad(WadFile.load(dir.resolve("R_PERMK.WAD"))).orElseThrow());
	}

	private static void idle(FlpPlayer p, int n) {
		for (int i = 0; i < n; i++) {
			p.pad(0);
			p.tick();
		}
	}

	private static void press(FlpPlayer p, int mask) {
		idle(p, 45);
		p.pad(mask);
		p.tick();
		idle(p, 5);
	}

	private static SaveGame sample(int difficulty, float playTime) {
		SaveGame g = new SaveGame();
		g.level = "ATHN01B";
		g.selectedMagic = "";
		g.difficulty = difficulty;
		g.playTime = playTime;
		g.hp = 50;
		return g;
	}

	@Test
	void shellLoadFlow() throws Exception {
		sample(2, 3723).write(MemoryCardFlow.file(card, 1));
		UiVariables v = new UiVariables();
		ShellBinding.init(v, 0);
		List<String> log = new ArrayList<>();
		v.addListener((n, val) -> log.add(n + "=" + val));
		FlpPlayer p = flp("R_SHELLK.WAD", "FLP_ShellK", v);
		MemoryCardFlow f = MemoryCardFlow.load(v, p.messages(), card);
		p.addCommandListener(f::command);
		press(p, UiPad.DOWN);
		press(p, UiPad.CIRCLE);
		assertTrue(log.contains("PS2_Menu_Choice=2") && log.contains("PS2_Done=1"), log.toString());
		f.start();
		idle(p, 30);
		assertEquals(21, f.state());
		press(p, UiPad.CIRCLE); // 카드 슬롯 1
		assertEquals(23, f.state(), "파일 선택");
		assertEquals("비어있음", v.get("PS2_MemoryCard_Name1"));
		assertEquals("영웅(어려움)", v.get("PS2_MemoryCard_Name2"));
		assertEquals("1:02:03", v.get("PS2_MemoryCard_PlayTime2"));
		press(p, UiPad.CIRCLE); // 빈 파일 1 → 무시
		assertEquals(23, f.state(), "빈 파일은 고를 수 없음");
		press(p, UiPad.RIGHT); // 파일 2
		press(p, UiPad.CIRCLE);
		assertEquals(10, f.state(), "불러올까?");
		press(p, UiPad.CIRCLE); // 기본 = 아니오
		assertEquals(23, f.state(), "아니오 → 파일 선택");
		press(p, UiPad.RIGHT); // 화면이 다시 열리면 커서는 파일 1 로 돌아간다
		press(p, UiPad.CIRCLE);
		assertEquals(10, f.state());
		press(p, UiPad.UP);
		press(p, UiPad.CIRCLE);
		assertEquals(11, f.state(), "예 → 불러오는 중");
		f.update(1.1f);
		assertEquals(13, f.state());
		f.update(1.1f);
		assertEquals(MemoryCardFlow.Result.LOADED, f.result());
		assertEquals(3723, f.loaded().playTime, 0.01);
		assertEquals(1, f.fileIndex());
		idle(p, 60);
		assertEquals(7, p.frameOf(""), "메뉴 닫힘 → MainWait");
	}

	@Test
	void shellCancelAndNoCard() throws Exception {
		UiVariables v = new UiVariables();
		ShellBinding.init(v, 0);
		FlpPlayer p = flp("R_SHELLK.WAD", "FLP_ShellK", v);
		MemoryCardFlow f = MemoryCardFlow.load(v, p.messages(), card);
		p.addCommandListener(f::command);
		press(p, UiPad.DOWN);
		press(p, UiPad.CIRCLE);
		f.start();
		idle(p, 30);
		press(p, UiPad.CIRCLE);
		assertEquals(14, f.state(), "빈 카드 → GOW 파일 없음");
		press(p, UiPad.CIRCLE);
		assertEquals(21, f.state());
		press(p, UiPad.RIGHT); // 슬롯 2
		press(p, UiPad.CIRCLE);
		assertEquals(2, f.state(), "카드 2 없음");
		press(p, UiPad.CIRCLE);
		assertEquals(21, f.state());
		press(p, UiPad.TRIANGLE);
		assertEquals(MemoryCardFlow.Result.CANCELLED, f.result());
	}

	@Test
	void hudSaveFlow() throws Exception {
		UiVariables v = new UiVariables();
		v.set("PS2_EnableButtons", 1);
		List<String> log = new ArrayList<>();
		v.addListener((n, val) -> log.add(n + "=" + val));
		FlpPlayer p = flp("R_PERMK.WAD", "FLP_HUDK", v);
		idle(p, 30);
		v.event("PS2_SaveGame_Event", 1);
		idle(p, 30);
		press(p, UiPad.CIRCLE);
		assertTrue(log.contains("PS2_Done=1"), "저장 질문 응답: " + log);
		String choice = v.get("PS2_Menu_Choice").replace(".0", "");
		if (!"1".equals(choice)) {
			// 기본 커서가 아니오면 왼쪽으로 옮겨 다시
			v.event("PS2_SaveGame_Event", 1);
			idle(p, 30);
			press(p, UiPad.LEFT);
			press(p, UiPad.CIRCLE);
			choice = v.get("PS2_Menu_Choice").replace(".0", "");
		}
		assertEquals("1", choice, "저장하시겠습니까? 예: " + log);
		MemoryCardFlow f = MemoryCardFlow.save(v, p.messages(), card, () -> sample(1, 61));
		p.addCommandListener(f::command);
		f.start();
		idle(p, 30);
		press(p, UiPad.CIRCLE);
		assertEquals(22, f.state(), "저장 파일 선택");
		press(p, UiPad.CIRCLE);
		assertEquals(16, f.state(), "새 파일 생성?");
		press(p, UiPad.UP);
		press(p, UiPad.CIRCLE);
		assertEquals(17, f.state(), "저장 중");
		f.update(1.1f);
		assertEquals(18, f.state());
		assertTrue(Files.exists(MemoryCardFlow.file(card, 0)));
		press(p, UiPad.CIRCLE);
		assertEquals(MemoryCardFlow.Result.SAVED, f.result());
		assertEquals(61, SaveGame.read(MemoryCardFlow.file(card, 0)).playTime, 0.01);
	}
}
