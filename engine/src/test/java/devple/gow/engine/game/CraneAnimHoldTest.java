package devple.gow.engine.game;

import devple.gow.engine.nav.Interactables;
import devple.gow.engine.nav.Platforms;
import devple.gow.tools.content.Content;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 43 §54.1-2: 가산 회전 트랙의 유지 (hold) 프레임에도 마지막 변화량을 더한다 (재생 0x111760: 구간 [start, start+count+hold−1], 표본 번호 count−1 로 묶음).
 * PAND04G 기중기 ANM_crane 의 CraneAni 는 가산 −16 (3 프레임) + 유지 65 를 열한 번 이어 24 초에 한 바퀴 가까이 돈다 — 예전 엔진은 유지를 빼서
 * 24 초 동안 13° 만 돌고 마지막 절대 키 (프레임 719) 에서 105° 를 한 번에 뛰었다 (크레인을 끝까지 돌리면 매단 블록이 반대편으로 순간이동).
 */
class CraneAnimHoldTest {

	private static final Path ROM = Path.of(System.getProperty("gow.rom", "../gow-rom"));

	@Test
	void craneTurnsSmoothlyAndTheBlockSweepsTheZones() throws Exception {
		Path p = Content.file(ROM, "PAND04G.WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		LevelData lv = new LevelData(WadFile.load(p));
		Tweaks tw = new Tweaks();
		tw.load(lv.wad);
		Interactables items = Interactables.of(lv, tw);
		Platforms ps = Platforms.of(lv, Set.of(), SceneNav.bodyOwners(items));
		Platforms.Body crane = ps.body("gocrane");
		Platforms.Body block = ps.body("goblocktranslatebg");
		assertNotNull(crane);
		assertNotNull(block);
		float prev = Float.NaN, total = 0;
		float[] blockAt4 = null;
		for (int i = 0; i <= 240; i++) {
			float t = i * 0.1f;
			ps.poseAt("gocrane", null, t);
			ps.update(0);
			float[] m = crane.joints()[2]; // CraneAni
			float yaw = (float) Math.toDegrees(Math.atan2(m[8], m[10]));
			if (!Float.isNaN(prev)) {
				float d = ((yaw - prev + 540) % 360) - 180;
				assertTrue(Math.abs(d) < 3f, "한 번에 뛰지 않음 t=" + t + " " + prev + " → " + yaw);
				total += d;
			}
			prev = yaw;
			if (i == 45) {
				blockAt4 = block.joints()[0].clone();
			}
		}
		assertEquals(-255f, total, 6f, "24 초에 −255° (마지막 절대 키 −37° 와 이어짐)");
		// 4.5 초 근처 매단 블록은 갈라진 구덩이 LowZone (248, −4259) 위
		assertEquals(248f, blockAt4[12], 40f);
		assertEquals(-4259f, blockAt4[14], 60f);
	}
}
