package devple.gow.engine.game;

import devple.gow.engine.ui.FlpPlayer;
import devple.gow.engine.ui.UiVariables;
import devple.gow.tools.ui.FlpFile;
import devple.gow.tools.ui.MessageTable;
import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 43 문서 §57: 엔딩 흐름 — CompleteGame (0x1B1128) → RAM 해금 비트 + 끝 종류 0x29D94C → 프레임 끝 0x21D8B0(종류) 클리어 저장 질문
 * (HUD SaveGame_Event 2–5, FLP SaveScreen 프레임 = 종류) → "예" = 해금만 카드에 적는 쓰기 (0x230A48(0), 단계 0x19).
 */
class EndGameSaveTest {

	private static final Path ROM = Path.of(System.getProperty("gow.rom", "../gow-rom"));

	@TempDir
	Path card;

	@AfterEach
	void powerOff() {
		Unlocks.resetMemory();
	}

	@Test
	void endGameKindTable() {
		// 0x1B1160 – 0x1B11C4: kind 0 → 난이도 0·1·2 = 2, 3 = 3; kind 1 → 4; kind 2 → 5
		assertEquals(2, Unlocks.endGameKind(0, 0));
		assertEquals(2, Unlocks.endGameKind(0, 1));
		assertEquals(2, Unlocks.endGameKind(0, 2));
		assertEquals(3, Unlocks.endGameKind(0, 3));
		assertEquals(4, Unlocks.endGameKind(1, 1));
		assertEquals(5, Unlocks.endGameKind(2, 1));
		assertEquals(0, Unlocks.endGameKind(7, 1));
	}

	@Test
	void grantStaysInRamUntilACardWrite() throws Exception {
		assertEquals(0x1, Unlocks.grant(card, 0, 1), "보통 클리어 → bit0 (RAM)");
		assertFalse(Files.exists(Unlocks.file(card)), "카드에는 아직 안 씀 (아니오 → 전원을 끌 때까지만)");
		Unlocks.persist(card);
		assertEquals(0x1, Unlocks.load(card), "쓰기 단계 0x19: 레코드 |= g_unlockMask");
		Unlocks.grant(card, 2, 1);
		Unlocks.persist(card);
		assertEquals(0x11, Unlocks.load(card), "누적");
	}

	private static SaveGame sample() {
		SaveGame g = new SaveGame();
		g.level = "OLYMP02";
		g.selectedMagic = "";
		g.difficulty = 1;
		g.savePoint = 41;
		return g;
	}

	/** 영상 5화 10592 "예" → 10595 Ω 만 → 10596 "저장하기 완료": GOW 디렉터리가 있는 카드는 카드 · 파일 선택 없이 곧 단계 0x19. */
	@Test
	void unlockOnlyWriteGoesStraightToDone() throws Exception {
		SaveGame old = sample();
		Path f = MemoryCardFlow.file(card, 2);
		old.write(f);
		byte[] before = Files.readAllBytes(f);
		Unlocks.grant(card, 0, 1);
		UiVariables v = new UiVariables();
		MemoryCardFlow flow = MemoryCardFlow.save(v, null, card, () -> {
			throw new AssertionError("클리어 저장은 게임 데이터를 쓰지 않는다");
		}).unlockOnly();
		flow.start();
		assertEquals(MemoryCardFlow.SAVING, flow.state());
		assertFalse(v.has("PS2_MemoryCard_Event"), "화면 바꿈 없음 (Ω 만)");
		flow.update(MemoryCardFlow.BUSY_SEC + 0.1f);
		assertEquals(MemoryCardFlow.SAVE_DONE, flow.state());
		assertEquals(18, (int) v.number("PS2_MemoryCard_Event"), "저장하기 완료");
		assertArrayEquals(before, Files.readAllBytes(f), "슬롯 파일 그대로");
		assertEquals(0x1, Unlocks.load(card));
		flow.select(1);
		assertEquals(MemoryCardFlow.Result.SAVED, flow.result());
	}

	/** GOW 디렉터리가 없으면 새로 만들기 질문 (단계 0xF, 16): 아니오 → 흐름 −1 (클리어 질문으로), 예 → 만들기 (17) → 해금 쓰기. */
	@Test
	void unlockOnlyWithoutDirectoryAsksToCreate() throws Exception {
		Unlocks.grant(card, 0, 3);
		MemoryCardFlow no = MemoryCardFlow.save(new UiVariables(), null, card, SaveGame::new).unlockOnly();
		no.start();
		assertEquals(MemoryCardFlow.CREATE_ASK, no.state());
		no.select(0);
		assertEquals(MemoryCardFlow.Result.CANCELLED, no.result());
		assertFalse(Files.exists(Unlocks.file(card)));
		MemoryCardFlow yes = MemoryCardFlow.save(new UiVariables(), null, card, SaveGame::new).unlockOnly();
		yes.start();
		yes.select(1);
		assertEquals(MemoryCardFlow.SAVING, yes.state());
		yes.update(MemoryCardFlow.BUSY_SEC + 0.1f);
		assertEquals(MemoryCardFlow.SAVE_DONE, yes.state());
		assertEquals(0x4, Unlocks.load(card), "신 클리어 → bit2");
	}

	@Test
	void savePointWriteAlsoStoresUnlocks() throws Exception {
		Unlocks.grant(card, 1, 1);
		MemoryCardFlow flow = MemoryCardFlow.save(new UiVariables(), null, card, EndGameSaveTest::sample);
		flow.start();
		flow.select(1);
		flow.select(2);
		assertEquals(MemoryCardFlow.CREATE_ASK, flow.state());
		flow.select(1);
		flow.update(MemoryCardFlow.BUSY_SEC + 0.1f);
		assertEquals(MemoryCardFlow.SAVE_DONE, flow.state());
		assertEquals(0x8, Unlocks.load(card), "단계 0x18 → 0x19");
	}

	/** 옵션 블록 [10] HUD EndGame (0x22DB64) 은 세이브 · 체크포인트 스냅숏에 남는다. */
	@Test
	void endGameOptionRoundTrips() throws Exception {
		SaveGame g = sample();
		g.endGame = 3;
		Path f = card.resolve("x.save");
		g.write(f);
		assertEquals(3, SaveGame.read(f).endGame);
	}

	/** FLP_HUDK SaveGameMenu "On": SaveScreen GotoFrame2(SaveGame_Event), SaveQuestion 은 종류 ≥ 2 면 프레임 1 (클리어 문구 + "게임 진행 상황을…"). */
	@Test
	void hudShowsTheCompletionScreen() throws Exception {
		Path perm = ROM.resolve("R_PERMK.WAD");
		Assumptions.assumeTrue(Files.exists(perm), "gow-rom 없음");
		WadFile w = WadFile.load(perm);
		WadTag t = w.tags().stream().filter(x -> x.name().equals("FLP_HUDK") && x.hasData()).findFirst().orElseThrow();
		UiVariables v = new UiVariables();
		v.set("PS2_EnableButtons", 1);
		FlpPlayer p = new FlpPlayer(new FlpFile(t.data()), v, MessageTable.fromWad(w).orElseThrow());
		for (int i = 0; i < 30; i++) {
			p.tick();
		}
		v.event("PS2_SaveGame_Event", 2);
		for (int i = 0; i < 30; i++) {
			p.tick();
		}
		assertEquals(1, p.frameOf("SaveGameMenu/SaveScreen"), "GotoFrame2 2 = 둘째 프레임 (0 부터 1, 세이브 포인트 1 은 0)");
		assertEquals(1, p.frameOf("SaveGameMenu/SaveQuestion"));
	}
}
