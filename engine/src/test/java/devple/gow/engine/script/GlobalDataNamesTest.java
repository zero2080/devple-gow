package devple.gow.engine.script;

import devple.gow.tools.anim.Mat4;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GlobalData 변수는 이름으로 전역이다 (원작 0x1AE848 → 0x1AEC88 등록, 0x1AEDE8 조회): 스크립트 번호는 레벨마다 다르다 (43 문서 §17).
 */
class GlobalDataNamesTest {

	private static final Path ROM = Path.of(System.getProperty("gow.rom", "../gow-rom"));

	private static LevelData level(String wad) throws Exception {
		Path p = ROM.resolve(wad);
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return new LevelData(WadFile.load(p));
	}

	private static float[] centre(ScriptWorld w, String name) {
		ScriptEntity e = w.entities().stream().filter(x -> x.name().equals(name)).findFirst().orElseThrow();
		LevelData.PlacedVolume v = e.volume;
		float[] c = new float[3];
		Mat4.transformPoint(v.world(), v.hull().sphere[0], v.hull().sphere[1], v.hull().sphere[2], c);
		assertTrue(v.contains(c[0], c[1], c[2]), name + " 볼륨 중심");
		return c;
	}

	private static void stay(ScriptWorld w, float[] p, int ticks) {
		for (int i = 0; i < ticks; i++) {
			w.tick(1 / 60f, p[0], p[1], p[2]);
		}
	}

	/** 구역 제목 기록. */
	static final class Zones implements ScriptListener {
		final List<Integer> zones = new ArrayList<>();

		@Override
		public void internal(int func, List<Object> args) {
			if (func == GameScriptHost.ZONE_REPORT) {
				zones.add(((Number) args.getFirst()).intValue());
			}
		}
	}

	/**
	 * ATHN03A 에서 "아테네로 향한 도로" (구역 3, ZoneArea = GlobalData:0) 를 본 뒤 ATHN03C → ATHN04A 워프 도착 (WarpFrmVista, ZoneReportGroup1 안):
	 * ATHN04A 의 ZoneArea 는 GlobalData:2 라 번호로 찾던 예전 엔진은 0 을 읽어 도착하자마자 구역 3 을 다시 띄웠다. 원작 (PS2 영상 5676–5696) 은 없고
	 * 광장 계단 (ZoneReportGroup, 영상 5701–5706) 에서 "아테네 도시 광장" (구역 4, 메시지 804) 만.
	 */
	@Test
	void zoneAreaFollowsTheNameIntoTheTownSquare() throws Exception {
		Zones r = new Zones();
		ScriptHost host = new ScriptHost();
		ScriptWorld a = new ScriptWorld(level("ATHN03A.WAD"), host, r);
		a.start();
		float[] s = a.startEntity().position();
		stay(a, s, 10);
		stay(a, centre(a, "ZoneReportGroup-nurbsSphere2-EntrySensor1"), 30);
		assertEquals(List.of(3), r.zones);
		assertEquals("ZoneArea", a.globalName(0));

		ScriptWorld p = new ScriptWorld(level("ATHN04A.WAD"), host, r); // 게임은 레벨이 바뀌어도 같은 호스트 (세션)
		assertEquals("ZoneArea", p.globalName(2));
		assertEquals("cameFromA5C", p.globalName(0));
		p.start();
		ScriptEntity arrive = p.entities().stream().filter(x -> x.name().equals("WarpFrmVista")).findFirst().orElseThrow();
		float[] at = arrive.position();
		ScriptEntity road = p.entities().stream().filter(x -> x.name().equals("ZoneReportGroup1-nurbsSphere13-EntrySensor1")).findFirst().orElseThrow();
		assertTrue(road.volume.contains(at[0], at[1], at[2]), "워프 도착점은 구역 3 볼륨 안");
		// 게임의 워프 도착은 서 있는 볼륨을 "이미 안" 으로 둔다 (placePlayer seamless) — 여기서는 볼륨에 들어오는 경우 (거리를 되돌아온 것과 같음)
		stay(p, at, 30);
		assertEquals(List.of(3), r.zones, "ZoneArea = 3 이면 구역 3 볼륨에 들어가도 '아테네로 향한 도로' 를 다시 띄우지 않음");
		assertEquals(false, host.getVar(null, 2, 0, 2), "ATHN04A GlobalData:0 = cameFromA5C (ATHN03A 의 ZoneArea 3 이 아님)");
		assertEquals(3, ((Number) host.global("ZoneArea")).intValue());
		stay(p, centre(p, "ZoneReportGroup-nurbsSphere13-EntrySensor1"), 30);
		assertEquals(List.of(3, 4), r.zones, "광장 계단에서 '아테네 도시 광장'");
		assertEquals(4, ((Number) host.global("ZoneArea")).intValue());
	}

	/** HydraDead: ATHN01E 는 GlobalData:2, ATHN01A 는 0, ATHN01D 는 1 (01D 의 2 는 SaveTrain). */
	@Test
	void hydraDeadIsOneVariableUnderThreeNumbers() throws Exception {
		ScriptHost host = new ScriptHost();
		new ScriptWorld(level("ATHN01E.WAD"), host, null);
		host.setVar(null, 2, 2, 2, true);
		new ScriptWorld(level("ATHN01A.WAD"), host, null);
		assertEquals(true, host.getVar(null, 2, 0, 2));
		new ScriptWorld(level("ATHN01D.WAD"), host, null);
		assertEquals(true, host.getVar(null, 2, 1, 2), "01D HydraDead");
		assertEquals(false, host.getVar(null, 2, 2, 2), "01D SaveTrain 은 따로");
		ScriptHost n = ScriptHost.neighbour(host);
		new ScriptWorld(level("ATHN01C.WAD"), n, null);
		assertEquals(true, n.getVar(null, 2, 0, 2), "이웃 슬롯 호스트도 같은 이름 표");
	}
}
