package devple.gow.engine.nav;

import devple.gow.engine.game.SceneNav;
import devple.gow.engine.game.Tweaks;
import devple.gow.tools.anim.Mat4;
import devple.gow.tools.level.BallHull;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 움직이는 오브젝트 CDV 가 영웅을 막는다 (42 문서 §5): ATHN01D 쌍발차기 문 gotwinkickdoor (IO_CSM) 의 볼록 hull 중심에 둔 구는 밀려나고,
 * 스크립트 Vis Attr11_0 = 2 (충돌 끔) 면 그대로다.
 */
class BodyCollisionTest {

	static final Path ROM = Path.of(System.getProperty("gow.rom", "../gow-rom"));

	@Test
	void kickDoorBlocksUntilCollisionOff() throws Exception {
		Assumptions.assumeTrue(Files.exists(ROM.resolve("ATHN01D.WAD")), "gow-rom 없음");
		LevelData lv = new LevelData(WadFile.load(ROM.resolve("ATHN01D.WAD")));
		Tweaks tw = new Tweaks();
		tw.load(lv.wad);
		Interactables items = Interactables.of(lv, tw);
		Platforms ps = Platforms.of(lv, Set.of(), SceneNav.posedOwners(items));
		Platforms.Body door = ps.body("gotwinkickdoor");
		assertNotNull(door, "문 = 자세를 갖는 R1 오브젝트");
		assertNotNull(door.hull, "문 CDV");
		BallHull.Hull hh = door.hull.hulls.stream().filter(x -> x.planes().size() >= 4).findFirst().orElseThrow();
		int j = door.hull.hullJoint[door.hull.hulls.indexOf(hh)];
		float[] w = new float[3];
		Mat4.transformPoint(door.joints()[Math.min(j, door.joints().length - 1)], hh.center()[0], hh.center()[1], hh.center()[2], w);
		Vector3f c = new Vector3f(w[0], w[1], w[2]);
		assertTrue(ps.collideSphere(c, 12, Set.of()), "닫힌 문 안 → 밀려남");
		assertTrue(c.distance(w[0], w[1], w[2]) > 1);
		Vector3f again = new Vector3f(c);
		assertFalse(ps.collideSphere(again, 12, Set.of()), "밀려난 자리는 밖");
		ps.setSolid("gotwinkickdoor", false);
		Vector3f in = new Vector3f(w[0], w[1], w[2]);
		assertFalse(ps.collideSphere(in, 12, Set.of()), "충돌 끔 (Attr11_0 = 2)");
	}
}
