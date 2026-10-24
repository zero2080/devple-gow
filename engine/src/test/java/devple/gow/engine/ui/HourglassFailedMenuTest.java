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

import static org.junit.jupiter.api.Assertions.*;

/**
 * FLP_HUDK 의 모래시계 (HourGlassMeter: PS2_Timer_Event 0 → "Off", 양수 → GotoFrame2(2 + PS2_Timer_Value), 프레임 92 부터 붉은 깜박임) ·
 * 실패 메뉴 (FailedMenu: PS2_FailedMenu_Event 1 → ○ = Menu_Choice 0 + Done 1) · 메시지 타자 효과 (원작 초당 50 자, 첫 ○ 는 끝 표시만) — 43 문서 §23.
 */
class HourglassFailedMenuTest {

	private static FlpPlayer hud(UiVariables v, List<String> log) throws Exception {
		Path dir = FlpPlayerTest.rom();
		Assumptions.assumeTrue(Files.exists(dir.resolve("R_PERMK.WAD")), "gow-rom 없음");
		WadFile w = WadFile.load(dir.resolve("R_PERMK.WAD"));
		WadTag t = w.tags().stream().filter(x -> x.name().equals("FLP_HUDK") && x.hasData()).findFirst().orElseThrow();
		v.addListener((n, val) -> log.add(n + "=" + val));
		FlpPlayer p = new FlpPlayer(new FlpFile(t.data()), v, MessageTable.fromWad(w).orElseThrow());
		for (int i = 0; i < 30; i++) {
			p.tick();
		}
		return p;
	}

	private static void ticks(FlpPlayer p, int n) {
		for (int i = 0; i < n; i++) {
			p.pad(0);
			p.tick();
		}
	}

	/** GameScene.timerHud 가 보내는 값 그대로: 경과 4 % (67 초 남음) → GotoFrame2(2 + 4) (0 기반 5), 91 % → 0 기반 92 = 붉은 clip#3, Event 0 → Off (0). */
	@Test
	void hourglassFrames() throws Exception {
		UiVariables v = new UiVariables();
		FlpPlayer p = hud(v, new ArrayList<>());
		assertEquals(0, p.frameOf("/HourGlassMeter"), "처음엔 Off (프레임 0)");
		v.set("PS2_Timer_Value", 4).set("PS2_Timer_Seconds", 67);
		v.event("PS2_Timer_Event", 1);
		ticks(p, 2);
		assertEquals(5, p.frameOf("/HourGlassMeter"), "GotoFrame2(2 + 4) → 0 기반 5");
		assertEquals("-1", v.get("PS2_Timer_Event"), "이벤트 소비");
		v.set("PS2_Timer_Value", 90).set("PS2_Timer_Seconds", 7);
		v.event("PS2_Timer_Event", 1);
		ticks(p, 2);
		assertEquals(91, p.frameOf("/HourGlassMeter"), "90 % → 0 기반 91 (붉은 깜박임 전, 영상 9760.25 흰 7)");
		v.set("PS2_Timer_Value", 91).set("PS2_Timer_Seconds", 6);
		v.event("PS2_Timer_Event", 1);
		ticks(p, 2);
		assertEquals(92, p.frameOf("/HourGlassMeter"), "91 % → 0 기반 92 = 붉은 clip#3 이 놓이는 프레임 (영상 9760.5 붉은 6)");
		v.set("PS2_Timer_Value", 0).set("PS2_Timer_Seconds", 0);
		v.event("PS2_Timer_Event", 0);
		ticks(p, 2);
		assertEquals(0, p.frameOf("/HourGlassMeter"), "Event 0 → Off");
	}

	/** 실패 화면 0x21D808: FailedMenu_Event 1 → "실패" + 선택, ○ = 다시 하기 (Menu_Choice 0) → CloseFailedMenu → PS2_Done 1. */
	@Test
	void failedMenuRestart() throws Exception {
		UiVariables v = new UiVariables();
		List<String> log = new ArrayList<>();
		FlpPlayer p = hud(v, log);
		v.set("PS2_Menu_Choice", 0);
		v.event("PS2_FailedMenu_Event", 1);
		ticks(p, 150);
		assertTrue(p.frameOf("/FailedMenu") > 1, "FailedMenu On: " + p.frameOf("/FailedMenu"));
		p.pad(UiPad.CIRCLE);
		p.tick();
		ticks(p, 60);
		assertTrue(log.contains("PS2_Sound2=SND_IG_YOUAREDEAD"), log.toString());
		assertTrue(log.contains("PS2_Sound1=SND_MM_RESTART_YES"), log.toString());
		assertTrue(log.contains("PS2_Done=1"), log.toString());
		assertEquals(0, (int) v.number("PS2_Menu_Choice"), "다시 하기 = 0");
	}

	/** 타자 효과 (0x1482E0 / 0x148360 / 0x1486F8): 50 자/초, 앞 줄부터, 태그 [XButton] 은 한 글자, 첫 확정은 끝 표시만 (false), 다음부터 true. */
	@Test
	void typewriter() {
		UiVariables v = new UiVariables();
		v.set("PS2_MessageTemplate_Line1", "문을 통해 사막으로 가세요, 크레토스.");
		v.set("PS2_MessageTemplate_Line2", "[XButton]을 누르십시오.");
		v.typeLines("PS2_MessageTemplate_Line", 1, 2);
		assertEquals("", v.typed("PS2_MessageTemplate_Line1", v.get("PS2_MessageTemplate_Line1")));
		v.typeTick(0.2f); // 10 자
		assertEquals("문을 통해 사막으로", v.typed("PS2_MessageTemplate_Line1", v.get("PS2_MessageTemplate_Line1")), "영상 9885 \"문을 통해 사막으로\"");
		assertEquals("", v.typed("_level0/:PS2_MessageTemplate_Line2", v.get("PS2_MessageTemplate_Line2")));
		v.typeTick(0.38f); // 29 자: 첫 줄 21 자 + 둘째 줄 8 단위
		assertEquals("[XButton]을 누르십시오", v.typed("PS2_MessageTemplate_Line2", v.get("PS2_MessageTemplate_Line2")));
		assertEquals("다른 변수", v.typed("PS2_MessageTemplate_Line3", "다른 변수"), "범위 밖은 그대로");
		assertTrue(v.typing());
		assertFalse(v.typeComplete(), "첫 ○: 끝 표시만");
		assertTrue(v.typeComplete(), "둘째 ○: 넘김");
		assertEquals("[XButton]을 누르십시오.", v.typed("PS2_MessageTemplate_Line2", v.get("PS2_MessageTemplate_Line2")));
		v.typeLines(null, 0, 0);
		assertTrue(v.typeComplete(), "타자 없음 = 바로 넘김");
	}
}
