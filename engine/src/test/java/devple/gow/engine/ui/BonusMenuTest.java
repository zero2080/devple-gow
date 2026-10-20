package devple.gow.engine.ui;

import devple.gow.tools.content.Content;
import devple.gow.engine.game.BonusMenu;
import devple.gow.engine.game.ShellBinding;
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
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 39 문서: 원작 FLP_ShellK 귀중품 화면 (TreasureOn..) 을 BonusMenu 로 진행 — 이름 채우기, 스크롤, 잠금, 영상/레벨 선택. */
class BonusMenuTest {

	/** 재생 요청을 기록하고, 테스트가 끝내라고 할 때 done 을 부르는 가짜 호스트. */
	private static final class FakeHost implements BonusMenu.Host {
		final List<String> played = new ArrayList<>();
		final List<String> levels = new ArrayList<>();
		Consumer<Boolean> pending;

		@Override
		public void playMovie(String name, Consumer<Boolean> done) {
			played.add(name);
			pending = done;
		}

		@Override
		public void startLevel(String wad) {
			levels.add(wad);
		}

		void finish(boolean skipped) {
			Consumer<Boolean> d = pending;
			pending = null;
			d.accept(skipped);
		}
	}

	private UiVariables v;
	private FlpPlayer p;
	private BonusMenu bonus;
	private FakeHost host;

	private void open(int unlockBits) throws Exception {
		Path dir = FlpPlayerTest.rom();
		Assumptions.assumeTrue(Files.exists(dir.resolve("R_SHELLK.WAD")), "gow-rom 없음");
		WadFile w = WadFile.load(dir.resolve("R_SHELLK.WAD"));
		WadTag t = w.tags().stream().filter(x -> x.name().equals("FLP_ShellK") && x.hasData()).findFirst().orElseThrow();
		v = new UiVariables();
		ShellBinding.init(v, unlockBits);
		p = new FlpPlayer(new FlpFile(t.data()), v, MessageTable.fromWad(WadFile.load(dir.resolve("R_PERMK.WAD"))).orElseThrow());
		host = new FakeHost();
		bonus = new BonusMenu(v, p.messages(), unlockBits, host);
		p.addCommandListener(bonus::command);
		// 메인 메뉴: 새 게임 → 불러오기 → 옵션 → 귀중품 (↓ 3번) → ○ = TreasureOn
		press(UiPad.DOWN);
		press(UiPad.DOWN);
		press(UiPad.DOWN);
		press(UiPad.CIRCLE);
		idle(60);
	}

	private void idle(int n) {
		for (int i = 0; i < n; i++) {
			p.pad(0);
			p.tick();
			bonus.update(1 / 60f);
		}
	}

	private void press(int mask) {
		idle(45);
		p.pad(mask);
		p.tick();
		bonus.update(1 / 60f);
		idle(5);
	}

	private void scrollTo(int index) {
		for (int k = 0; k < 40 && bonus.cursor() < index; k++) {
			press(UiPad.DOWN);
		}
		assertEquals(index, bonus.cursor(), "스크롤 실패: busy=" + bonus.busy() + " EnableButtons=" + v.get("PS2_EnableButtons")
				+ " EnableButton=" + p.getVariable("EnableButton"));
	}

