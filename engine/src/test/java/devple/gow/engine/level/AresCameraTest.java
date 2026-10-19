package devple.gow.engine.level;

import devple.gow.engine.game.Tweaks;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** AresCamera1 (0x1F67C0) 수치·보조 함수. 수치는 R_PERMK TWK /GlobGame/BAR 와 같아야 한다. */
class AresCameraTest {

	private static final Path PERM = Path.of(System.getProperty("gow.rom", "../gow-rom"), "R_PERMK.WAD");

	@Test
	void constantsMatchBarTweaks() throws Exception {
		Assumptions.assumeTrue(Files.exists(PERM), "gow-rom 없음");
		Tweaks t = new Tweaks();
		t.load(WadFile.load(PERM));
		String n = t.findNode("/GlobGame/BAR/");
		assertNotNull(n);
		assertEquals(AresCamera.TARGET_RADIUS, t.f(n, "Camera Target Radius", -1) * 16, 1e-4f);
		assertEquals(AresCamera.LINE_ANGLE, t.f(n, "f line angle", -1));
		assertEquals(AresCamera.LINE_WEIGHT, t.f(n, "f line weight", -1));
		// bool 은 하위 바이트 (06 문서 §TWK)
		assertEquals(AresCamera.LINE_SYMMETRIC, (t.i(n, "f line symetrical", -1) & 0xFF) != 0);
		assertEquals(AresCamera.OBSCURED_SYMMETRIC, (t.i(n, "obscured symetrical", -1) & 0xFF) != 0);
		assertEquals(AresCamera.SEP_MIN, t.f(n, "seperating min", -1));
		assertEquals(AresCamera.SEP_MAX, t.f(n, "seperating max", -1));
		assertEquals(AresCamera.SEP_WEIGHT, t.f(n, "seperating weight", -1));
		assertEquals(AresCamera.OBSCURED_WEIGHT, t.f(n, "obscured weight", -1));
		assertEquals(0f, t.f(n, "dolly rot weight", -1), "dolly rot 가중 0 → 미사용");
		assertEquals(AresCamera.DIST_MIN, t.f(n, "distance min", -1) * 16);
		assertEquals(AresCamera.DIST_MIN_WEIGHT, t.f(n, "distance min weight", -1));
		assertEquals(AresCamera.RIM_MIN, t.f(n, "inside rim min", -1));
		assertEquals(AresCamera.RIM_WEIGHT, t.f(n, "inside rim weight", -1));
		assertEquals(AresCamera.HORIZ_MAX, t.f(n, "horizontal max", -1));
		assertEquals(AresCamera.HORIZ_WEIGHT, t.f(n, "horizontal weight", -1));
		assertEquals(AresCamera.VERT_MAX, t.f(n, "vertical max", -1));
		assertEquals(AresCamera.VERT_WEIGHT, t.f(n, "vertical weight", -1));
		assertEquals(AresCamera.MIN_DIST, t.f(n, "minimum dist", -1) * 16, 1e-4f);
		assertEquals(AresCamera.MIN_DIST_WEIGHT, t.f(n, "minimum dist weight", -1));
		assertEquals(AresCamera.MAX_DIST, t.f(n, "maximum dist", -1) * 16, 1e-4f);
		assertEquals(AresCamera.MAX_DIST_WEIGHT, t.f(n, "maximum dist weight", -1));
		assertEquals(AresCamera.SLOPE_WEIGHT, t.f(n, "slope weight", -1));
		assertEquals(AresCamera.BAY_FLOOR, t.f(n, "bay floor", -1));
		assertEquals(AresCamera.BOWL_BASE, t.f(n, "bowl base", -1));
		assertEquals(AresCamera.BOWL_X, t.f(n, "bowl centre x", -1));
		assertEquals(AresCamera.BOWL_Z, t.f(n, "bowl centre z", -1));
		assertEquals(AresCamera.BOWL_RADIUS, t.f(n, "bowl radius", -1));
		assertEquals(AresCamera.BOWL_SLOPE, t.f(n, "bowl slope", -1));
		assertFalse(t.raw(n, "bowl h delta").isPresent(), "bowl h delta 는 TWK 에 없음 → 실행 파일 기본 4");
		assertEquals(AresCamera.MAX_RAIL_SPEED, t.f(n, "max rail speed", -1));
		assertEquals(AresCamera.BOOM_IN, t.f(n, "boom speed in", -1));
		assertEquals(AresCamera.BOOM_OUT, t.f(n, "boom speed out", -1));
	}

	@Test
	void helpers() {
		// 0x1F0D00: 범위 밖 정도
		assertEquals(0, AresCamera.outside(20, 0, 35));
		assertEquals(5, AresCamera.outside(40, 0, 35), 1e-4f);
		// 0x1F5258: 안쪽은 그대로, 밖은 경계
		assertEquals(0.3f, AresCamera.softClamp(-1, 1, 0.3f), 1e-6f);
		assertEquals(1f, AresCamera.softClamp(-1, 1, 5f));
		assertEquals(-1f, AresCamera.softClamp(-1, 1, -5f));
		// 그릇: 안쪽 평평(바닥 0.52·16), 바깥 기울기 1.25
		float base = AresCamera.BOWL_BASE * 16, cz = AresCamera.BOWL_Z * 16;
		assertEquals(base, AresCamera.bowlHeight(0, cz), 1e-4f);
		assertEquals(base + 1.2499998f * 100, AresCamera.bowlHeight(0, cz + AresCamera.BOWL_RADIUS * 16 + 100), 1e-2f);
		float mid = AresCamera.bowlHeight(0, cz + AresCamera.BOWL_RADIUS * 16);
		assertTrue(mid > base && mid < base + 80, "전이 구간 Hermite " + mid);
	}
}
