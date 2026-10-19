package devple.gow.engine.level;

import devple.gow.engine.game.LevelEntry;
import devple.gow.engine.script.ScriptHost;
import devple.gow.engine.script.ScriptWorld;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 레벨 시작 카메라 (43 문서 §6, 30 문서 §7): 레일 비용 0x1F1648(각도 범위로 자른 방향의 거리) + 0x400 접선 좌표계, 다른 슬롯 레벨 CMV,
 * 이음 전환 카메라 넘기기.
 */
class StartCameraTest {

	private static LevelData load(String level) throws Exception {
		Path wad = Path.of(System.getProperty("gow.rom", "../gow-rom"), level + ".WAD");
		Assumptions.assumeTrue(Files.exists(wad), "gow-rom 없음");
		return new LevelData(WadFile.load(wad));
	}

	/** 기본 Start 바닥 위 영웅 발. */
	private static Vector3f startFeet(LevelData d, LevelCollision col) {
		LevelEntry.Spot s = LevelEntry.resolve(d, new ScriptWorld(d, new ScriptHost(), null), null, null);
		float[] p = LevelEntry.snapToFloor(col, s.pos(), 256);
		assertNotNull(p);
		float g = col.groundHeight(p[0], p[1] + LevelEntry.UP, p[2], LevelEntry.DROP);
		return new Vector3f(p[0], Float.isNaN(g) ? p[1] : g, p[2]);
	}

	private static CameraRig settle(CameraRig rig, Vector3f feet) {
		rig.update(0, feet, 0, false);
		for (int i = 0; i < 60; i++) {
			rig.update(1 / 60f, feet, 0, false);
		}
		return rig;
	}

	/** 눈 → 대상 사이에 정적 충돌 면이 없다 (NoPlayerCollision 안내면·투명 벽 제외). */
	private static void assertSeesHero(String level, CameraRig rig, LevelCollision col) {
		Vector3f eye = rig.eye(), t = new Vector3f(rig.pose().target);
		Vector3f d = new Vector3f(t).sub(eye);
		float len = d.length();
		assertNull(col.raycast(eye, d.normalize(), len, LevelCollision.Tri::solidForPlayer), level + " " + rig.current() + " 눈 " + eye + " → " + t);
	}

	/**
	 * 0x400·각도 범위 레일 카메라 (플래그 0x435FE, 0x200 없음 → 비용 0x1F1648): 거리만 보던 이전 비용은 영웅 앞쪽(설계 각과 반대) 레일 점을 골라
	 * 각도 자르기로 눈이 벽 속에 들어갔다. 7 레벨 기본 시작에서 카메라가 영웅을 본다.
	 */
	@Test
	void railCameraSeesHeroAtDefaultStart() throws Exception {
		String[][] cases = {{"ATHN04C", "camera1"}, {"ATHN04D", "LedgeCam2"}, {"ATHN06C", "LwrCam18"}, {"ATRET01B", "FlrCam11"},
				{"ATRET01C", "LwrCam18"}, {"PAND01BB", "LowCam1"}, {"PAND04H", "WarpCam"}};
		for (String[] c : cases) {
			LevelData d = load(c[0]);
			LevelCollision col = new LevelCollision(d.sheets);
			CameraRig rig = settle(new CameraRig(d), startFeet(d, col));
			assertEquals(c[1], rig.current(), c[0]);
			assertSeesHero(c[0], rig, col);
		}
	}

	/** ATHN04C camera1: 레일 위, 영웅 뒤(−z) 128 거리 — 설계 정면 +z 를 따라 복도를 내다본다. */
	@Test
	void athn04cCameraBehindHero() throws Exception {
		LevelData d = load("ATHN04C");
		LevelCollision col = new LevelCollision(d.sheets);
		Vector3f feet = startFeet(d, col);
		CameraRig rig = settle(new CameraRig(d), feet);
		Vector3f eye = rig.eye();
		assertTrue(eye.z < feet.z - 100, "눈 " + eye + " 영웅 " + feet);
		assertEquals(944, eye.x, 16);
		assertEquals(128, eye.distance(rig.pose().target), 2);
	}

	/**
	 * 다른 슬롯 레벨 CMV (원작 두 슬롯 상주): ATHN02D 는 생성 시 자기 CMV(MedusaHeadCam, MedusaSpawnCam) 를 Vis Attr11_1 = 2 로 끄고,
	 * 방은 숙주 ATHN02C 의 Room2Cam 들이 잡는다. ATHN05AA(지오메트리 없는 부속) 는 숙주 ATHN05A 카메라.
	 */
	@Test
	void hostLevelCameras() throws Exception {
		LevelData d = load("ATHN02D"), host = load("ATHN02C");
		CameraRig rig = new CameraRig(d);
		rig.setOther(host);
		rig.setOwnerVolumes("gomedusaheadripcamera", false);
		rig.setOwnerVolumes("gomedusacamgroup", false);
		settle(rig, new Vector3f(-1232, 961, 1722)); // ATHN02C → ATHN02D LoadCheck 도착 (36 문서 하네스)
		assertTrue(rig.current().startsWith("Room2Cam"), rig.current());
		assertSeesHero("ATHN02D", rig, new LevelCollision(host.sheets));

		LevelData aa = load("ATHN05AA"), a = load("ATHN05A");
		CameraRig r2 = new CameraRig(aa);
		assertEquals("Follow", settle(r2, new Vector3f(725, 624, 3017)).current()); // 자기 CMV 만으로는 볼륨 밖
		CameraRig r3 = new CameraRig(aa);
		r3.setOther(a);
		settle(r3, new Vector3f(725, 624, 3017));
		assertNotEquals("Follow", r3.current());
		assertNotNull(a.camera(r3.current()), r3.current());
		float ey = r3.eye().y;
		assertFalse(ey >= 665 && ey <= 697, "눈 높이 " + ey + " 가 길거리 연기층(gosmokeplane2main) 안");
	}

	/** 이음 전환: 카메라 상태(활성 목록·현재 카메라·자세)를 새 레벨 장면이 이어받아 같은 카메라로 계속 (끊김 없음). */
	@Test
	void seamlessHandoffKeepsCamera() throws Exception {
		LevelData a = load("ATHN04A"), c = load("ATHN04C");
		Vector3f feet = new Vector3f(1232, -80, 1301); // ATHN04A → ATHN04C LoadCheck
		CameraRig ra = new CameraRig(a);
		ra.setOther(c);
		settle(ra, feet);
		String cam = ra.current();
		Vector3f eye = ra.eye();
		CameraRig rc = new CameraRig(c);
		rc.setOther(a);
		rc.resume(ra.handoff());
		assertEquals(cam, rc.current());
		rc.update(1 / 60f, feet, 0, false);
		assertEquals(cam, rc.current());
		assertTrue(rc.eye().distance(eye) < 4, rc.eye() + " vs " + eye);
	}
}
