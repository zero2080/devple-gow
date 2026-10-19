package devple.gow.engine.game;

import devple.gow.engine.script.ScriptListener.LevelOp;
import devple.gow.engine.script.ScriptListener.LevelRequest;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 레벨 슬롯 2개 모델 (36 문서 §2): LoadWad/LoadForWarp/Warp/LoadCheck 판정. */
class LevelLinkTest {

	private static final Set<String> WADS = Set.of("ATHN01A", "ATHN01B", "ATHN01F", "ATHN02A");

	private static LevelRequest req(LevelOp op, String wad) {
		return new LevelRequest(op, wad, null, null);
	}

	@Test
	void loadCheckSwitchesOnlyToLoadedSlot() {
		LevelLink l = new LevelLink("ATHN01A", null, WADS::contains);
		assertNull(l.handle(req(LevelOp.LOAD_CHECK, "ATHN01B")), "로드 전 LoadCheck 는 무시");
		assertFalse(l.loaded("ATHN01B"));
		assertNull(l.handle(req(LevelOp.LOAD_WAD, "ATHN01B")));
		assertTrue(l.loaded("athn01b"));
		LevelLink.Transition t = l.handle(req(LevelOp.LOAD_CHECK, "ATHN01B"));
		assertEquals(LevelLink.Kind.SEAMLESS, t.kind());
		assertEquals("ATHN01B", t.target());
		assertEquals("ATHN01A", t.from());
	}

	@Test
	void warpNeedsLoadForWarp() {
		LevelLink l = new LevelLink("ATHN01F", null, WADS::contains);
		l.handle(req(LevelOp.LOAD_WAD, "ATHN02A"));
		assertNull(l.handle(req(LevelOp.WARP, null)), "LoadWad 뒤 Warp 는 무시 (원작 0x29D914 = 0)");
		l.handle(req(LevelOp.LOAD_FOR_WARP, "ATHN02A"));
		LevelLink.Transition t = l.handle(req(LevelOp.WARP, null));
		assertEquals(LevelLink.Kind.WARP, t.kind());
		assertEquals("ATHN02A", t.target());
		assertNull(l.handle(req(LevelOp.WARP, null)), "Warp 는 한 번");
	}

	@Test
	void arrivalKeepsPreviousLevelInOtherSlot() {
		LevelLink l = new LevelLink("ATHN01B", "ATHN01A", WADS::contains);
		assertEquals("ATHN01A", l.other());
		assertEquals(LevelLink.Kind.SEAMLESS, l.handle(req(LevelOp.LOAD_CHECK, "ATHN01A")).kind());
	}

	@Test
	void missingOrSelfWadIsIgnored() {
		LevelLink l = new LevelLink("ATHN01A", null, WADS::contains);
		l.handle(req(LevelOp.LOAD_WAD, "ATHN0E"));
		assertNull(l.other(), "없는 WAD (ATHN03D 의 \"Athn0E\" 오타) 는 슬롯에 올리지 않는다");
		l.handle(req(LevelOp.LOAD_WAD, "ATHN01A"));
		assertNull(l.other(), "자기 자신 LoadWad 는 무시 (원작 0x1B0478)");
		assertNull(l.handle(req(LevelOp.LOAD_CHECK, "ATHN01A")));
	}
}