	@Test
	void namesFillScrollAndPlayMovie() throws Exception {
		open(0);
		assertEquals(17, bonus.entries().size(), "메시지 900..916");
		// 커서 0 = 4번째 줄, 위 3줄은 비어 있음. FLP 가 _level0/:BonusName 으로 복사
		assertEquals("", v.get("PS2_BonusName1"));
		assertEquals("GOW의 제작 과정", v.get("PS2_BonusName4"));
		assertEquals("GOW 예고편", v.get("PS2_BonusName5"));
		assertEquals("GOW의 제작 과정", p.getVariable("_level0/:BonusName4"));
		assertEquals("0", v.get("PS2_BonusLocked4"), "S = 항상 열림");
		assertEquals("1", v.get("PS2_BonusLocked6"), "만든이들 = 클리어 필요");
		assertEquals("1", v.get("PS2_BonusLocked1"), "범위 밖 = 잠김");
		assertEquals("GOW가 하나의 작은 아이디어에서", v.get("PS2_BonusDesc1"));

		press(UiPad.DOWN);
		assertEquals(1, bonus.cursor());
		assertEquals("GOW 예고편", v.get("PS2_BonusName4"));
		assertEquals("GOW의 제작 과정", v.get("PS2_BonusName3"));
		press(UiPad.UP);
		assertEquals(0, bonus.cursor());
		press(UiPad.UP);
		assertEquals(0, bonus.cursor(), "맨 위에서 멈춤");

		press(UiPad.CIRCLE);
		assertEquals(List.of("Bonus03"), host.played, "제작 과정 = M03");
		assertEquals("0", v.get("PS2_EnableButtons"), "재생 중 입력 잠금");
		press(UiPad.DOWN);
		assertEquals(0, bonus.cursor(), "재생 중 스크롤 무시");
		host.finish(false);
		idle(70);
		assertEquals("1", v.get("PS2_EnableButtons"), "1초 뒤 입력 복구");

		press(UiPad.DOWN);
		press(UiPad.CIRCLE);
		assertEquals(List.of("Bonus03", "Bonus21"), host.played, "예고편 = M21");
		host.finish(true);
		idle(70);

		// 잠긴 항목 (만든이들 EM20) 은 ○ 무반응, 설명은 잠긴 설명
		press(UiPad.DOWN);
		assertEquals("만든이들", v.get("PS2_BonusName4"));
		assertEquals("1", v.get("PS2_BonusLocked4"));
		assertEquals("잠금을 풀려면 게임을 클리어 하세요.", v.get("PS2_BonusDesc2"));
		press(UiPad.CIRCLE);
		assertEquals(2, host.played.size());

		// △ = TreasureOff → 메인 메뉴 (PS2_BonusScroll 4 로 다시 초기화)
		press(UiPad.TRIANGLE);
		idle(30);
		assertFalse(bonus.busy());
	}

	@Test
	void unlockAllEntries() throws Exception {
		open(BonusMenu.ALL_UNLOCKED);
		scrollTo(2);
		assertEquals("0", v.get("PS2_BonusLocked4"));
		press(UiPad.CIRCLE);
		assertEquals("Bonus20", host.played.getLast(), "만든이들 = M20 (크레딧)");
		host.finish(false);
		idle(70);

		scrollTo(10);
		assertEquals("신들의 시험", v.get("PS2_BonusName4"));
		press(UiPad.CIRCLE);
		assertEquals(List.of("ARENA10"), host.levels);
		assertEquals("0", v.get("PS2_EnableButtons"), "레벨 시작 = 셸 입력 잠금");
		// 셸로 돌아온 것으로 간주 (원작은 셸을 새로 띄우므로 EnableButtons 초기값 1 + TreasureOn 의 4)
		v.set("PS2_EnableButtons", 1);
		bonus.command("PS2_BonusScroll", "4");
		idle(5);

		scrollTo(11);
		assertEquals("게임 속 동영상", v.get("PS2_BonusName4"));
		press(UiPad.CIRCLE);
		int before = host.played.size();
		for (int i = 0; i < 3; i++) {
			host.finish(false);
		}
		assertEquals(List.of("Semo01", "Semo02", "Semo03", "Semo04"), host.played.subList(before - 1, before + 3));
		host.finish(true); // 건너뛰면 연속 재생 중단
		assertEquals(before + 3, host.played.size());
		idle(70);

		// 각 항목의 영상 이름 = 메시지 동작 문자열 (M..)
		List<String> movies = new ArrayList<>();
		for (BonusMenu.Entry e : bonus.entries()) {
			if (e.action().startsWith("M")) {
				movies.add("Bonus" + e.action().substring(1));
			}
		}
		assertEquals(List.of("Bonus03", "Bonus21", "Bonus20", "Bonus06", "Bonus12", "Bonus14", "Bonus10", "Bonus16",
				"Bonus17", "Bonus18", "Bonus24", "Bonus19"), movies);
		Path rom = FlpPlayerTest.rom();
		for (String m : movies) {
			assertTrue(Files.exists(Content.file(rom, m.toUpperCase() + ".PSS")) || !Files.exists(Content.file(rom, "BONUS01.PSS")), m);
		}
	}

	@Test
	void lockRules() {
		assertFalse(BonusMenu.locked('S', 0));
		assertTrue(BonusMenu.locked('E', 0));
		assertFalse(BonusMenu.locked('E', 0x1));
		assertTrue(BonusMenu.locked('H', 0x1));
		assertFalse(BonusMenu.locked('H', 0x2));
		assertTrue(BonusMenu.locked('G', 0x3));
		assertFalse(BonusMenu.locked('G', 0x4));
		assertTrue(BonusMenu.locked('A', 0x7));
		assertFalse(BonusMenu.locked('A', 0x8));
		assertFalse(BonusMenu.locked('F', 0x10));
		assertTrue(BonusMenu.locked('L', 0x1F));
	}
}
