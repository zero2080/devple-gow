package devple.gow.engine.game;

import devple.gow.engine.script.ScriptEntity;
import devple.gow.engine.script.ScriptHost;
import devple.gow.engine.script.ScriptListener;
import devple.gow.engine.script.ScriptWorld;
import devple.gow.tools.anim.Mat4;
import devple.gow.tools.content.Content;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.ui.MessageTable;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * "아테네의 지붕" (구역 5, 메시지 805) — ATHN04C 계단을 올라 ATHN05A 마당에 들어서는 순간 05A 는 아직 이웃 슬롯이다 (LoadCheck 이음은 그 다음).
 * 원작 GameMgr 구역 제목 (0x1B0A28 → 0x1B8BA0) 은 슬롯을 보지 않아 영상 6539 에 제목이 뜨고 6545 까지 남는다. 예전 엔진은 이웃 슬롯의
 * Internal 0x10 을 버렸다 (43 문서 §19).
 */
class RooftopsZoneTitleTest {

	private static final Path ROM = Path.of(System.getProperty("gow.rom", "../gow-rom"));

	private static LevelData level(String name) throws Exception {
		Path p = Content.file(ROM, name + ".WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return new LevelData(WadFile.load(p));
	}

	@Test
	void neighbourSlotZoneReportShowsTheRooftopsTitle() throws Exception {
		List<Integer> zones = new ArrayList<>();
		SceneNav.Host host = new SceneNav.Host() {
			public void heroDied() {
			}

			public void neighbourLevel(ScriptListener.LevelRequest r) {
			}

			public void teleport(float[] pos, float yaw) {
			}

			public void message(int id, float seconds) {
			}

			public void orbs(String emitter, org.joml.Vector3f at) {
			}

			@Override
			public void neighbourZoneReport(int zone) {
				zones.add(zone);
			}
		};
		// 이웃 슬롯 세계의 리스너가 SceneNav 와 같은 길로 Internal 을 넘긴다
		ScriptListener neighbour = new ScriptListener() {
			@Override
			public void internal(int func, List<Object> args) {
				SceneNav.neighbourInternal(host, func, args);
			}
		};
		ScriptWorld a = new ScriptWorld(level("ATHN05A"), new ScriptHost(), neighbour);
		a.start();
		// 04C 계단 위 (05A 경계 밖) → 마당 ZoneReportGroup 볼륨 안으로 걸어 들어온다
		float[] stairs = {760, 40, 3030};
		for (int i = 0; i < 10; i++) {
			a.tick(1 / 60f, stairs[0], stairs[1], stairs[2]);
		}
		assertTrue(zones.isEmpty());
		ScriptEntity zr = a.entities().stream().filter(x -> x.name().equals("ZoneReportGroup-nurbsSphere9-EntrySensor1")).findFirst().orElseThrow();
		float[] c = new float[3];
		Mat4.transformPoint(zr.volume.world(), zr.volume.hull().sphere[0], zr.volume.hull().sphere[1], zr.volume.hull().sphere[2], c);
		float[] courtyard = {620, -16, 3010};
		assertTrue(zr.volume.contains(courtyard[0], courtyard[1] + 10, courtyard[2]), "마당 도착점은 구역 볼륨 안");
		for (int i = 0; i < 30; i++) {
			a.tick(1 / 60f, courtyard[0], courtyard[1] + 10, courtyard[2]);
		}
		assertEquals(List.of(5), zones, "이웃 슬롯에서도 구역 제목 (0x1B0A28)");
		MessageTable m = MessageTable.fromWad(WadFile.load(Content.file(ROM, "R_PERMK.WAD"))).orElseThrow();
		assertEquals("아테네의 지붕", GameScene.zoneTitle(m, 5));
		// 한 번만 (GlobalData ZoneArea = 5)
		for (int i = 0; i < 30; i++) {
			a.tick(1 / 60f, stairs[0], stairs[1], stairs[2]);
		}
		for (int i = 0; i < 30; i++) {
			a.tick(1 / 60f, courtyard[0], courtyard[1] + 10, courtyard[2]);
		}
		assertEquals(List.of(5), zones, "ZoneArea = 5 뒤에는 다시 안 뜸");
	}

	/** 이음 직전 10 초 안 제목은 새 씬이 이어 띄운다 (StatsZoneReport 10 초, 엔진 씬은 이음마다 HUD 를 새로 만듦) [근사: 엔진 구조]. */
	@Test
	void zoneTitleCarryWindowIsTheHudLoop() {
		assertEquals(10f, GameScene.ZONE_TITLE_SECS);
	}
}
