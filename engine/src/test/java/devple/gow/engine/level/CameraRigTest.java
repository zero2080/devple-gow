package devple.gow.engine.level;

import devple.gow.tools.level.CameraDef;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.level.NcvCurve;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 레벨 카메라: NCV 레일 포맷, CAM 파라미터, CMV 선택/레일 추적/전환 (30 문서). */
class CameraRigTest {

	private static LevelData load(String level) throws Exception {
		Path wad = Path.of(System.getProperty("gow.rom", "../gow-rom"), level + ".WAD");
		Assumptions.assumeTrue(Files.exists(wad), "gow-rom 없음");
		return new LevelData(WadFile.load(wad));
	}

	@Test
	void railIsContinuousWorldCurve() throws Exception {
		LevelData d = load("ATHN01A");
		NcvCurve r = d.curves.get("NCV_Boat1Rail1");
		assertNotNull(r);
		assertEquals(5, r.seg.length);
		// 구간 경계에서 이어진다 (전역 t 다항식)
		float[] a = new float[3], b = new float[3];
		for (int i = 0; i < r.knots.length - 1; i++) {
			float k = r.knots[i];
			r.eval(k - 1e-4f, a);
			r.eval(k + 1e-4f, b);
			assertEquals(a[2], b[2], 0.5f, "매듭 " + k);
		}
		// Boat1Cam1 월드 위치가 레일 위 (레일 = 월드 좌표)
		LevelData.PlacedCamera c = d.camera("Boat1Cam1");
		float[] e = c.eye();
		assertEquals(1314f, e[0], 1f);
		assertEquals(80.9f, e[1], 1f);
		assertSame(r, c.rail());
	}

	@Test
	void cameraParams() throws Exception {
		LevelData d = load("ATHN01A");
		CameraDef.Params p = d.camera("Boat1Cam4").def().p();
		assertEquals(5, p.priority());
		assertEquals(0.8f, p.damping(), 1e-4f);
		assertEquals(3, p.pitchMode());
		assertEquals(3, p.yawMode());
		assertEquals(160f, p.railMax(), 1e-3f);
		assertTrue(d.camera("IdleCam").def().p().animated());
		CameraDef c1 = d.camera("Boat1Cam1").def();
		assertEquals(2, c1.transitions().size());
		assertEquals("Boat1Cam2", c1.transitions().getFirst().from());
		assertEquals(1.3f, c1.transitions().getFirst().blend(), 1e-4f);
	}

	@Test
	void boatRunFramesKratos() throws Exception {
		LevelData d = load("ATHN01A");
		CameraRig rig = new CameraRig(d);
		Vector3f hero = new Vector3f(1536, 19.2f, 1956.6f);
		// 시작 인트로 (ESC_IdleCamGroup / ESC_EnemyIdleCamGroup): 생성 센서가 Idle2Cam 볼륨을 끄고, 시작 지점의 관절 애니 카메라 IdleCam (우선순위 50)
		rig.setOwnerVolumes("goenemyidlecamgroup", false);
		rig.update(1 / 60f, hero, 0, false);
		assertEquals("IdleCam", rig.current());
		// 9.5 초 뒤 IdleCamGroup-VisOff → 볼륨 카메라 Boat1Cam4
		rig.setOwnerVolumes("goidlecamgroup", false);
		rig.update(1 / 60f, hero, 0, false);
		assertEquals("Boat1Cam4", rig.current());
		// 갑판을 따라 −z 로 달린다: Boat1Cam1 로 전환(전환표 1.5초), 레일을 따라 같은 거리로 따라간다
		Vector3f eye = new Vector3f();
		for (int i = 0; i < 240; i++) {
			hero.set(1600, 0, 1840 - i * 0.6f);
			rig.update(1 / 60f, hero, 0, false);
			CameraRig.Pose p = rig.pose();
			p.eye(eye);
			float dist = eye.distance(p.target);
			assertTrue(dist > 20 && dist <= 1600.5f, "거리 " + dist);
		}
		assertEquals("Boat1Cam1", rig.current());
		CameraRig.Pose p = rig.pose();
		assertTrue(p.dist <= 160.5f, "Boat1Cam1 최대 거리 160: " + p.dist);
		// 설계 요 고정 (요 범위 0): 카메라 −Z 와 같은 요
		float[] f = d.camera("Boat1Cam1").forward();
		assertEquals(CameraRig.yawOf(new Vector3f(f[0], f[1], f[2])), p.yaw, 0.5f);
	}

	@Test
	void scriptRequestAndVolumeOff() throws Exception {
		LevelData d = load("ATHN01A");
		CameraRig rig = new CameraRig(d);
		Vector3f hero = new Vector3f(1600, 0, 1800);
		rig.update(1 / 60f, hero, 0, false);
		rig.setVolumeQuery(false);
		rig.request("RCM_LiftCam");
		rig.update(1 / 60f, hero, 0, false);
		assertEquals("LiftCam", rig.current());
		for (int i = 0; i < 120; i++) {
			rig.update(1 / 60f, hero, 0, false); // 후보 없음 → 유지
		}
		assertEquals("LiftCam", rig.current());
	}

	@Test
	void arenaFollowsAtDesignerAngle() throws Exception {
		LevelData d = load("ARENA06");
		CameraRig rig = new CameraRig(d);
		Vector3f hero = new Vector3f(-13, 21, 242);
		rig.update(1 / 60f, hero, 0, false);
		assertEquals("camera1", rig.current());
		assertEquals(160f, rig.pose().dist, 0.01f); // 거리 [10,10]×16
		assertEquals(hero.y + CameraRig.TARGET_HEIGHT, rig.pose().target.y, 1e-3f);
	}
}
