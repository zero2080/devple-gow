package devple.gow.engine.level;

import devple.gow.engine.game.SceneNav;
import devple.gow.engine.nav.Platforms;
import devple.gow.engine.script.ScriptEntity;
import devple.gow.engine.script.ScriptHost;
import devple.gow.engine.script.ScriptListener;
import devple.gow.engine.script.ScriptWorld;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 도착 상태 (43 문서 §13.8): 이음 도착 카메라 (떠나온 레벨 카메라 유지), 이웃 스크립트 Vis 숨김, 두 슬롯 Animator 상태 넘기기,
 * 생성 스크립트가 움직이는 발판 위 시작 (PAND05B 엘리베이터).
 */
class ArrivalStateTest {

	private static LevelData load(String level) throws Exception {
		Path wad = Path.of(System.getProperty("gow.rom", "../gow-rom"), level + ".WAD");
		Assumptions.assumeTrue(Files.exists(wad), "gow-rom 없음");
		return new LevelData(WadFile.load(wad));
	}

	/**
	 * PAND01B ← PAND01A 이음 (-1028, 0, -1371): 도착점은 어느 CMV 에도 없고 (ShldDrCam 볼륨은 생성 스크립트가 끄고 켜는 엔티티 #188 을 아무도
	 * 부르지 않는다), 원작은 떠나온 레벨에서 쓰던 카메라가 남는다. 넘겨받은 상태가 없는 재현에서는 가장 가까운 PAND01A 후보 AltCam43.
	 */
	@Test
	void seamArrivalKeepsLeftLevelCamera() throws Exception {
		LevelData b = load("PAND01B"), a = load("PAND01A");
		CameraRig rig = new CameraRig(b);
		rig.setOther(a);
		rig.setOwnerVolumes("goshlddrcamnode", false); // ShldDrCamnode-CamOff (생성 센서)
		Vector3f hero = new Vector3f(-1028, 0, -1371);
		Vector3f seed = rig.seedApproach(hero, 0, a);
		assertNotNull(seed);
		assertEquals("AltCam43", rig.current());
		rig.update(1 / 60f, hero, 0, false);
		assertEquals("AltCam43", rig.current(), "후보 0 이면 현재 카메라 유지 (0x1EE8E0)");
		// 이미 후보가 있는 자리에서는 아무것도 하지 않는다
		CameraRig rig2 = new CameraRig(b);
		rig2.setOther(a);
		assertNull(rig2.seedApproach(new Vector3f(-1300, 0, -1371), 0, a));
	}

	/** PAND01A AxeAlcWall-CamSensor1: 카메라 전환 이벤트 1037 의 이름이 AltCam43 이면 goaxealcwall 을 숨긴다 (Vis Visible 2). */
	@Test
	void neighbourScriptHidesWallOnCameraEvent() throws Exception {
		LevelData a = load("PAND01A");
		List<String> hidden = new ArrayList<>();
		ScriptWorld w = new ScriptWorld(a, new ScriptHost(), new ScriptListener() {
			@Override
			public void vis(ScriptEntity source, List<String> objects, int[] values) {
				if (values.length > 0 && values[0] == 2) {
					hidden.addAll(objects);
				}
			}
		});
		w.start();
		w.placePlayer(-1028, 0, -1371, true);
		hidden.clear();
		w.postEvent(1037, "AltCam43", 0, 0, null);
		for (int i = 0; i < 5; i++) {
			w.tick(1 / 60f, -1028, 0, -1371);
		}
		assertTrue(hidden.contains("goaxealcwall"), hidden.toString());
	}

	/**
	 * PAND05B 기본 시작: 생성 센서가 엘리베이터 Animator "!0" 을 걸고 (t=0 윗면 281 = Start 282), 11.33 초에 바닥 0 으로 내려간다.
	 * 도착점 맞추기가 이 발판을 멈추면 안 된다.
	 */
	@Test
	void startOnScriptElevatorKeepsPlaying() throws Exception {
		LevelData d = load("PAND05B");
		ScriptWorld w = new ScriptWorld(d, new ScriptHost(), null);
		Set<String> owners = SceneNav.animatorOwners(w);
		assertTrue(owners.contains("goelevatorgrppand05b"));
		Platforms ps = Platforms.of(d, owners, owners);
		assertTrue(ps.play("goelevatorgrppand05b", "!0", 8, 1, 0));
		Platforms.Body b = ps.settleUnder(new Vector3f(869, 282, 1014), 24);
		assertNotNull(b);
		assertTrue(b.playing());
		assertEquals(0, b.time(), 1e-4);
		for (int i = 0; i < 12 * 60; i++) {
			ps.update(1 / 60f);
		}
		Platforms.Support s = ps.floor(869, 400, 1014, 1000);
		assertNotNull(s);
		assertEquals(0, s.y(), 2, "애니 끝 = 바닥");
	}

	/** 이음 넘기기: 열린 문 (DEST01 goenddoor "EndDoor") 상태를 새 Platforms 에 되살린다. */
	@Test
	void platformStateRoundTrip() throws Exception {
		LevelData d = load("DEST01");
		Set<String> owners = SceneNav.animatorOwners(new ScriptWorld(d, new ScriptHost(), null));
		Platforms p1 = Platforms.of(d, owners, owners);
		assertTrue(p1.play("goenddoor", "!0", 0, 1, 1e9f));
		p1.update(1 / 60f);
		List<Platforms.State> st = p1.states();
		assertEquals(1, st.size());
		Platforms p2 = Platforms.of(d, owners, owners);
		p2.restore(st);
		float[][] j1 = p1.body("goenddoor").joints(), j2 = p2.body("goenddoor").joints();
		for (int i = 0; i < j1.length; i++) {
			assertArrayEquals(j1[i], j2[i], 1e-3f);
		}
		// 맞지 않는 도착점 맞추기는 넘겨받은 자세를 지우지 않는다
		p2.settleUnder(new Vector3f(2425, 512, 6556), 24);
		assertNotNull(p2.body("goenddoor").anim());
	}
}
