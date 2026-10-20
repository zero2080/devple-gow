package devple.gow.engine.level;

import devple.gow.tools.level.CollisionSheet;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 가시 칸 컨텍스트 (43 문서 §11): SheetHdr 섹션 [1]/[2]/[10] + 0x5C — 카메라 위치를 포함하는 칸이 CXT_bv… 컨텍스트를 켠다 (0x16DC60),
 * 목록에 있는데 켜지지 않은 컨텍스트의 인스턴스는 그리지 않는다 (0x16D6F8 → 0x134980). 안개 GO 는 칸이 그 컨텍스트를 켤 때만 (0x20D0D8).
 */
class VisCellsTest {

	private static LevelData load(String level) throws Exception {
		Path wad = Path.of(System.getProperty("gow.rom", "../gow-rom"), level + ".WAD");
		Assumptions.assumeTrue(Files.exists(wad), "gow-rom 없음");
		return new LevelData(WadFile.load(wad));
	}

	@Test
	void pand01bbSandArenaShellHiddenFromConHallCam5() throws Exception {
		LevelData d = load("PAND01BB");
		CollisionSheet s = d.sheets.getFirst();
		assertEquals(java.util.List.of("CXT_bvbstatuefog", "CXT_bvsthdroom", "CXT_bvladder", "CXT_bvoutside", "CXT_bvhall"), s.visContexts);
		assertEquals(7, s.visCells.size());
		assertEquals("CXT_bvoutside", d.layout.contextOf.get("gosandarena"));
		assertEquals("CXT_bvhall", d.layout.contextOf.get("gohallsectionb"));
		// ConHallCam5 고정 위치 (PAND00D 에서 Warp 도착): 칸 1 → bvladder·bvbstatuefog·bvhall 켬, bvoutside·bvsthdroom 은 꺼짐
		Set<String> hidden = d.contextHidden(-2816f, -222.5f, -791f);
		assertTrue(hidden.contains("gosandarena"), "탑 외피 (bvoutside) 숨김: " + hidden);
		assertFalse(hidden.contains("gohallsectionb"), "복도 (bvhall) 보임");
		assertFalse(hidden.contains("gostart"), "CXT_default 는 목록에 없어 항상 보임");
		assertTrue(d.contextActive("gofog", -2816f, -222.5f, -791f), "안개 gofog (bvbstatuefog) 켜짐");
		// 바깥 (칸 6: bvoutside 만 켬) 에서는 외피가 보이고 복도가 숨는다
		Set<String> out = d.contextHidden(-1700f, 300f, -500f);
		assertFalse(out.contains("gosandarena"));
		assertTrue(out.contains("gohallsectionb"));
	}

	@Test
	void athn01aBoatCameraSeesBothContexts() throws Exception {
		LevelData d = load("ATHN01A");
		// Boat1Cam4 레일 끝 눈 (1519, 68.8, 2030): 칸 0 (bvstartship) · 칸 1 (dbvdeck) 안 → 숨김 없음 (43 §11.3)
		assertEquals(Set.of(), d.contextHidden(1519f, 68.8f, 2030f));
	}
}
