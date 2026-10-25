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
		SaveGame atlas = sample(2, 3723);
		atlas.savePoint = 17; // PAND01A SaveGameZone (Creator 슬롯 6)
		atlas.write(MemoryCardFlow.file(card, 1));
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
		// Save_SlotText 0x22E810: 이름 = 메시지 600 + 세이브 포인트 번호, 셋째 줄 = "%s    %d:%02d:%02d" (난이도 + 시간) — 영상 5182 "아틀라스의 시험" · "전사(보통) 3:10:43"
		assertEquals("비어있음", v.get("PS2_MemoryCard_Name1"));
		assertEquals("아틀라스의 시험", v.get("PS2_MemoryCard_Name2"));
		assertEquals("영웅(어려움)    1:02:03", v.get("PS2_MemoryCard_PlayTime2"));
		assertTrue(String.valueOf(v.get("PS2_MemoryCard_Date2")).matches("\\d{4}/\\d{1,2}/[ \\d]\\d [ \\d]\\d:\\d\\d:\\d\\d [ap]m"),
				"날짜 %d/%d/%2d %2d:%02d:%02d am|pm: " + v.get("PS2_MemoryCard_Date2"));
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
		SaveGame old = sample(1, 30);
		old.savePoint = 17;
		old.write(MemoryCardFlow.file(card, 2)); // 파일 3 = 예전 저장 (PAND01A)
		List<int[]> thumbs = new ArrayList<>();
		MemoryCardFlow f = MemoryCardFlow.save(v, p.messages(), card, () -> {
			SaveGame g = sample(1, 61);
			g.savePoint = 10;
			return g;
		}).thumbnails(thumbs::add);
		p.addCommandListener(f::command);
		f.start();
		idle(p, 30);
		press(p, UiPad.CIRCLE);
		assertEquals(22, f.state(), "저장 파일 선택");
		// 슬롯 그림 (0x1B81C0): 파일마다 세이브 포인트 번호 (없는 파일 0 → 기본 그림)
		assertEquals("[0, 0, 17, 0]", java.util.Arrays.toString(thumbs.getLast()));
		assertEquals("아틀라스의 시험", v.get("PS2_MemoryCard_Name3"));
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
		assertEquals(10, SaveGame.read(MemoryCardFlow.file(card, 0)).savePoint, "세이브 포인트 번호도 파일에 (슬롯 디렉터리 u16 +0xC)");
	}

	/** Save_SlotText 0x22E810 의 이름 갈래와 0x22E4A8 의 언어 5 날짜 형식 (43 §34). */
	@Test
	void slotNameAndDate() throws Exception {
		MessageTable m = MessageTable.fromWad(WadFile.load(FlpPlayerTest.rom().resolve("R_PERMK.WAD"))).orElseThrow();
		assertEquals("* Not A Save Point *", MemoryCardFlow.slotName(m, 0));
		assertEquals("오라클의 사원", MemoryCardFlow.slotName(m, 10)); // ATHN06A
		assertEquals("아틀라스의 시험", MemoryCardFlow.slotName(m, 17)); // PAND01A
		assertEquals("Save Point 99", MemoryCardFlow.slotName(m, 99)); // 메시지 699 없음
		assertEquals("2023/1/ 9  6:42:53 am", MemoryCardFlow.slotDate(java.time.LocalDateTime.of(2023, 1, 9, 6, 42, 53)));
		assertEquals("2023/12/25 12:00:00 pm", MemoryCardFlow.slotDate(java.time.LocalDateTime.of(2023, 12, 25, 12, 0, 0)));
		assertEquals("2023/12/25 12:05:09 am", MemoryCardFlow.slotDate(java.time.LocalDateTime.of(2023, 12, 25, 0, 5, 9)));
		assertEquals("2023/12/25 11:59:59 pm", MemoryCardFlow.slotDate(java.time.LocalDateTime.of(2023, 12, 25, 23, 59, 59)));
	}
}
